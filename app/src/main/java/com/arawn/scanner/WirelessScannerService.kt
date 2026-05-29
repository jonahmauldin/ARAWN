package com.arawn.scanner

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.arawn.scanner.db.ArawnDatabase
import com.arawn.scanner.db.SessionEntity
import com.arawn.scanner.db.WirelessDao
import com.arawn.scanner.oui.OuiLookupManager
import com.arawn.scanner.db.toBleEntities
import com.arawn.scanner.db.toLogEntry
import com.arawn.scanner.db.toWifiEntities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * ARAWN foreground service (Phase 1 sensing + Phase 2 persistence).
 *
 * Owns the three asynchronous sensor streams (GPS, Wi-Fi scan results, BLE
 * advertisements) and fuses them into [ScanPacket]s, one per location fix.
 *
 * Design notes:
 *  - The service is BOTH started (startForegroundService) and bound. Started
 *    keeps it alive across UI death; bound lets the UI collect [packets].
 *  - GPS fixes are the heartbeat. Wi-Fi and BLE results accumulate into
 *    thread-safe holders between fixes; each fix snapshots them.
 *  - Each fused packet fans out to TWO sinks: the live UI [packets] flow and a
 *    persistence [writeChannel] drained by a single IO coroutine that writes
 *    each scan window to SQLite atomically (one session = one tracking run).
 *  - All sensor APIs are wrapped in permission/availability guards so a denied
 *    permission degrades gracefully instead of crashing.
 */
class WirelessScannerService : Service() {

    // ---- Binder / public stream ---------------------------------------

    inner class LocalBinder : Binder() {
        val service: WirelessScannerService get() = this@WirelessScannerService
    }

    private val binder = LocalBinder()

    private val _packets = MutableSharedFlow<ScanPacket>(
        replay = 0,
        extraBufferCapacity = 64, // never suspend the sensor callbacks
    )
    /** Cold-ish hot stream of fused scan packets. */
    val packets: SharedFlow<ScanPacket> = _packets.asSharedFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    // ---- Local persistence pipeline -----------------------------------

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao: WirelessDao by lazy { ArawnDatabase.get(this).wirelessDao() }

    /**
     * Decouples the GPS callback (main thread) from disk writes. A capped,
     * drop-oldest buffer means a slow disk can never stall scanning or grow
     * memory without bound. A fresh channel is created per session.
     */
    private var writeChannel: Channel<ScanPacket>? = null

    // ---- System services ----------------------------------------------

    private var locationManager: LocationManager? = null
    private var wifiManager: WifiManager? = null
    private var bleScanner: BluetoothLeScanner? = null

    // ---- Thread-safe accumulators (written from binder/system threads) -

    @Volatile
    private var latestWifi: List<WifiObservation> = emptyList()

    /** MAC -> most recent observation, with the elapsed-time of last sighting. */
    private val bleSeen = ConcurrentHashMap<String, TimedBle>()

    private data class TimedBle(val obs: BleObservation, val seenElapsedMs: Long)

    // ---- Wi-Fi --------------------------------------------------------

