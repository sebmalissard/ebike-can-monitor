package com.seb.ebikecanmonitor

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.seb.ebikecanmonitor.ble.BleState
import com.seb.ebikecanmonitor.ble.LogEntry
import com.seb.ebikecanmonitor.can.CanMessage
import com.seb.ebikecanmonitor.record.RecordingReader
import com.seb.ebikecanmonitor.record.Recordings
import com.seb.ebikecanmonitor.record.SessionRecorder
import com.seb.ebikecanmonitor.record.SmbExporter
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Content of a saved session file shown in the in-app viewer. */
data class ViewerState(
    val recording: Recordings.Recording,
    val lines: List<String>,
    val totalLines: Long,
    /** Incremented each time [lines] changes, so the UI can follow the end of the file. */
    val version: Long,
    /** True while the session writing this file is still running. */
    val live: Boolean,
)

/** Progress of the export of the saved sessions to the Samba server. */
sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val done: Int, val total: Int) : ExportState
    data class Success(val uploaded: Int, val skipped: Int) : ExportState
    data class Failure(val message: String) : ExportState
}

/**
 * Thin UI-facing layer. The BLE connection, the recording and the live log live in [Monitor]
 * (process scope), so they keep running when the activity is destroyed.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val VIEWER_POLL_MS = 500L
    }

    private val monitor = Monitor.get(app)

    init {
        // The app was opened: try to connect for a while (the process may already be alive)
        monitor.startRetryWindow()
    }

    /** File currently being recorded (null when no session is running). */
    val recordingInfo: StateFlow<SessionRecorder.Info?> = monitor.recorder.info
    val recordingError: StateFlow<String?> = monitor.recorder.error

    val connectionState: StateFlow<BleState> = monitor.connectionState
    val statusMessage: StateFlow<String> = monitor.statusMessage
    val entries: StateFlow<List<LogEntry>> = monitor.entries
    val lostFrames: StateFlow<Long> = monitor.lostFrames
    val busStatus: StateFlow<CanMessage.Status?> = monitor.busStatus
    val autoConnect: StateFlow<Boolean> = monitor.autoConnect

    fun connect() = monitor.connect()
    fun disconnect() = monitor.disconnect()
    fun clear() = monitor.clear()
    fun setAutoConnect(enabled: Boolean) = monitor.setAutoConnect(enabled)
    fun reportStatus(message: String) = monitor.reportStatus(message)
    fun beginPairing() = monitor.beginPairing()
    fun endPairing() = monitor.endPairing()
    fun onPermissionDenied() =
        monitor.reportStatus(getApplication<Application>().getString(R.string.status_permission_denied))

    // ------------------------------------------------------------ Recordings

    private val _recordings = MutableStateFlow<List<Recordings.Recording>>(emptyList())

    /** Saved session files, newest first. Call [refreshRecordings] to update the list. */
    val recordings: StateFlow<List<Recordings.Recording>> = _recordings.asStateFlow()

    fun refreshRecordings() {
        viewModelScope.launch(Dispatchers.IO) {
            _recordings.value = Recordings.list(getApplication<Application>())
        }
    }

    private val _viewer = MutableStateFlow<ViewerState?>(null)

    /** Saved file shown in the viewer (null when the viewer is closed). */
    val viewer: StateFlow<ViewerState?> = _viewer.asStateFlow()

    private var viewerJob: Job? = null

    private fun isLive(recording: Recordings.Recording) =
        monitor.recorder.info.value?.fileName == recording.name

    /**
     * Opens [recording] in the in-app viewer. As long as the session writing the file is running,
     * the viewer follows its end like the real-time log. Afterwards it stops polling.
     */
    fun openRecording(recording: Recordings.Recording) {
        viewerJob?.cancel()
        _viewer.value = ViewerState(recording, emptyList(), 0L, 0L, isLive(recording))
        viewerJob = viewModelScope.launch(Dispatchers.IO) {
            val reader = RecordingReader(getApplication<Application>(), recording.uri)
            var version = 0L
            var quietPolls = 0
            while (isActive) {
                val grew = reader.readNew()
                val live = isLive(recording)
                ensureActive()
                if (grew) {
                    version++
                    _viewer.value = ViewerState(recording, reader.snapshot(), reader.totalLines, version, live)
                } else {
                    _viewer.update { it?.copy(live = live) }
                }
                // Once the session is over, stop after two polls without new data
                quietPolls = if (live || grew) 0 else quietPolls + 1
                if (quietPolls >= 2) break
                delay(VIEWER_POLL_MS)
            }
        }
    }

    fun closeRecording() {
        viewerJob?.cancel()
        viewerJob = null
        _viewer.value = null
    }

    // ---------------------------------------------------------- SMB export

    private val smbPrefs = app.getSharedPreferences("smb_export", Context.MODE_PRIVATE)

    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)

    /** Progress of the export to the Samba server. */
    val exportState: StateFlow<ExportState> = _exportState.asStateFlow()

    private var exportJob: Job? = null

    /** Last user name used for the export (empty means guest). */
    fun savedSmbUsername(): String = smbPrefs.getString("username", "") ?: ""

    /** Last password used for the export. */
    fun savedSmbPassword(): String = smbPrefs.getString("password", "") ?: ""

    /**
     * Uploads every saved session file to the Samba server, except the one still being recorded
     * (it is incomplete). The credentials are remembered for the next export.
     */
    fun exportToSmb(username: String, password: String) {
        if (exportJob?.isActive == true) return
        smbPrefs.edit().putString("username", username).putString("password", password).apply()
        _exportState.value = ExportState.Running(0, 0)

        exportJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = getApplication<Application>()
                val recording = monitor.recorder.info.value?.fileName
                val files = Recordings.list(app).filter { it.name != recording }
                val result = SmbExporter.export(app, files, username, password) { done, total ->
                    _exportState.value = ExportState.Running(done, total)
                }
                _exportState.value = ExportState.Success(result.uploaded, result.skipped)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _exportState.value = ExportState.Failure(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Resets the export state, e.g. when the export dialog is opened or closed. */
    fun resetExport() {
        if (exportJob?.isActive == true) return
        _exportState.value = ExportState.Idle
    }
}
