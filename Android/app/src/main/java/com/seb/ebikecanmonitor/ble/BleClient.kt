package com.seb.ebikecanmonitor.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.annotation.StringRes
import com.seb.ebikecanmonitor.R
import com.seb.ebikecanmonitor.can.CanMessage
import com.seb.ebikecanmonitor.can.CanProtocol
import com.seb.ebikecanmonitor.record.JsonLines
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class BleState { Disconnected, Scanning, Connecting, Connected }

/** Une ligne complète reçue de l'ESP32, horodatée à la réception, avec son décodage. */
data class LogEntry(val id: Long, val timestampMs: Long, val text: String, val message: CanMessage)

/**
 * Ordered events produced by [BleClient]. Session boundaries travel in the same queue as the
 * lines, so a consumer always sees every line inside the session it belongs to.
 */
sealed interface BleEvent {
    data class Line(val entry: LogEntry) : BleEvent
    data class SessionStart(val timeMs: Long) : BleEvent
    data object SessionEnd : BleEvent

    /** Diagnostic record (`link` or `event` JSON line, see JsonLines), possibly outside any session. */
    data class Note(val json: String) : BleEvent
}

/**
 * Client BLE pour le firmware eBikeCanSniffer (Nordic UART Service).
 * L'ESP32 envoie des lignes texte terminées par '\n', découpées en paquets
 * selon le MTU : on réassemble donc les lignes avant de les horodater.
 */
@SuppressLint("MissingPermission") // Les permissions sont demandées par l'UI avant connect()
class BleClient(context: Context) {

    companion object {
        val NUS_SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // ESP -> app (notify)
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val DEVICE_NAME = "eBikeCanSniffer"
        const val REQUESTED_MTU = 247
        const val SCAN_TIMEOUT_MS = 15_000L
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val DISCONNECT_TIMEOUT_MS = 3_000L
        const val LINK_TICK_MS = 1_000L
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(BleState.Disconnected)
    val state: StateFlow<BleState> = _state.asStateFlow()

    private val _status = MutableStateFlow(appContext.getString(R.string.status_disconnected))
    val status: StateFlow<String> = _status.asStateFlow()

    private fun str(@StringRes id: Int, vararg args: Any): String = appContext.getString(id, *args)

    /** Ordered stream of received lines and session boundaries (consumed by the ViewModel). */
    val events = Channel<BleEvent>(Channel.UNLIMITED)

    /** True between [BleEvent.SessionStart] and [BleEvent.SessionEnd]. */
    private val sessionActive = AtomicBoolean(false)

    private val idGenerator = AtomicLong()
    private val lineBuffer = StringBuilder()

    private val diag = LinkDiagnostics(appContext)

    private var scanStartMs = 0L

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var userRequestedDisconnect = false
    private var scanner: BluetoothLeScanner? = null
    @Volatile private var scanning = false

    // ---------------------------------------------------------------- API

    fun reportStatus(message: String) {
        _status.value = message
    }

    fun connect() {
        if (_state.value != BleState.Disconnected) return

        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            _status.value = str(R.string.status_bluetooth_off)
            return
        }
        val leScanner = adapter.bluetoothLeScanner
        if (leScanner == null) {
            _status.value = str(R.string.status_scanner_unavailable)
            return
        }

        userRequestedDisconnect = false
        synchronized(lineBuffer) { lineBuffer.clear() }

        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(NUS_SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        scanner = leScanner
        scanning = true
        _state.value = BleState.Scanning
        _status.value = str(R.string.status_scanning, DEVICE_NAME)
        scanStartMs = System.currentTimeMillis()
        note("scan_start")
        leScanner.startScan(listOf(filter), settings, scanCallback)
        mainHandler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
    }

    fun disconnect() {
        userRequestedDisconnect = true
        when (_state.value) {
            BleState.Disconnected -> return
            BleState.Scanning -> {
                stopScan()
                _state.value = BleState.Disconnected
                _status.value = str(R.string.status_disconnected)
            }
            else -> {
                val g = gatt
                if (g == null) {
                    forceCleanup(str(R.string.status_disconnected))
                } else {
                    _status.value = str(R.string.status_disconnecting)
                    g.disconnect()
                    mainHandler.postDelayed(forceClose, DISCONNECT_TIMEOUT_MS)
                }
            }
        }
    }

    fun release() {
        stopScan()
        mainHandler.removeCallbacksAndMessages(null)
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        _state.value = BleState.Disconnected
        endSession()
        events.close()
    }

    // ------------------------------------------------------------- Timeouts

    private val scanTimeout = Runnable {
        if (scanning) {
            stopScan()
            _state.value = BleState.Disconnected
            _status.value = str(R.string.status_not_found, DEVICE_NAME)
            note("scan_timeout", "scan_duration_ms" to System.currentTimeMillis() - scanStartMs)
        }
    }

    private val connectTimeout = Runnable {
        if (_state.value == BleState.Connecting) {
            note("connect_timeout")
            forceCleanup(str(R.string.status_connect_timeout))
        }
    }

    private val forceClose = Runnable {
        if (gatt != null) forceCleanup(str(R.string.status_disconnected))
    }

    private fun forceCleanup(message: String) {
        closeGatt(message)
        endSession()
    }

    private fun closeGatt(message: String) {
        mainHandler.removeCallbacks(connectTimeout)
        mainHandler.removeCallbacks(forceClose)
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        _state.value = BleState.Disconnected
        _status.value = message
    }

    // ----------------------------------------------------------------- Scan

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        mainHandler.removeCallbacks(scanTimeout)
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: IllegalStateException) {
            // Bluetooth coupé pendant le scan : rien à faire
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            stopScan()
            note(
                "scan_result",
                "rssi_dbm" to result.rssi,
                "scan_duration_ms" to System.currentTimeMillis() - scanStartMs,
            )
            val device: BluetoothDevice = result.device
            _state.value = BleState.Connecting
            _status.value = str(R.string.status_connecting, device.name ?: device.address)
            gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            mainHandler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
        }