    private val wifiReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            // Triggered by the OS when fresh results land (or throttling denies
            // a fresh scan — either way we read the freshest cached results).
            val mgr = wifiManager ?: return
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return
            val results = try {
                mgr.scanResults
            } catch (_: SecurityException) {
                return
            }
            latestWifi = results.map { r ->
                @Suppress("DEPRECATION")
                WifiObservation(
                    ssid = r.SSID ?: "",
                    bssid = r.BSSID ?: "",
                    rssiDbm = r.level,
                    frequencyMhz = r.frequency,
                    capabilities = r.capabilities ?: "",
                )
            }
        }
    }

    private val wifiScanLoop = object : Runnable {
        @SuppressLint("MissingPermission")
        override fun run() {
            val mgr = wifiManager
            if (mgr != null && _scanning.value) {
                try {
                    @Suppress("DEPRECATION")
                    mgr.startScan() // may be throttled by the OS; harmless if so
                } catch (_: SecurityException) {
                }
            }
            mainHandler.postDelayed(this, WIFI_SCAN_INTERVAL_MS)
        }
    }

    private val mainHandler = android.os.Handler(Looper.getMainLooper())

    // ---- BLE ----------------------------------------------------------

    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            recordBle(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::recordBle)
        }

        override fun onScanFailed(errorCode: Int) {
            // Non-fatal; surfaced only via logcat in Phase 1.
            android.util.Log.w(TAG, "BLE scan failed: code=$errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun recordBle(result: ScanResult) {
        val device = result.device ?: return
        val mac = device.address ?: return
        val name = result.scanRecord?.deviceName
        val tx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            result.txPower.takeIf { it != ScanResult.TX_POWER_NOT_PRESENT }
        } else null
        bleSeen[mac] = TimedBle(
            obs = BleObservation(mac, name, result.rssi, tx),
            seenElapsedMs = android.os.SystemClock.elapsedRealtime(),
        )
    }

    // ---- Location (the heartbeat) -------------------------------------

    private val locationListener = LocationListener { location -> emitPacket(location) }

    private fun emitPacket(location: Location) {
        val now = android.os.SystemClock.elapsedRealtime()

        // Snapshot Wi-Fi (immutable list reference) and prune stale BLE, stamping
        // each signal with its offline OUI vendor as we go. Lookups are O(1)
        // HashMap hits, so doing them on the GPS-fix thread is negligible — and
        // it keeps vendor resolution in ONE place, ahead of both the UI flow and
        // the SQLite writer channel.
        val wifiSnapshot = latestWifi.map { w ->
            w.copy(vendorName = OuiLookupManager.lookup(w.bssid))
        }
        val bleSnapshot = ArrayList<BleObservation>(bleSeen.size)
        val it = bleSeen.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value.seenElapsedMs > BLE_STALE_MS) {
                it.remove()
            } else {
                val obs = e.value.obs
                bleSnapshot.add(obs.copy(vendorName = OuiLookupManager.lookup(obs.macAddress)))
            }
        }

        val packet = ScanPacket(
            timestampMs = System.currentTimeMillis(),
            latitude = location.latitude,
            longitude = location.longitude,
            altitudeM = if (location.hasAltitude()) location.altitude else 0.0,
            accuracyM = if (location.hasAccuracy()) location.accuracy else -1f,
            speedMps = if (location.hasSpeed()) location.speed else 0f,
            wifi = wifiSnapshot,
            ble = bleSnapshot,
        )
        // Fan out: live to the UI flow, and to the persistence channel. Both
        // are non-blocking so the GPS callback returns immediately.
        _packets.tryEmit(packet)
        writeChannel?.trySend(packet)
    }

    // ---- Lifecycle ----------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService()
        wifiManager = applicationContext.getSystemService<WifiManager>()
        val btManager = getSystemService<BluetoothManager>()
        bleScanner = btManager?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        // Warm the offline OUI registry off-thread now, so the in-memory map is
        // ready well before the first GPS fix needs to stamp vendors.
        OuiLookupManager.warmUp(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTracking()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startTracking()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        stopTracking()
        super.onDestroy()
    }

    // ---- Start / stop sensing -----------------------------------------

    @SuppressLint("MissingPermission")
    private fun startTracking() {
        if (_scanning.value) return
        startAsForeground()
        startSessionWriter()

        // GPS — high-accuracy continuous fixes.
        if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            try {
                locationManager?.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    LOCATION_MIN_TIME_MS,
                    LOCATION_MIN_DIST_M,
                    locationListener,
                    Looper.getMainLooper(),
                )
            } catch (e: SecurityException) {
                android.util.Log.w(TAG, "Location updates denied", e)
            } catch (e: IllegalArgumentException) {
                android.util.Log.w(TAG, "GPS provider unavailable", e)
            }
        }

        // Wi-Fi — register receiver + kick the periodic scan loop.
        ContextCompat.registerReceiver(
            this,
            wifiReceiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        mainHandler.post(wifiScanLoop)

        // BLE — re-resolve the scanner in case the adapter was toggled on after
        // onCreate, then start a low-latency advertisement stream.
        if (bleScanner == null) {
            bleScanner = getSystemService<BluetoothManager>()
                ?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        }
        if (canScanBle()) {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            try {
                bleScanner?.startScan(null, settings, bleCallback)
            } catch (e: SecurityException) {
                android.util.Log.w(TAG, "BLE scan denied", e)
            }
        }

        _scanning.value = true
    }

    @SuppressLint("MissingPermission")
    private fun stopTracking() {
        if (!_scanning.value && locationManager == null) return

        try {
            locationManager?.removeUpdates(locationListener)
        } catch (_: SecurityException) {
        }

        mainHandler.removeCallbacks(wifiScanLoop)
        runCatching { unregisterReceiver(wifiReceiver) }

        if (canScanBle()) {
            try {
                bleScanner?.stopScan(bleCallback)
            } catch (_: SecurityException) {
            }
        }

        bleSeen.clear()
        latestWifi = emptyList()
        _scanning.value = false

        // Sensors are off, so no new packets will arrive — now close the channel
        // so the writer drains whatever is buffered and finalizes the session.
        stopSessionWriter()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    // ---- Persistence writer -------------------------------------------

    /**
     * Opens a session row and a fresh write channel, then drains scan windows
     * into SQLite sequentially on the IO scope. Sequential draining preserves
     * insertion order and avoids overlapping transactions.
     */
    private fun startSessionWriter() {
        val channel = Channel<ScanPacket>(
            capacity = WRITE_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        writeChannel = channel
        ioScope.launch {
            val sessionId = dao.insertSession(
                SessionEntity(startTime = System.currentTimeMillis())
            )
            var points = 0
            for (packet in channel) {
                try {
                    dao.insertScanWindow(
                        entry = packet.toLogEntry(sessionId),
                        wifi = packet.toWifiEntities(),
                        ble = packet.toBleEntities(),
                    )
                    points++
                } catch (e: Exception) {
                    // One bad window must not kill the whole session writer.
                    android.util.Log.w(TAG, "Scan-window persist failed", e)
                }
            }
            // Channel closed by stopSessionWriter(): stamp the session as complete.
            // totalDistanceM is left at 0.0 for Phase 2 (distance is a later phase).
            dao.finalizeSession(sessionId, System.currentTimeMillis(), points, 0.0)
            android.util.Log.i(TAG, "Session $sessionId finalized ($points windows)")
        }
    }

    private fun stopSessionWriter() {
        writeChannel?.close() // signals the drain-then-finalize path
        writeChannel = null
    }

    // ---- Foreground notification --------------------------------------

    private fun startAsForeground() {
        createChannel()

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ARAWN — RF survey active")
            .setContentText("Logging Wi-Fi / BLE / GPS in the foreground")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService<NotificationManager>() ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "RF Survey",
            NotificationManager.IMPORTANCE_LOW, // no sound, persistent
        ).apply { description = "Active wireless logging session" }
        nm.createNotificationChannel(channel)
    }

    // ---- Permission helpers -------------------------------------------

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) ==
            PackageManager.PERMISSION_GRANTED

    private fun canScanBle(): Boolean {
        if (bleScanner == null) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    companion object {
        // Public intent actions used by the UI to drive the service.
        const val ACTION_START = "com.arawn.scanner.action.START"
        const val ACTION_STOP = "com.arawn.scanner.action.STOP"

        private const val TAG = "ArawnService"
        private const val CHANNEL_ID = "arawn_rf_survey"
        private const val NOTIFICATION_ID = 0x4157 // 'AW'

        private const val LOCATION_MIN_TIME_MS = 1_500L
        private const val LOCATION_MIN_DIST_M = 0f
        private const val WIFI_SCAN_INTERVAL_MS = 3_000L
        private const val BLE_STALE_MS = 30_000L
        private const val WRITE_BUFFER = 256 // scan windows buffered before DROP_OLDEST
    }
}
