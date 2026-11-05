package com.seb.ebikecanmonitor

import android.bluetooth.BluetoothManager
import android.content.Context
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
    }

    fun connect() {
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
        scope.launch { tryAutoConnect() }
    }

    // ----------------------------------------------------------- Auto-connect

    /** True when a connection attempt can start without any user interaction. */
    private fun canConnectSilently(): Boolean {
        val bluetoothOn = appContext.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
        return bluetoothOn && hasBlePermissions(appContext)
    }

    private fun tryAutoConnect() {
        if (_autoConnect.value && client.state.value == BleState.Disconnected && canConnectSilently()) {
            connect()
        }
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

        // Auto-connect loop: whenever enabled and disconnected, start a new attempt.
        // A failed scan or a lost link ends in Disconnected, so the next tick retries.
        scope.launch {
            while (isActive) {
                tryAutoConnect()
                delay(AUTO_CONNECT_INTERVAL_MS)
            }
        }

        // Keep the process alive while a connection is active (foreground service).
        scope.launch {
            client.state.collect { state ->
                if (state == BleState.Connecting || state == BleState.Connected) {
                    MonitorService.start(appContext)
                }
            }
        }

        // Make sure the system keeps watching for the paired ESP32 (survives app updates/reboots).
        CompanionPairing.ensureObserving(appContext)
    }
}
