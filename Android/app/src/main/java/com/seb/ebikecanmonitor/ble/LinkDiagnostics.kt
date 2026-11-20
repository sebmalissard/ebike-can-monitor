package com.seb.ebikecanmonitor.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import com.seb.ebikecanmonitor.record.JsonLines

/**
 * Collects the BLE link quality indicators written to the recording as `link` records
 * (once per second while connected, see [JsonLines]). Fields, covering the last interval
 * for the counters, null when unknown:
 *  - rssi_dbm / rssi_age_ms : last RSSI read from the ESP32 and its age
 *  - notifications / bytes  : BLE notifications received and their total size
 *  - frames / lost_frames   : CAN frames received and `sequence` gaps seen between them
 *  - max_gap_ms             : longest time between two notifications (includes the current silence)
 *  - silence_ms             : time since the last notification when the record was written
 *  - phy / mtu              : PHY and MTU negotiated with the ESP32
 *  - audio_a2dp_connected / audio_hfp_connected : Bluetooth audio profiles connected on the phone
 *  - gatt_device_count      : GATT devices connected to the phone (includes the ESP32)
 *  - power_save / doze / screen_on : Android power state
 *
 * Counters are updated from the Bluetooth binder thread and read from the main thread.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is requested by the UI before connecting
class LinkDiagnostics(context: Context) {

    companion object {
        private const val ENV_REFRESH_MS = 5_000L
        private const val NS_PER_MS = 1_000_000L

        /** Readable name of an Android GATT status / disconnect reason. */
        fun gattStatusName(status: Int): String = when (status) {
            0 -> "SUCCESS"
            8 -> "CONN_TIMEOUT"
            19 -> "REMOTE_TERMINATED"
            22 -> "LOCAL_TERMINATED"
            34 -> "LMP_TIMEOUT"
            62 -> "FAILED_TO_ESTABLISH"
            133 -> "GATT_ERROR"
            257 -> "GATT_FAILURE"
            else -> "UNKNOWN"
        }

        /** Readable name of a PHY constant of [android.bluetooth.BluetoothDevice]. */
        fun phyName(phy: Int?): String = when (phy) {
            null -> "?"
            1 -> "1M"
            2 -> "2M"
            3 -> "coded"
            else -> phy.toString()
        }
    }

    private data class Environment(
        val a2dp: Boolean?,
        val hfp: Boolean?,
        val gattDevices: Int?,
        val powerSave: Boolean?,
        val doze: Boolean?,
        val screenOn: Boolean?,
    )

    private val appContext = context.applicationContext
    private val lock = Any()

    // Link parameters, null until Android reports them
    private var rssi: Int? = null
    private var rssiAtNs = 0L
    private var mtu: Int? = null
    private var txPhy: Int? = null
    private var rxPhy: Int? = null

    // Counters of the current interval
    private var notifications = 0
    private var bytes = 0L
    private var frames = 0
    private var seqLost = 0L
    private var gapMaxNs = 0L
    private var lastChunkNs = 0L // 0 = nothing received yet
    private var lastSeq = -1L

    // Phone environment, refreshed every few seconds (main thread only)
    private var env: Environment? = null
    private var envAtMs = 0L

    // ------------------------------------------------------------ Updates

    /** A new GATT connection starts: forget the parameters of the previous one. */
    fun onNewConnection() {
        synchronized(lock) {
            rssi = null
            rssiAtNs = 0L
            mtu = null
            txPhy = null
            rxPhy = null
            lastChunkNs = 0L
        }
    }

    /** A recording session starts: counters restart from zero. */
    fun resetCounters() {
        synchronized(lock) {
            notifications = 0
            bytes = 0L
            frames = 0
            seqLost = 0L
            gapMaxNs = 0L
            lastChunkNs = 0L
            lastSeq = -1L
        }
    }

    fun onChunk(size: Int) {
        val now = SystemClock.elapsedRealtimeNanos()
        synchronized(lock) {
            if (lastChunkNs != 0L) gapMaxNs = maxOf(gapMaxNs, now - lastChunkNs)
            lastChunkNs = now
            notifications++
            bytes += size
        }
    }

    fun onFrame(seq: Long) {
        synchronized(lock) {
            // seq <= lastSeq means an ESP32 reboot (counter restarts), not a loss
            if (lastSeq >= 0 && seq > lastSeq + 1) seqLost += seq - lastSeq - 1
            lastSeq = seq
            frames++
        }
    }

    fun onRssi(value: Int) {
        synchronized(lock) {
            rssi = value
            rssiAtNs = SystemClock.elapsedRealtimeNanos()
        }
    }

    fun onMtu(value: Int) {
        synchronized(lock) { mtu = value }
    }

    fun onPhy(tx: Int, rx: Int) {
        synchronized(lock) {
            txPhy = tx
            rxPhy = rx
        }
    }

    // ------------------------------------------------------------ Output

    /** One `link` record; also restarts the interval counters. */
    fun linkRecord(nowMs: Long): String {
        val environment = environment()
        return synchronized(lock) {
            val nowNs = SystemClock.elapsedRealtimeNanos()
            val silenceNs = if (lastChunkNs == 0L) null else nowNs - lastChunkNs
            val gapNs = maxOf(gapMaxNs, silenceNs ?: 0L)
            val rssiAgeMs = if (rssi == null) null else (nowNs - rssiAtNs) / NS_PER_MS
            val silenceMs = silenceNs?.let { it / NS_PER_MS }
            val phy = if (txPhy == null && rxPhy == null) null else "${phyName(txPhy)}/${phyName(rxPhy)}"
            val record = JsonLines.record(
                "link",
                "phone_time_ms" to nowMs,
                "rssi_dbm" to rssi,
                "rssi_age_ms" to rssiAgeMs,
                "notifications" to notifications,
                "bytes" to bytes,
                "frames" to frames,
                "lost_frames" to seqLost,
                "max_gap_ms" to gapNs / NS_PER_MS,
                "silence_ms" to silenceMs,
                "phy" to phy,
                "mtu" to mtu,
                "audio_a2dp_connected" to environment.a2dp,
                "audio_hfp_connected" to environment.hfp,
                "gatt_device_count" to environment.gattDevices,
                "power_save" to environment.powerSave,
                "doze" to environment.doze,
                "screen_on" to environment.screenOn,
            )
            notifications = 0
            bytes = 0L
            frames = 0
            seqLost = 0L
            gapMaxNs = 0L
            record
        }
    }

    /** Last known link state, added to the disconnection event. */
    fun summaryFields(): Array<Pair<String, Any?>> = synchronized(lock) {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val rssiAgeMs = if (rssi == null) null else (nowNs - rssiAtNs) / NS_PER_MS
        val silenceMs = if (lastChunkNs == 0L) null else (nowNs - lastChunkNs) / NS_PER_MS
        arrayOf<Pair<String, Any?>>(
            "last_rssi_dbm" to rssi,
            "rssi_age_ms" to rssiAgeMs,
            "silence_ms" to silenceMs,
        )
    }

    // ------------------------------------------------------------ Helpers

    private fun environment(): Environment {
        val nowMs = SystemClock.elapsedRealtime()
        env?.let { if (nowMs - envAtMs < ENV_REFRESH_MS) return it }
        val fresh = readEnvironment()
        env = fresh
        envAtMs = nowMs
        return fresh
    }

    private fun readEnvironment(): Environment {
        val manager = appContext.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        fun connected(profile: Int): Boolean? = try {
            adapter?.getProfileConnectionState(profile)?.let { it == BluetoothProfile.STATE_CONNECTED }
        } catch (_: RuntimeException) {
            null
        }
        val gattDevices = try {
            manager?.getConnectedDevices(BluetoothProfile.GATT)?.size
        } catch (_: RuntimeException) {
            null
        }
        val power = appContext.getSystemService(PowerManager::class.java)
        return Environment(
            a2dp = connected(BluetoothProfile.A2DP),
            hfp = connected(BluetoothProfile.HEADSET),
            gattDevices = gattDevices,
            powerSave = power?.isPowerSaveMode,
            doze = power?.isDeviceIdleMode,
            screenOn = power?.isInteractive,
        )
    }

}
