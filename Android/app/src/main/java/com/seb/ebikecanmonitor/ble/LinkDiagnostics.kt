package com.seb.ebikecanmonitor.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock

/**
 * Collects BLE link quality indicators that are written to the CSV recording as comment lines:
 *
 *  - `# info,<phone_ms>,...` once per session (phone and app description)
 *  - `# link,<phone_ms>,...` once per second while connected
 *
 * Fields of a `# link` line (counters cover the last interval only, "?" = unknown):
 *  - rssi / rssi_age_ms : last RSSI read from the ESP32 (dBm) and its age
 *  - notif / bytes      : BLE notifications received and their total size
 *  - frames / seq_lost  : CAN frames received and `seq` gaps seen between them
 *  - gap_max_ms         : longest time between two notifications (includes the current silence)
 *  - silence_ms         : time since the last notification when the line was written
 *  - phy / mtu          : PHY and MTU negotiated with the ESP32
 *  - a2dp / hfp         : Bluetooth audio profiles connected on the phone (headphones...)
 *  - gatt_devices       : GATT devices connected to the phone (includes the ESP32)
 *  - power_save / doze / screen : Android power state
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

    /** One `# link` comment line; also restarts the interval counters. */
    fun linkLine(nowMs: Long): String {
        val environment = environment()
        return synchronized(lock) {
            val nowNs = SystemClock.elapsedRealtimeNanos()
            val silenceNs = if (lastChunkNs == 0L) null else nowNs - lastChunkNs
            val gapNs = maxOf(gapMaxNs, silenceNs ?: 0L)
            val line = buildString {
                append("# link,").append(nowMs)
                append(",rssi=").append(text(rssi))
                append(",rssi_age_ms=").append(text(if (rssi == null) null else (nowNs - rssiAtNs) / NS_PER_MS))
                append(",notif=").append(notifications)
                append(",bytes=").append(bytes)
                append(",frames=").append(frames)
                append(",seq_lost=").append(seqLost)
                append(",gap_max_ms=").append(gapNs / NS_PER_MS)
                append(",silence_ms=").append(text(silenceNs?.let { it / NS_PER_MS }))
                append(",phy=").append(phyName(txPhy)).append('/').append(phyName(rxPhy))
                append(",mtu=").append(text(mtu))
                append(",a2dp=").append(flag(environment.a2dp))
                append(",hfp=").append(flag(environment.hfp))
                append(",gatt_devices=").append(text(environment.gattDevices))
                append(",power_save=").append(flag(environment.powerSave))
                append(",doze=").append(flag(environment.doze))
                append(",screen=").append(flag(environment.screenOn))
            }
            notifications = 0
            bytes = 0L
            frames = 0
            seqLost = 0L
            gapMaxNs = 0L
            line
        }
    }

    /** One `# info` comment line describing the phone and the app. */
    fun infoLine(nowMs: Long): String =
        "# info,$nowMs,phone=${clean("${Build.MANUFACTURER} ${Build.MODEL}")}," +
            "android=${Build.VERSION.RELEASE},sdk=${Build.VERSION.SDK_INT},app=${clean(appVersion())}"

    /** Last known link state, appended to the disconnection event. */
    fun summaryFields(): Array<String> = synchronized(lock) {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val rssiAgeMs = if (rssi == null) null else (nowNs - rssiAtNs) / NS_PER_MS
        val silenceMs = if (lastChunkNs == 0L) null else (nowNs - lastChunkNs) / NS_PER_MS
        arrayOf(
            "last_rssi=${text(rssi)}",
            "rssi_age_ms=${text(rssiAgeMs)}",
            "silence_ms=${text(silenceMs)}",
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

    private fun appVersion(): String = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    private fun clean(value: String) = value.replace(',', '_').replace(' ', '_')

    private fun text(value: Any?): String = value?.toString() ?: "?"

    private fun flag(value: Boolean?): String = when (value) {
        true -> "1"
        false -> "0"
        null -> "?"
    }

    private fun phyName(phy: Int?): String = when (phy) {
        null -> "?"
        1 -> "1M"
        2 -> "2M"
        3 -> "coded"
        else -> phy.toString()
    }
}