        override fun onScanFailed(errorCode: Int) {
            stopScan()
            _state.value = BleState.Disconnected
            _status.value = str(R.string.status_scan_failed, errorCode)
            note("scan_failed", "error_code" to errorCode)
        }
    }

    // ----------------------------------------------------------------- GATT

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                diag.onNewConnection()
                note("gatt_connected")
                _status.value = str(R.string.status_negotiating_mtu)
                if (!g.requestMtu(REQUESTED_MTU)) g.discoverServices()
            } else {
                // Déconnexion (volontaire, perte de lien, ou échec de connexion)
                mainHandler.removeCallbacks(connectTimeout)
                mainHandler.removeCallbacks(forceClose)
                note(
                    "disconnect",
                    "status_code" to status,
                    "status_name" to LinkDiagnostics.gattStatusName(status),
                    "new_state" to newState,
                    "user_requested" to userRequestedDisconnect,
                    "in_session" to sessionActive.get(),
                    *diag.summaryFields(),
                )
                g.close()
                endSession()
                if (gatt === g) gatt = null
                _state.value = BleState.Disconnected
                _status.value = when {
                    userRequestedDisconnect -> str(R.string.status_disconnected)
                    status == BluetoothGatt.GATT_SUCCESS -> str(R.string.status_disconnected_by_esp32)
                    else -> str(R.string.status_connection_lost, status)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            diag.onMtu(mtu)
            note("mtu", "mtu" to mtu, "status_code" to status)
            _status.value = str(R.string.status_discovering_services)
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val tx = g.getService(NUS_SERVICE)?.getCharacteristic(NUS_TX)
            val cccd = tx?.getDescriptor(CCCD)
            if (status != BluetoothGatt.GATT_SUCCESS || tx == null || cccd == null) {
                _status.value = str(R.string.status_uart_not_found)
                userRequestedDisconnect = true
                g.disconnect()
                return
            }
            g.setCharacteristicNotification(tx, true)
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != CCCD) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                mainHandler.removeCallbacks(connectTimeout)
                startSession()
                g.readPhy()
                _state.value = BleState.Connected
                _status.value = str(R.string.status_connected, DEVICE_NAME)
            } else {
                _status.value = str(R.string.status_notifications_failed, status)
                userRequestedDisconnect = true
                g.disconnect()
            }
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) diag.onRssi(rssi)
        }

        override fun onPhyRead(g: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            diag.onPhy(txPhy, rxPhy)
            note("phy", "tx_phy" to LinkDiagnostics.phyName(txPhy), "rx_phy" to LinkDiagnostics.phyName(rxPhy))
        }

        override fun onPhyUpdate(g: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            diag.onPhy(txPhy, rxPhy)
            note("phy", "tx_phy" to LinkDiagnostics.phyName(txPhy), "rx_phy" to LinkDiagnostics.phyName(rxPhy))
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == NUS_TX) handleChunk(value)
        }
    }

    // ---------------------------------------------------------------- Sessions

    // ------------------------------------------------------------ Diagnostics

    /** Builds an `event` record and queues it; the consumer decides which file it belongs to. */
    private fun note(name: String, vararg fields: Pair<String, Any?>) {
        events.trySend(BleEvent.Note(JsonLines.event(System.currentTimeMillis(), name, *fields)))
    }

    /** Once per second while a session is active: asks for a new RSSI and writes a `link` record. */
    private val linkTick = object : Runnable {
        override fun run() {
            if (!sessionActive.get()) return
            try {
                gatt?.readRemoteRssi()
            } catch (_: RuntimeException) {
                // The link is going down: the disconnection event will say why
            }
            events.trySend(BleEvent.Note(diag.linkRecord(System.currentTimeMillis())))
            mainHandler.postDelayed(this, LINK_TICK_MS)
        }
    }

    /** Queues [BleEvent.SessionStart] before any line of the new connection. */
    private fun startSession() {
        if (sessionActive.compareAndSet(false, true)) {
            events.trySend(BleEvent.SessionStart(System.currentTimeMillis()))
            diag.resetCounters()
            mainHandler.postDelayed(linkTick, LINK_TICK_MS)
        }
    }

    /** Queues [BleEvent.SessionEnd] after every line already received on this connection. */
    private fun endSession() {
        if (sessionActive.compareAndSet(true, false)) {
            mainHandler.removeCallbacks(linkTick)
            events.trySend(BleEvent.SessionEnd)
        }
    }

    // ------------------------------------------------- Réassemblage des lignes

    private fun handleChunk(value: ByteArray) {
        diag.onChunk(value.size)
        val completed = ArrayList<String>()
        synchronized(lineBuffer) {
            lineBuffer.append(value.toString(Charsets.UTF_8))
            var idx = lineBuffer.indexOf("\n")
            while (idx >= 0) {
                completed += lineBuffer.substring(0, idx).trimEnd('\r')
                lineBuffer.delete(0, idx + 1)
                idx = lineBuffer.indexOf("\n")
            }
            if (lineBuffer.length > 4096) lineBuffer.clear() // garde-fou
        }
        if (completed.isEmpty()) return

        val now = System.currentTimeMillis()
        for (line in completed) {
            if (line.isNotBlank()) {
                val message = CanProtocol.parse(line)
                if (message is CanMessage.Frame) diag.onFrame(message.seq)
                events.trySend(BleEvent.Line(LogEntry(idGenerator.incrementAndGet(), now, line, message)))
            }
        }
    }
}
