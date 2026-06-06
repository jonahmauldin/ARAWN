package com.arawn.scanner

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import android.widget.Toast
import com.arawn.core.database.CoordinatePair
import com.arawn.scanner.export.EnrichedCsvExporter
import com.arawn.scanner.ui.FrequencyCurveChart
import com.arawn.scanner.ui.HeatmapPanel
import com.arawn.scanner.ui.HeatPoint
import com.arawn.scanner.ui.OfflineMapPanel
import com.arawn.scanner.knowledge.KnowledgeScreen
import com.arawn.scanner.vault.VaultScreen
import kotlinx.coroutines.launch

/**
 * ARAWN Phase 1 entry point.
 *
 * Owns the runtime-permission flow and the lifecycle of the bound
 * [WirelessScannerService], and renders the operator terminal that streams
 * fused [ScanPacket]s as they arrive.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var container: AppContainer

    private var service: WirelessScannerService? by mutableStateOf(null)
    private var scanning by mutableStateOf(false)

    /** True while the Vault screen is unlocked. Cleared on app background or tab change. */
    private var isVaultUnlocked by mutableStateOf(false)

    /**
     * Set just before the Vault launches the system file picker. The picker is a
     * separate Activity, so launching it sends us through onStop — which would
     * otherwise auto-lock the vault, tear down the unlocked screen (cancelling the
     * import coroutine), and drop the picked file. This one-shot flag tells onStop
     * to skip the auto-lock for that round-trip.
     */
    private var suppressVaultAutoLock = false

    /** True once bindService() has been called and not yet unbound. */
    private var bindRequested = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? WirelessScannerService.LocalBinder)?.service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    // Result is reported back to Compose via this state; we start scanning only
    // once the user resolves the dialog (and only with whatever was granted).
    private var pendingStart by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (pendingStart) {
            pendingStart = false
            startTracking()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = (application as ArawnApplication).container
        setContent {
            ArawnTheme {
                // Cold-start boot screen. rememberSaveable so rotation
                // mid-session does not replay the splash.
                var booted by rememberSaveable { mutableStateOf(false) }
                Crossfade(
                    targetState = booted,
                    animationSpec = tween(durationMillis = 380),
                    label = "boot-gate",
                ) { isBooted ->
                    if (!isBooted) {
                        BootScreen(onComplete = { booted = true })
                        return@Crossfade
                    }
                    AppContent()
                }
            }
        }
    }

    @Composable
    private fun AppContent() {
        val lines = remember { mutableStateListOf<String>() }
                // Latest Wi-Fi snapshot, fed straight to the live spectrum chart.
                val latestWifi = remember { mutableStateListOf<WifiObservation>() }
                // GPS track of the active session, projected for the offline map.
                val mapCoords = remember { mutableStateListOf<CoordinatePair>() }
                // Live RSSI heat points for the heatmap tab (Phase G).
                val heatPoints = remember { mutableStateListOf<HeatPoint>() }
                // Most recent GPS fix — shown as the live position marker on the OPS map.
                var liveCoord by remember { mutableStateOf<CoordinatePair?>(null) }
                var viewMode by remember { mutableStateOf(ViewMode.CONSOLE) }
                var currentScreen by remember { mutableStateOf(AppScreen.RECON) }
                // Phase G: filter randomized/private MACs from console + spectrum.
                var filterRandomized by remember { mutableStateOf(false) }

                // Drain the service stream into the console while bound.
                LaunchedEffect(service) {
                    service?.packets?.collect { packet ->
                        lines.add(packet.toConsoleString())
                        // Cap the buffer so long sessions don't grow unbounded.
                        while (lines.size > MAX_CONSOLE_LINES) lines.removeAt(0)
                        // Replace the spectrum's view of the world with this fix's
                        // Wi-Fi environment; the chart recomposes and re-animates.
                        latestWifi.clear()
                        latestWifi.addAll(packet.wifi)
                        // Track live position for the OPS Center map.
                        liveCoord = CoordinatePair(
                            latitude    = packet.latitude,
                            longitude   = packet.longitude,
                            timestampMs = packet.timestampMs,
                        )
                        // Accumulate one heat point per GPS fix (Phase G heatmap).
                        val maxRssi = maxOf(
                            packet.wifi.maxOfOrNull { it.rssiDbm } ?: Int.MIN_VALUE,
                            packet.ble.maxOfOrNull  { it.rssiDbm } ?: Int.MIN_VALUE,
                        )
                        if (maxRssi > Int.MIN_VALUE) {
                            heatPoints.add(HeatPoint(packet.latitude, packet.longitude, maxRssi))
                            while (heatPoints.size > MAX_HEAT_POINTS) heatPoints.removeAt(0)
                        }
                    }
                }

                // Keep the local flag in sync with the bound service's truth
                // (covers re-bind after rotation while a session is running).
                LaunchedEffect(service) {
                    service?.scanning?.collect { scanning = it }
                }

                // Load the active session's GPS track from the local DB whenever
                // the map is shown, and refresh as new fixes land (lines.size
                // ticks once per packet). Uses the lightweight coordinate-only
                // projection — no signal subtree pulled into memory.
                LaunchedEffect(viewMode, lines.size) {
                    if (viewMode != ViewMode.MAP) return@LaunchedEffect
                    val dao = container.wirelessDao
                    val sessionId = dao.getLatestSessionId()
                    val coords = sessionId?.let { dao.getSessionCoordinates(it) }.orEmpty()
                    mapCoords.clear()
                    mapCoords.addAll(coords)
                }

                Scaffold(
                    containerColor = Color.Black,
                    bottomBar = {
                        ArawnBottomBar(
                            currentScreen = currentScreen,
                            onNavigate = { currentScreen = it },
                        )
                    },
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    ) {
                        when (currentScreen) {
                            AppScreen.OPS      -> OpsScreen(
                                wirelessDao  = container.wirelessDao,
                                geoDao       = container.geoDao,
                                missionDao   = container.missionDao,
                                livePosition = liveCoord,
                            )
                            AppScreen.RECON    -> ScannerScreen(
                                scanning        = scanning,
                                lines           = lines,
                                wifi            = latestWifi,
                                coordinates     = mapCoords,
                                heatPoints      = heatPoints,
                                filterRandomized = filterRandomized,
                                viewMode        = viewMode,
                                onSelectView    = { viewMode = it },
                                onToggleFilter  = { filterRandomized = !filterRandomized },
                                onStart         = { requestPermissionsThenStart() },
                                onStop          = { stopTracking() },
                                onClear         = { lines.clear(); heatPoints.clear() },
                                onExport        = { exportLatestSession() },
                                onReport        = { generateHtmlReport() },
                            )
                            AppScreen.MISSIONS -> com.arawn.scanner.missions.MissionsScreen(
                                missionDao        = container.missionDao,
                                geoDao            = container.geoDao,
                                wirelessDao       = container.wirelessDao,
                                missionRepository = container.missionRepository,
                                livePosition      = liveCoord,
                            )
                            AppScreen.REPORTS  -> com.arawn.scanner.reports.ReportsScreen(
                                wirelessDao          = container.wirelessDao,
                                htmlReportExporter   = container.htmlReportExporter,
                                enrichedCsvExporter  = container.enrichedCsvExporter,
                                reportDao            = container.reportDao,
                            )
                            AppScreen.VAULT    -> VaultScreen(
                                isUnlocked         = isVaultUnlocked,
                                onAuthenticate     = { authenticateVault() },
                                onWillPickFile     = { suppressVaultAutoLock = true },
                                onWillOpenExternal = { suppressVaultAutoLock = true },
                                vaultRepository    = container.vaultRepository,
                                vaultDao           = container.vaultDao,
                            )
                            AppScreen.DOCS     -> KnowledgeScreen(
                                knowledgeRepository = container.knowledgeRepository,
                            )
                        }
                    }
                }
    }

    override fun onStart() {
        super.onStart()
        // Attach to the service with flag 0: we don't create it here, but if a
        // session is already running (or gets started), onServiceConnected fires
        // and the console reconnects — this is what survives rotation.
        doBind()
    }

    override fun onStop() {
        super.onStop()
        doUnbind()
        // Auto-lock on genuine background — but NOT when we launched the file
        // picker ourselves (a separate Activity that also triggers onStop).
        // Skipping the lock there keeps the unlocked Vault screen composed so the
        // picked file's import coroutine survives and completes.
        if (suppressVaultAutoLock) {
            suppressVaultAutoLock = false
        } else {
            isVaultUnlocked = false
            // Genuine background (not a file-picker / viewer round-trip): destroy any
            // plaintext that was decrypted for viewing so it never outlives the lock.
            container.vaultRepository.wipeViewCache()
        }
    }

    override fun onResume() {
        super.onResume()
        // Defensive: never let the one-shot suppress flag linger past a resume
        // (e.g. if a picker were dismissed without ever stopping the activity).
        suppressVaultAutoLock = false
    }

    private fun doBind() {
        if (bindRequested) return
        // BIND_AUTO_CREATE so the connection reliably establishes even before the
        // service is started — a plain flag-0 bind to a not-yet-running service
        // silently fails, leaving the UI unable to ever receive packets. When a
        // session is live the service is also started (foreground), so unbinding
        // on stop won't tear it down mid-run.
        val bound = bindService(
            Intent(this, WirelessScannerService::class.java),
            connection,
            android.content.Context.BIND_AUTO_CREATE,
        )
        bindRequested = bound
    }

    private fun doUnbind() {
        if (!bindRequested) return
        runCatching { unbindService(connection) }
        bindRequested = false
        service = null
    }

    // ---- Permission + service control ---------------------------------

    private fun requestPermissionsThenStart() {
        val needed = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            startTracking()
        } else {
            pendingStart = true
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun startTracking() {
        val intent = Intent(this, WirelessScannerService::class.java).apply {
            action = WirelessScannerService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
        // We bound in onStart() with flag 0; once the service is up, that
        // binding delivers onServiceConnected and the console starts streaming.
        doBind() // no-op if already bound
        scanning = true // optimistic; reconciled by the service's scanning flow
    }

    private fun stopTracking() {
        val intent = Intent(this, WirelessScannerService::class.java).apply {
            action = WirelessScannerService.ACTION_STOP
        }
        startService(intent)
        // Keep the binding for the activity's lifetime (released in onStop); the
        // service drops out of the foreground and idles until unbound.
        scanning = false
    }

    /**
     * Dump the most recent session to TWO files in Documents/ARAWN/: the strict
     * WiGLE-1.6 CSV (unchanged, importable by WiGLE) and the enriched analytics
     * CSV (vendor + MAC scope + full radio columns). Separate exporters / files
     * so neither format constrains the other. Runs on a background coroutine;
     * the combined outcome is reported via a single Toast.
     */
    private fun exportLatestSession() {
        lifecycleScope.launch {
            val wigle = container.dataLogExporter.exportLatestSession()
            val enriched = container.enrichedCsvExporter.exportLatestSession()
            val enrichedNote = when (enriched) {
                is EnrichedCsvExporter.Result.Success -> "enriched: ${enriched.rows} rows"
                is EnrichedCsvExporter.Result.Failure -> "enriched failed: ${enriched.message}"
                EnrichedCsvExporter.Result.NoData,
                EnrichedCsvExporter.Result.NoSession -> "enriched: no data"
            }
            Toast.makeText(
                this@MainActivity,
                "${wigle.message}\n$enrichedNote",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun generateHtmlReport() {
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "Generating report…", Toast.LENGTH_SHORT).show()
            val result = container.htmlReportExporter.exportLatestSession()
            Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
        }
    }

    // ---- Vault biometric authentication --------------------------------

    /**
     * Launch a [BiometricPrompt] to authenticate the user before showing the
     * Vault contents. On success, [isVaultUnlocked] is set to true.
     *
     * Allows both biometric (fingerprint / face) and device credential (PIN /
     * pattern / password) so the app works even without enrolled biometrics.
     * Falls back gracefully when the device has no secure lock screen.
     */
    private fun authenticateVault() {
        val canAuth = BiometricManager.from(this).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        if (canAuth == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ||
            canAuth == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
        ) {
            // No biometric / credential enrolled — unlock directly (device has no lock)
            isVaultUnlocked = true
            return
        }

        val executor = ContextCompat.getMainExecutor(this)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                isVaultUnlocked = true
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // User cancelled or hardware error — stay locked
            }
            override fun onAuthenticationFailed() {
                // Biometric not recognised — prompt stays open, no state change
            }
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("ARAWN VAULT")
            .setSubtitle("Authenticate to access encrypted storage")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        BiometricPrompt(this, executor, callback).authenticate(promptInfo)
    }

    private fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private companion object {
        const val MAX_CONSOLE_LINES = 500
        const val MAX_HEAT_POINTS   = 2000  // ~33 min at 1 fix/s
    }
}

// ---------------------------------------------------------------------------
//  UI
// ---------------------------------------------------------------------------

/** Which viewport the main pane is showing. */
private enum class ViewMode { CONSOLE, SPECTRUM, MAP, HEATMAP }

/**
 * Consumes all nested-scroll and fling events produced by the osmdroid MapView
 * before they can propagate up through the Compose gesture system. Without this,
 * panning the map sends scroll velocity to the parent Column, visually shifting
 * the layout and hiding the view-mode tabs.
 */
private val MapScrollBarrier = object : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset = available
    override suspend fun onPreFling(available: Velocity): Velocity = available
}

