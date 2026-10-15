package com.seb.ebikecanmonitor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.seb.ebikecanmonitor.ble.BleClient
import com.seb.ebikecanmonitor.ble.BleState
import com.seb.ebikecanmonitor.ble.LogEntry
import com.seb.ebikecanmonitor.can.CanMessage
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val MAX_ENTRIES = 3000
        private const val UI_REFRESH_MS = 100L
        private const val NO_SEQ = -1L
    }

    private val client = BleClient(app)

    val connectionState: StateFlow<BleState> = client.state
    val statusMessage: StateFlow<String> = client.status

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    /** Dernier `seq` reçu (NO_SEQ après une (re)connexion). */
    private val lastSeq = AtomicLong(NO_SEQ)

    /** Trames perdues entre l'ESP32 et l'app (sauts de `seq`, ex. buffer BLE saturé). */
    private val _lostFrames = MutableStateFlow(0L)
    val lostFrames: StateFlow<Long> = _lostFrames.asStateFlow()

    /** Dernier `#STATUS` reçu : trames perdues côté driver TWAI de l'ESP32. */
    private val _busStatus = MutableStateFlow<CanMessage.Status?>(null)
    val busStatus: StateFlow<CanMessage.Status?> = _busStatus.asStateFlow()

    private fun track(entry: LogEntry) {
        when (val m = entry.message) {
            is CanMessage.Frame -> {
                val prev = lastSeq.getAndSet(m.seq)
                // m.seq <= prev : reboot de l'ESP32 (le compteur repart de 0), pas une perte
                if (prev != NO_SEQ && m.seq > prev + 1) {
                    _lostFrames.update { it + (m.seq - prev - 1) }
                }
            }
            is CanMessage.Status -> _busStatus.value = m
            is CanMessage.Unknown -> Unit
        }
    }

    init {
        // Le bus CAN peut produire des centaines de trames/s : on regroupe
        // les lignes reçues et on met l'UI à jour toutes les 100 ms.
        viewModelScope.launch(Dispatchers.Default) {
            for (first in client.lines) {
                val batch = ArrayList<LogEntry>()
                batch.add(first)
                while (true) {
                    val next = client.lines.tryReceive().getOrNull() ?: break
                    batch.add(next)
                }
                batch.forEach(::track)
                _entries.update { old ->
                    val merged = old + batch
                    if (merged.size > MAX_ENTRIES) merged.takeLast(MAX_ENTRIES) else merged
                }
                delay(UI_REFRESH_MS)
            }
        }
    }

    fun connect() {
        // Le seq de l'ESP32 continue pendant la déconnexion : ne pas compter ça comme perte
        lastSeq.set(NO_SEQ)
        client.connect()
    }
    fun disconnect() = client.disconnect()
    fun clear() {
        _entries.value = emptyList()
        _lostFrames.value = 0L
    }
    fun onPermissionDenied() =
        client.reportStatus(getApplication<Application>().getString(R.string.status_permission_denied))

    override fun onCleared() {
        client.release()
    }
}
