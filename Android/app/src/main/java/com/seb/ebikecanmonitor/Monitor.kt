package com.seb.ebikecanmonitor

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.SystemClock
import com.seb.ebikecanmonitor.ble.BleClient
import com.seb.ebikecanmonitor.ble.BleEvent
import com.seb.ebikecanmonitor.ble.BleState
import com.seb.ebikecanmonitor.ble.LogEntry
import com.seb.ebikecanmonitor.can.CanMessage
import com.seb.ebikecanmonitor.companion.CompanionPairing
import com.seb.ebikecanmonitor.record.SessionRecorder
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Process-wide owner of the BLE client, the session recorder and the live log.
 *
 * It outlives the UI: the activity/ViewModel only observe it, while [MonitorService] keeps the
 * process alive during a connection and the companion service wakes it up when the ESP32 is nearby.
 */
class Monitor private constructor(context: Context) {

    companion object {
        private const val MAX_ENTRIES = 3000
        private const val UI_REFRESH_MS = 100L
        private const val NO_SEQ = -1L
        private const val AUTO_CONNECT_INTERVAL_MS = 3_000L
        private const val RETRY_WINDOW_MS = 60_000L
        private const val PAIRING_TIMEOUT_MS = 90_000L
        private const val PREFS_NAME = "settings"
        private const val KEY_AUTO_CONNECT = "auto_connect"

        @Volatile private var instance: Monitor? = null

        fun get(context: Context): Monitor = instance ?: synchronized(this) {
            instance ?: Monitor(context.applicationContext).also { instance = it }
        }
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val client = BleClient(appContext)
    val recorder = SessionRecorder(appContext)

    val connectionState: StateFlow<BleState> = client.state
    val statusMessage: StateFlow<String> = client.status

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    /** Last `seq` received (NO_SEQ after a (re)connection). */
    private val lastSeq = AtomicLong(NO_SEQ)

    /** Frames lost between the ESP32 and the app (`seq` gaps, e.g. saturated BLE buffer). */
    private val _lostFrames = MutableStateFlow(0L)
    val lostFrames: StateFlow<Long> = _lostFrames.asStateFlow()

    /** Last `#STATUS` received: frames lost by the ESP32 TWAI driver. */
    private val _busStatus = MutableStateFlow<CanMessage.Status?>(null)
    val busStatus: StateFlow<CanMessage.Status?> = _busStatus.asStateFlow()

    private val _autoConnect = MutableStateFlow(prefs.getBoolean(KEY_AUTO_CONNECT, false))

    /** When true, the app keeps reconnecting to the ESP32 whenever it is disconnected. */
    val autoConnect: StateFlow<Boolean> = _autoConnect.asStateFlow()

    // ------------------------------------------------------------------ API

    fun setAutoConnect(enabled: Boolean) {
        _autoConnect.value = enabled
        prefs.edit().putBoolean(KEY_AUTO_CONNECT, enabled).apply()
        if (enabled) startRetryWindow()
    }

    fun connect() {
        // No connection while the pairing dialog searches: a connected ESP32 would not be found
        if (isPairing()) return
        // The ESP32 seq keeps running while disconnected: do not count that as a loss
        lastSeq.set(NO_SEQ)
        client.connect()
    }

    /** A manual disconnect also turns auto-connect off, otherwise it would reconnect at once. */
    fun disconnect() {
        setAutoConnect(false)
        client.disconnect()
    }

    fun clear() {
        _entries.value = emptyList()
        _lostFrames.value = 0L
    }

    fun reportStatus(message: String) = client.reportStatus(message)

    /** Called by the companion service when the system sees the paired ESP32 in range. */
    fun onDeviceNearby() {
        scope.launch {
            startRetryWindow()
            tryAutoConnect()
        }
    }

    // ----------------------------------------------------------- Auto-connect

    /** True when a connection attempt can start without any user interaction. */
    private fun canConnectSilently(): Boolean {
        val bluetoothOn = appContext.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
        return bluetoothOn && hasBlePermissions(appContext)
    }

    private fun tryAutoConnect() {
        if (isPairing()) return
        if (_autoConnect.value && client.state.value == BleState.Disconnected && canConnectSilently()) {
            connect()
        }
    }

    /** Deadline (elapsedRealtime) until which failed or lost connections are retried. */
    @Volatile private var retryUntilMs = 0L

    /**
     * Opens a time-limited window during which the app keeps trying to connect. Outside of it
     * nothing scans: once the ESP32 is paired, Android wakes the app when it appears, so a
     * permanent scan loop would only drain the battery.
     */
    fun startRetryWindow() {
        retryUntilMs = SystemClock.elapsedRealtime() + RETRY_WINDOW_MS
    }

    private fun isRetryWindowOpen() = SystemClock.elapsedRealtime() < retryUntilMs

    // ---------------------------------------------------------------- Pairing

    /** Deadline (elapsedRealtime) until which auto-connect is paused for the pairing dialog. */
    @Volatile private var pairingUntilMs = 0L

    private fun isPairing() = SystemClock.elapsedRealtime() < pairingUntilMs

    /**
     * Pauses auto-connect while the system pairing dialog searches for the ESP32. A BLE peripheral
     * stops advertising once connected, so a live connection would hide it from that search.
     * The pause ends with [endPairing] or after [PAIRING_TIMEOUT_MS] at most.
     */
    fun beginPairing() {
        pairingUntilMs = SystemClock.elapsedRealtime() + PAIRING_TIMEOUT_MS
        if (client.state.value != BleState.Disconnected) client.disconnect()
    }

    fun endPairing() {
        pairingUntilMs = 0L
        // Pairing is over: let auto-connect take over right away
        startRetryWindow()
    }

    // ------------------------------------------------------------ Data path

    private fun track(entry: LogEntry) {
        when (val m = entry.message) {
            is CanMessage.Frame -> {
                val prev = lastSeq.getAndSet(m.seq)
                // m.seq <= prev: ESP32 reboot (the counter restarts at 0), not a loss
                if (prev != NO_SEQ && m.seq > prev + 1) {
                    _lostFrames.update { it + (m.seq - prev - 1) }
                }
            }
            is CanMessage.Status -> _busStatus.value = m
            is CanMessage.Unknown -> Unit
        }
    }

    /**
     * Processes [first] and every event already queued, strictly in order.
     * Session boundaries are handled in-band, so the recorder sees each line
     * inside the session it belongs to, including the last lines before a disconnect.
     */
    private fun drain(first: BleEvent?) {
        var batch = ArrayList<LogEntry>()
        var event = first
        while (event != null) {
            when (event) {
                is BleEvent.Line -> batch.add(event.entry)
                is BleEvent.SessionStart -> {
                    publish(batch)
                    batch = ArrayList()
                    recorder.start(event.timeMs)
                }
                BleEvent.SessionEnd -> {
                    publish(batch)
                    batch = ArrayList()
                    recorder.stop()
                }
            }
            event = client.events.tryReceive().getOrNull()
        }
        publish(batch)
    }

    /** Tracks losses, records the batch and appends it to the on-screen log. */
    private fun publish(batch: List<LogEntry>) {
        if (batch.isEmpty()) return
        batch.forEach(::track)
        recorder.write(batch)
        _entries.update { old ->
            val merged = old + batch
            if (merged.size > MAX_ENTRIES) merged.takeLast(MAX_ENTRIES) else merged
        }
    }

    init {
        // The CAN bus can produce hundreds of frames/s: group the received lines
        // and refresh the UI every 100 ms.
        scope.launch(Dispatchers.Default) {
            for (first in client.events) {
                drain(first)
                delay(UI_REFRESH_MS)
            }
        }

        // Auto-connect loop: while the retry window is open, retry whenever disconnected.
        // A failed scan ends in Disconnected, so the next tick tries again. Once the window
        // closes nothing scans any more: Android wakes the app when the paired ESP32 appears.
        scope.launch {
            while (isActive) {
                if (isRetryWindowOpen()) tryAutoConnect()
                delay(AUTO_CONNECT_INTERVAL_MS)
            }
        }

        // Keep the process alive while a connection is active (foreground service),
        // and retry for a while when an established link is lost.
        scope.launch {
            var previous = client.state.value
            client.state.collect { state ->
                if (state == BleState.Connecting || state == BleState.Connected) {
                    MonitorService.start(appContext)
                }
                if (previous == BleState.Connected && state == BleState.Disconnected) {
                    startRetryWindow()
                }
                previous = state
            }
        }

        // The process just started (app opened or woken up): try to connect for a while
        startRetryWindow()

        // Make sure the system keeps watching for the paired ESP32 (survives app updates/reboots).
        CompanionPairing.ensureObserving(appContext)
    }
}
