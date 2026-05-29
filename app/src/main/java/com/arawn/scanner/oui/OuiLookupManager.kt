package com.arawn.scanner.oui

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Offline IEEE OUI → manufacturer resolver (Phase 3, Module B).
 *
 * 100% on-device: the entire registry ships as a flat `assets/oui.txt` file and
 * is parsed into an in-memory [HashMap]. There is no network endpoint, no REST
 * lookup, and no third-party SDK — a hard project constraint.
 *
 * ### Threading model
 * A process-wide `object` singleton. The table is loaded once, lazily, on a
 * background IO coroutine ([warmUp]); the heavy file parse never touches a
 * sensor or UI thread. Reads ([lookup]) are lock-free: the finished map is
 * published through a single `@Volatile` reference, so any thread that observes
 * a non-null [table] sees a fully-built, immutable map (safe publication). An
 * [AtomicBoolean] guard makes [warmUp] idempotent under concurrent callers.
 *
 * ### Lookup contract
 * [lookup] accepts a raw BSSID / BLE MAC in any common form
 * (`00:1A:11:…`, `00-1A-11-…`, `001a11…`) and resolves it in two steps:
 *
 *  1. **Classify the address.** The first octet's low two bits are inspected.
 *     A locally-administered address (bit 1 set) is a randomized / private MAC
 *     — Android Wi-Fi client randomization and BLE Resolvable Private Addresses
 *     both land here — and can NEVER be attributed to a manufacturer, so it
 *     short-circuits to [RANDOMIZED_VENDOR] rather than a misleading "unknown".
 *     A multicast/group address (bit 0 set) returns [UNKNOWN_VENDOR].
 *
 *  2. **Tiered prefix probe.** For globally-administered addresses the table is
 *     probed longest-prefix-first — 9 hex chars (MA-S /36), then 7 (MA-M /28),
 *     then 6 (MA-L /24) — so a vendor that owns only a sub-block of a shared
 *     /24 resolves correctly instead of being attributed to the block owner.
 *     A /24-only asset still works: only the 6-char probe ever hits.
 *
 * Before the table is warm, every call returns [UNKNOWN_VENDOR]; callers that
 * want to avoid stamping a premature "unknown" can gate on [isReady].
 */
object OuiLookupManager {

    /** Published once parsing completes; null while cold. Read lock-free. */
    @Volatile
    private var table: Map<String, String>? = null

