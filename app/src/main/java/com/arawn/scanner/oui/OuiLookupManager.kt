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
 * (`00:1A:11:…`, `00-1A-11-…`, `001a11…`), normalizes to the first 6 hex
 * characters upper-cased, and returns the vendor string or [UNKNOWN_VENDOR].
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
     * Resolve a MAC/BSSID to its manufacturer, or [UNKNOWN_VENDOR] when the
     * prefix is absent or the table is not yet warm. O(1), allocation-light,
     * and safe to call from the GPS heartbeat thread.
     */
    fun lookup(rawMac: String?): String {
        val map = table ?: return UNKNOWN_VENDOR
        val prefix = normalizePrefix(rawMac) ?: return UNKNOWN_VENDOR
        return map[prefix] ?: UNKNOWN_VENDOR
    }

    /**
     * Scrub `:` / `-` / `.` separators and whitespace, then take the first 6 hex
     * characters upper-cased. Returns null if fewer than 6 hex digits exist
     * (e.g. a malformed or empty address) so the caller falls back to unknown.
     */
    private fun normalizePrefix(rawMac: String?): String? {
        if (rawMac.isNullOrEmpty()) return null
        val sb = StringBuilder(6)
        for (c in rawMac) {
            if (c.isHexDigit()) {
                sb.append(c.uppercaseChar())
                if (sb.length == 6) return sb.toString()
            }
        }
        return null // fewer than 6 hex digits → not resolvable
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /**
     * Stream `assets/oui.txt` line-by-line so the full registry never lives in
     * memory twice. Expected line format (tab- or whitespace-delimited):
     *
     *     001A11<whitespace>Cisco Systems
     *
     * Blank lines, `#` comments, and rows whose prefix is not 6 hex digits are
     * skipped defensively. Later duplicate prefixes overwrite earlier ones.
     */
    private fun parseAssets(context: Context): Map<String, String> {
        val map = HashMap<String, String>(INITIAL_CAPACITY)
        context.assets.open(ASSET_NAME).bufferedReader().use { reader: BufferedReader ->
            reader.forEachLine { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachLine

                // Split on the FIRST run of whitespace/tab: prefix | vendor.
                val splitAt = line.indexOfFirst { it == '\t' || it == ' ' }
                if (splitAt <= 0) return@forEachLine

                val prefix = line.substring(0, splitAt)
                    .filter { it.isHexDigit() }
                    .uppercase()
                if (prefix.length != 6) return@forEachLine

                val vendor = line.substring(splitAt).trim()
                if (vendor.isNotEmpty()) map[prefix] = vendor
            }
        }
        return map
    }

    const val UNKNOWN_VENDOR = "Unknown Vendor"

    private const val TAG = "ArawnOui"
    private const val ASSET_NAME = "oui.txt"

    // The IEEE MA-L registry is ~37k entries; pre-size to skip rehash churn.
    private const val INITIAL_CAPACITY = 40_000
}
