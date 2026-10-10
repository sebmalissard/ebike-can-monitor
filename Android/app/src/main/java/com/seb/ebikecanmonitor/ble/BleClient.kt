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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

enum class BleState { Disconnected, Scanning, Connecting, Connected }

/** Une ligne complète reçue de l'ESP32, horodatée à la réception. */
data class LogEntry(val id: Long, val timestampMs: Long, val text: String)

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
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(BleState.Disconnected)
    val state: StateFlow<BleState> = _state.asStateFlow()

    private val _status = MutableStateFlow("Déconnecté")
    val status: StateFlow<String> = _status.asStateFlow()

    /** Flux des lignes reçues (consommé par le ViewModel). */
    val lines = Channel<LogEntry>(Channel.UNLIMITED)

    private val idGenerator = AtomicLong()
    private val lineBuffer = StringBuilder()

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
            _status.value = "Bluetooth désactivé"
            return
        }
        val leScanner = adapter.bluetoothLeScanner
        if (leScanner == null) {
            _status.value = "Scanner BLE indisponible"
            return
        }

        userRequestedDisconnect = false
        synchronized(lineBuffer) { lineBuffer.clear() }

        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(NUS_SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        scanner = leScanner
        scanning = true
        _state.value = BleState.Scanning
        _status.value = "Recherche de $DEVICE_NAME…"
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
                _status.value = "Déconnecté"
            }
            else -> {
                val g = gatt
                if (g == null) {
                    forceCleanup("Déconnecté")
                } else {
                    _status.value = "Déconnexion…"
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
        lines.close()
    }

    // ------------------------------------------------------------- Timeouts

    private val scanTimeout = Runnable {
        if (scanning) {
            stopScan()
            _state.value = BleState.Disconnected
            _status.value = "$DEVICE_NAME introuvable"
        }
    }

    private val connectTimeout = Runnable {
        if (_state.value == BleState.Connecting) {
            forceCleanup("Délai de connexion dépassé")
        }
    }

    private val forceClose = Runnable {
        if (gatt != null) forceCleanup("Déconnecté")
    }

    private fun forceCleanup(message: String) {
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
            val device: BluetoothDevice = result.device
            _state.value = BleState.Connecting
            _status.value = "Connexion à ${device.name ?: device.address}…"
            gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            mainHandler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
        }

        override fun onScanFailed(errorCode: Int) {
            stopScan()
            _state.value = BleState.Disconnected
            _status.value = "Échec du scan (code $errorCode)"
        }
    }

    // ----------------------------------------------------------------- GATT

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                _status.value = "Négociation MTU…"
                if (!g.requestMtu(REQUESTED_MTU)) g.discoverServices()
            } else {
                // Déconnexion (volontaire, perte de lien, ou échec de connexion)
                mainHandler.removeCallbacks(connectTimeout)
                mainHandler.removeCallbacks(forceClose)
                g.close()
                if (gatt === g) gatt = null
                _state.value = BleState.Disconnected
                _status.value = when {
                    userRequestedDisconnect -> "Déconnecté"
                    status == BluetoothGatt.GATT_SUCCESS -> "Déconnecté par l'ESP32"
                    else -> "Connexion perdue (code $status)"
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            _status.value = "Découverte des services…"
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val tx = g.getService(NUS_SERVICE)?.getCharacteristic(NUS_TX)
            val cccd = tx?.getDescriptor(CCCD)
            if (status != BluetoothGatt.GATT_SUCCESS || tx == null || cccd == null) {
                _status.value = "Service UART introuvable sur l'appareil"
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
                _state.value = BleState.Connected
                _status.value = "Connecté à $DEVICE_NAME"
            } else {
                _status.value = "Activation des notifications échouée ($status)"
                userRequestedDisconnect = true
                g.disconnect()
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == NUS_TX) handleChunk(value)
        }
    }

    // ------------------------------------------------- Réassemblage des lignes

    private fun handleChunk(value: ByteArray) {
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
                lines.trySend(LogEntry(idGenerator.incrementAndGet(), now, line))
            }
        }
    }
}