    private val loadStarted = AtomicBoolean(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** True once the registry has been parsed and is available for lookups. */
    val isReady: Boolean get() = table != null

    /** Number of OUI prefixes loaded (0 until warm). Diagnostics only. */
    val size: Int get() = table?.size ?: 0

    /**
     * Kick off a one-shot background parse of `assets/oui.txt`. Safe to call
     * repeatedly and from any thread; only the first call does work. Pass the
     * application context to avoid leaking an Activity/Service.
     */
    fun warmUp(context: Context) {
        if (!loadStarted.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        scope.launch {
            table = runCatching { parseAssets(appContext) }
                .onFailure {
                    android.util.Log.w(TAG, "OUI table load failed; vendors will read as unknown", it)
                }
                .getOrDefault(emptyMap())
            android.util.Log.i(TAG, "OUI table ready: ${table?.size ?: 0} prefixes")
        }
    }

    /**
     * Classification of a MAC's administration scope — the *reason* a lookup did
     * or did not resolve. Exposed so callers (and Phase 2's classifier) can tell
     * "we have no data" apart from "this address is structurally unattributable".
     */
    enum class MacKind {
        /** Globally-administered unicast: an IEEE OUI lookup is meaningful. */
        GLOBAL,
        /** Locally-administered: randomized / private MAC. No vendor exists. */
        RANDOMIZED,
        /** Multicast / group address. No single vendor. */
        MULTICAST,
        /** Empty or too few hex digits to read the first octet. */
        MALFORMED,
    }

    /**
     * Inspect the first octet's low two bits. Bit 0 = multicast/group; bit 1 =
     * locally administered (randomized / private). Order matters: a multicast
     * frame is reported as [MacKind.MULTICAST] even if bit 1 also happens to be
     * set. Allocation-free.
     */
    fun classify(rawMac: String?): MacKind {
        val octet = firstOctet(rawMac) ?: return MacKind.MALFORMED
        return when {
            octet and 0x01 != 0 -> MacKind.MULTICAST
            octet and 0x02 != 0 -> MacKind.RANDOMIZED
            else -> MacKind.GLOBAL
        }
    }

    /**
     * Resolve a MAC/BSSID to its manufacturer. Returns:
     *  - [RANDOMIZED_VENDOR] for locally-administered (private) addresses,
     *  - [UNKNOWN_VENDOR] for multicast, malformed, cold-table, or unmatched,
     *  - else the vendor via a longest-prefix-first table probe.
     *
     * O(1) (at most three hash probes), allocation-light, and safe to call from
     * the GPS heartbeat thread.
     */
    fun lookup(rawMac: String?): String {
        val map = table ?: return UNKNOWN_VENDOR
        when (classify(rawMac)) {
            MacKind.RANDOMIZED -> return RANDOMIZED_VENDOR
            MacKind.MULTICAST, MacKind.MALFORMED -> return UNKNOWN_VENDOR
            MacKind.GLOBAL -> { /* fall through to the tiered probe */ }
        }
        // Up to 9 hex chars covers MA-S (/36). Probe longest prefix first.
        val hex = normalizeHex(rawMac, MAX_PREFIX_LEN) ?: return UNKNOWN_VENDOR
        if (hex.length >= 9) map[hex.substring(0, 9)]?.let { return it } // MA-S /36
        if (hex.length >= 7) map[hex.substring(0, 7)]?.let { return it } // MA-M /28
        if (hex.length >= 6) map[hex.substring(0, 6)]?.let { return it } // MA-L /24
        return UNKNOWN_VENDOR
    }

    /**
     * The integer value of the first octet (first two hex digits), or null when
     * fewer than two hex digits are present. Used for administration-scope bits.
     */
    private fun firstOctet(rawMac: String?): Int? {
        val hex = normalizeHex(rawMac, 2) ?: return null
        if (hex.length < 2) return null
        return hex.substring(0, 2).toInt(16)
    }

    /**
     * Scrub `:` / `-` / `.` separators and whitespace, then take up to [max] hex
     * characters upper-cased. Returns null for empty input or when no hex digit
     * is present; may return fewer than [max] chars for short inputs (callers
     * length-check before slicing).
     */
    private fun normalizeHex(rawMac: String?, max: Int): String? {
        if (rawMac.isNullOrEmpty()) return null
        val sb = StringBuilder(max)
        for (c in rawMac) {
            if (c.isHexDigit()) {
                sb.append(c.uppercaseChar())
                if (sb.length == max) break
            }
        }
        return if (sb.isEmpty()) null else sb.toString()
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /**
     * Stream `assets/oui.txt` line-by-line so the full registry never lives in
     * memory twice. Tolerant of two layouts:
     *
     *     001A11<tab>Cisco Systems                (legacy: bare 6-hex MA-L)
     *     00:00:0C<tab>Cisco<tab>Cisco Systems    (Wireshark manuf, MA-L)
     *     70:B3:D5:1A:00:00/28<tab>Vendor<tab>…   (Wireshark manuf, MA-M /28)
     *
     * The prefix token may carry separators and an optional `/NN` CIDR mask; its
     * stored key length is the mask's nibble count (24→6, 28→7, 36→9) or, absent
     * a mask, the count of hex digits present. When multiple name fields follow
     * (manuf short + long), the FIRST (short) name wins. Blank lines, `#`
     * comments, and prefixes that don't normalize to 6/7/9 hex are skipped.
     */
    private fun parseAssets(context: Context): Map<String, String> {
        val map = HashMap<String, String>(INITIAL_CAPACITY)
        context.assets.open(ASSET_NAME).bufferedReader().use { reader: BufferedReader ->
            reader.forEachLine { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachLine

                // Split on the FIRST run of whitespace/tab: prefix | name(s).
                val splitAt = line.indexOfFirst { it == '\t' || it == ' ' }
                if (splitAt <= 0) return@forEachLine

                val prefix = normalizeTablePrefix(line.substring(0, splitAt)) ?: return@forEachLine

                // Remainder may be "short\tlong"; the short name is field one.
                val rest = line.substring(splitAt).trim()
                val tab = rest.indexOf('\t')
                val vendor = (if (tab >= 0) rest.substring(0, tab) else rest).trim()
                if (vendor.isNotEmpty()) map[prefix] = vendor
            }
        }
        return map
    }

    /**
     * Turn a prefix token into its stored key, or null if unusable. Honors an
     * optional `/NN` mask to size the key (so MA-M/MA-S blocks key at 7/9 hex);
     * otherwise the key is exactly the hex digits present. Only 6/7/9-length
     * keys are accepted — the lengths the tiered [lookup] probe expects.
     */
    private fun normalizeTablePrefix(token: String): String? {
        val slash = token.indexOf('/')
        val maskBits = if (slash >= 0) token.substring(slash + 1).trim().toIntOrNull() else null
        val hex = token.let { if (slash >= 0) it.substring(0, slash) else it }
            .filter { it.isHexDigit() }
            .uppercase()

        val len = if (maskBits != null) maskBits / 4 else hex.length
        if (len != 6 && len != 7 && len != 9) return null
        if (hex.length < len) return null
        return hex.substring(0, len)
    }

    const val UNKNOWN_VENDOR = "Unknown Vendor"

    /** Locally-administered (randomized / private) MAC — no manufacturer exists. */
    const val RANDOMIZED_VENDOR = "Randomized (private)"

    private const val TAG = "ArawnOui"
    private const val ASSET_NAME = "oui.txt"

    /** Longest key we store (MA-S /36 = 9 hex nibbles); bounds [normalizeHex]. */
    private const val MAX_PREFIX_LEN = 9

    // The Wireshark manuf set (MA-L+M+S) is ~57k entries; pre-size above
    // count / 0.75 load factor so warm-up never rehashes.
    private const val INITIAL_CAPACITY = 80_000
}
