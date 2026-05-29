package com.arawn.scanner

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import android.widget.Toast
import com.arawn.scanner.db.ArawnDatabase
import com.arawn.scanner.db.CoordinatePair
import com.arawn.scanner.export.DataLogBackupExporter
import com.arawn.scanner.export.EnrichedCsvExporter
import com.arawn.scanner.export.HtmlReportExporter
import com.arawn.scanner.ui.FrequencyCurveChart
import com.arawn.scanner.ui.OfflineMapPanel
import kotlinx.coroutines.launch

/**
 * ARAWN Phase 1 entry point.
 *
 * Owns the runtime-permission flow and the lifecycle of the bound
 * [WirelessScannerService], and renders the operator terminal that streams
 * fused [ScanPacket]s as they arrive.
 */
class MainActivity : ComponentActivity() {

    private var service: WirelessScannerService? by mutableStateOf(null)
    private var scanning by mutableStateOf(false)

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
        setContent {
            ArawnTheme {
                val lines = remember { mutableStateListOf<String>() }
                // Latest Wi-Fi snapshot, fed straight to the live spectrum chart.
                val latestWifi = remember { mutableStateListOf<WifiObservation>() }
                // GPS track of the active session, projected for the offline map.
                val mapCoords = remember { mutableStateListOf<CoordinatePair>() }
                var viewMode by remember { mutableStateOf(ViewMode.CONSOLE) }

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
                    val dao = ArawnDatabase.get(applicationContext).wirelessDao()
                    val sessionId = dao.getLatestSessionId()
                    val coords = sessionId?.let { dao.getSessionCoordinates(it) }.orEmpty()
                    mapCoords.clear()
                    mapCoords.addAll(coords)
                }

                ScannerScreen(
                    scanning = scanning,
                    lines = lines,
                    wifi = latestWifi,
                    coordinates = mapCoords,
                    viewMode = viewMode,
                    onSelectView = { viewMode = it },
                    onStart = { requestPermissionsThenStart() },
                    onStop = { stopTracking() },
                    onClear = { lines.clear() },
                    onExport = { exportLatestSession() },
                    onReport = { generateHtmlReport() },
                )
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
            val wigle = DataLogBackupExporter(applicationContext).exportLatestSession()
            val enriched = EnrichedCsvExporter(applicationContext).exportLatestSession()
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
            val result = HtmlReportExporter(applicationContext).exportLatestSession()
            Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
        }
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
    }
}

// ---------------------------------------------------------------------------
//  UI
// ---------------------------------------------------------------------------

/** Which viewport the main pane is showing. */
private enum class ViewMode { CONSOLE, SPECTRUM, MAP }

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
    viewMode: ViewMode,
    onSelectView: (ViewMode) -> Unit,
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
                Text(
                    text = if (scanning) "● LIVE" else "○ IDLE",
                    color = if (scanning) TerminalGreen else Color.Gray,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                )
            }

            Spacer(Modifier.height(8.dp))

            // Low-profile three-way viewport selector.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ViewTab("▤ CONSOLE", viewMode == ViewMode.CONSOLE, Modifier.weight(1f)) {
                    onSelectView(ViewMode.CONSOLE)
                }
                ViewTab("∿ SPECTRUM", viewMode == ViewMode.SPECTRUM, Modifier.weight(1f)) {
                    onSelectView(ViewMode.SPECTRUM)
                }
                ViewTab("⌖ MAP", viewMode == ViewMode.MAP, Modifier.weight(1f)) {
                    onSelectView(ViewMode.MAP)
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
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            items(lines) { line ->
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
                    FrequencyCurveChart(
                        observations = wifi,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }

                ViewMode.MAP -> {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
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
