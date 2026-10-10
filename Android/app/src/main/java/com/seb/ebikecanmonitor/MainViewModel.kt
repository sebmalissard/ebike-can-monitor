package com.seb.ebikecanmonitor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.seb.ebikecanmonitor.ble.BleClient
import com.seb.ebikecanmonitor.ble.BleState
import com.seb.ebikecanmonitor.ble.LogEntry
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
    }

    private val client = BleClient(app)

    val connectionState: StateFlow<BleState> = client.state
    val statusMessage: StateFlow<String> = client.status

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

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
                _entries.update { old ->
                    val merged = old + batch
                    if (merged.size > MAX_ENTRIES) merged.takeLast(MAX_ENTRIES) else merged
                }
                delay(UI_REFRESH_MS)
            }
        }
    }

    fun connect() = client.connect()
    fun disconnect() = client.disconnect()
    fun clear() { _entries.value = emptyList() }
    fun onPermissionDenied() = client.reportStatus("Permission Bluetooth refusée")

    override fun onCleared() {
        client.release()
    }
}