private val Amber = Color(0xFFE0B341)
private val TerminalGreen = Color(0xFF35D07F)
private val PanelBlack = Color(0xFF0A0A0A)
private val Ink = Color(0xFFE6E6E6)

@Composable
private fun ArawnTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = TerminalGreen,
            background = Color.Black,
            surface = PanelBlack,
            onBackground = Ink,
            onSurface = Ink,
        ),
        content = content,
    )
}

/** One cell of the low-profile viewport selector. */
@Composable
private fun ViewTab(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        color = if (selected) Amber else Color.Gray,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        modifier = modifier
            .clickable(onClick = onClick)
            .background(
                if (selected) Color(0xFF161616) else Color.Transparent,
                RoundedCornerShape(4.dp),
            )
            .padding(vertical = 6.dp),
    )
}

@Composable
private fun ScannerScreen(
    scanning: Boolean,
    lines: SnapshotStateList<String>,
    wifi: SnapshotStateList<WifiObservation>,
    coordinates: SnapshotStateList<CoordinatePair>,
    heatPoints: List<HeatPoint>,
    filterRandomized: Boolean,
    viewMode: ViewMode,
    onSelectView: (ViewMode) -> Unit,
    onToggleFilter: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
    onReport: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {

            // Header / status bar.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "ARAWN // RF SURVEY",
                    color = Amber,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 16.sp,
                )
                Spacer(Modifier.weight(1f))
                // Phase G: randomized-MAC filter toggle
                Text(
                    text = "[ RAND ]",
                    color = if (filterRandomized) Color(0xFFCC3B3B) else Color(0xFF2A2A2A),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .clickable(onClick = onToggleFilter)
                        .padding(horizontal = 6.dp),
                )
                Text(
                    text = if (scanning) "● LIVE" else "○ IDLE",
                    color = if (scanning) TerminalGreen else Color.Gray,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                )
            }

            Spacer(Modifier.height(8.dp))

            // Four-way viewport selector (Phase G adds HEATMAP).
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ViewTab("▤ CONSOLE", viewMode == ViewMode.CONSOLE, Modifier.weight(1f)) {
                    onSelectView(ViewMode.CONSOLE)
                }
                ViewTab("∿ SPEC", viewMode == ViewMode.SPECTRUM, Modifier.weight(1f)) {
                    onSelectView(ViewMode.SPECTRUM)
                }
                ViewTab("⌖ MAP", viewMode == ViewMode.MAP, Modifier.weight(1f)) {
                    onSelectView(ViewMode.MAP)
                }
                ViewTab("⊕ HEAT", viewMode == ViewMode.HEATMAP, Modifier.weight(1f)) {
                    onSelectView(ViewMode.HEATMAP)
                }
            }

            Spacer(Modifier.height(10.dp))

            // Main viewport — either the scrolling text console or the live
            // spectrum chart, toggled from the header.
            when (viewMode) {
                ViewMode.CONSOLE -> {
                    val listState = rememberLazyListState()
                    LaunchedEffect(lines.size) {
                        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(PanelBlack, RoundedCornerShape(6.dp))
                            .padding(8.dp),
                    ) {
                        if (lines.isEmpty()) {
                            Text(
                                text = "// awaiting first GPS fix…\n// press START TRACKING",
                                color = Color(0xFF4A4A4A),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                            )
                        }
                        // Phase G: filter lines containing randomized MACs when toggle is on
                        val visibleLines = if (filterRandomized)
                            lines.filter { "Randomized (private)" !in it }
                        else
                            lines
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            items(visibleLines) { line ->
                                Text(
                                    text = line,
                                    color = TerminalGreen,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }

                ViewMode.SPECTRUM -> {
                    // Phase G: strip randomized-MAC APs from the chart when filter is on
                    val visibleWifi = if (filterRandomized)
                        wifi.filter { it.vendorName != "Randomized (private)" }
                    else
                        wifi
                    FrequencyCurveChart(
                        observations = visibleWifi,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }

                ViewMode.MAP -> {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            // Prevent osmdroid pan/fling events from propagating up
                            // to the Compose Column and shifting the layout.
                            .nestedScroll(MapScrollBarrier)
                            // Clip map tile overflow so tabs above are never obscured.
                            .clipToBounds()
                            .background(PanelBlack, RoundedCornerShape(6.dp)),
                    ) {
                        OfflineMapPanel(
                            coordinates = coordinates,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (coordinates.isEmpty()) {
                            Text(
                                text = "// no track for the latest session\n" +
                                    "// (offline base map needs local tile archives)",
                                color = Color(0xFF4A4A4A),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                    }
                }

                ViewMode.HEATMAP -> {
                    HeatmapPanel(
                        points   = heatPoints,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Controls.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onStart,
                    enabled = !scanning,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = TerminalGreen),
                ) {
                    Text("START TRACKING", fontFamily = FontFamily.Monospace, color = Color.Black)
                }
                Button(
                    onClick = onStop,
                    enabled = scanning,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFCC3B3B)),
                ) {
                    Text("STOP", fontFamily = FontFamily.Monospace, color = Color.White)
                }
                Button(
                    onClick = onClear,
                    modifier = Modifier.width(64.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
                ) {
                    Text("CLR", fontFamily = FontFamily.Monospace, color = Ink)
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onExport,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF161616)),
                ) {
                    Text(
                        text = "⤓ EXPORT CSV",
                        fontFamily = FontFamily.Monospace,
                        color = Amber,
                        fontSize = 12.sp,
                    )
                }
                Button(
                    onClick = onReport,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF161616)),
                ) {
                    Text(
                        text = "⊞ HTML REPORT",
                        fontFamily = FontFamily.Monospace,
                        color = Amber,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun ArawnBottomBar(
    currentScreen: AppScreen,
    onNavigate: (AppScreen) -> Unit,
) {
    NavigationBar(
        containerColor = PanelBlack,
        contentColor = Ink,
    ) {
        AppScreen.entries.forEach { screen ->
            NavigationBarItem(
                selected = currentScreen == screen,
                onClick = { onNavigate(screen) },
                icon = {
                    Text(
                        text = screen.icon,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                    )
                },
                label = {
                    Text(
                        text = screen.label,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Amber,
                    selectedTextColor = Amber,
                    unselectedIconColor = Color.Gray,
                    unselectedTextColor = Color.Gray,
                    indicatorColor = Color(0xFF161616),
                ),
            )
        }
    }
}
