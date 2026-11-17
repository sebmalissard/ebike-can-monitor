package com.seb.ebikecanmonitor.record

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.seb.ebikecanmonitor.R
import com.seb.ebikecanmonitor.ble.LogEntry
import com.seb.ebikecanmonitor.can.CanMessage
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Records one CSV file per session in Downloads/eBikeCanMonitor.
 *
 * The file name is derived from the session start time, e.g. `ebike_can_2026-11-03_14-32-05.csv`.
 * Batches are queued by [write] and written on an IO thread, so the UI/parsing path never blocks
 * on disk access. Every batch is flushed, so a crash loses almost nothing.
 *
 * CSV columns: phone_time_ms,seq,esp_ts_us,id_hex,extended,rtr,dlc,data_hex
 * Lines starting with '#' are comments:
 *  - `# status,<phone_ms>,missed=N,overrun=N` bus status counters from the ESP32
 *  - `# info,...`  phone and app description (once per file)
 *  - `# event,<phone_ms>,<name>,key=value...` BLE events (scan, connection, disconnection reason...)
 *  - `# link,<phone_ms>,key=value...` BLE link quality, once per second (see LinkDiagnostics)
 */
class SessionRecorder(context: Context) {

    /** Info about the file currently being recorded. */
    data class Info(val fileName: String, val rows: Long)

    companion object {
        private const val CSV_HEADER = "phone_time_ms,seq,esp_ts_us,id_hex,extended,rtr,dlc,data_hex\n"
        private const val HEX_DIGITS = "0123456789ABCDEF"

        /** After a disconnection the file stays open this long: a reconnection continues in the same file. */
        private const val RESUME_WINDOW_MS = 30_000L
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _info = MutableStateFlow<Info?>(null)
    /** Non-null while a session file is open. */
    val info: StateFlow<Info?> = _info.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    /** Last recording error, cleared when a new session starts. */
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Queue of the current session; null when no session is running. */
    @Volatile private var channel: Channel<List<LogEntry>>? = null

    /** Pending delayed close of the file after a disconnection (null when none is scheduled). */
    private var closeJob: Job? = null

    /**
     * Starts a new session file named after [startMs].
     * If the previous file is still open (short disconnection, see [stop]), keeps writing in it.
     *
     * @return true when the previous file was resumed, false when a new file was created.
     */
    @Synchronized
    fun start(startMs: Long): Boolean {
        closeJob?.cancel()
        closeJob = null
        if (channel != null) return true
        val ch = Channel<List<LogEntry>>(Channel.UNLIMITED)
        channel = ch
        _error.value = null
        scope.launch { record(startMs, ch) }
        return false
    }

    /** Queues a batch for writing. Ignored when no session is running. */
    fun write(batch: List<LogEntry>) {
        channel?.trySend(batch)
    }

    /** Queues a comment line (must start with "# ") in order with the batches. Ignored when no session is running. */
    fun writeNote(text: String) {
        val entry = LogEntry(0L, System.currentTimeMillis(), text, CanMessage.Unknown(text))
        channel?.trySend(listOf(entry))
    }

    /**
     * Ends the current session. Already queued batches are still written before the file is closed.
     *
     * Unless [immediate] is true, the file is kept open for [RESUME_WINDOW_MS] so that a reconnection
     * (link lost then recovered) continues in the same file instead of creating a new one.
     */
    @Synchronized
    fun stop(immediate: Boolean = false) {
        if (channel == null) return
        closeJob?.cancel()
        closeJob = null
        if (immediate) {
            closeNow()
            return
        }
        closeJob = scope.launch {
            delay(RESUME_WINDOW_MS)
            synchronized(this@SessionRecorder) {
                // Ignore a close that was cancelled by start() while waiting for the lock
                if (closeJob === coroutineContext[Job]) closeNow()
            }
        }
    }

    @Synchronized
    private fun closeNow() {
        closeJob = null
        channel?.close()
        channel = null
        _info.value = null
    }

    private suspend fun record(startMs: Long, ch: Channel<List<LogEntry>>) {
        var writer: BufferedWriter? = null
        try {
            val resolver = appContext.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName(startMs))
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Recordings.RELATIVE_PATH)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Cannot create the file")
            // MediaStore may rename the file on collision, so read back the real name
            val name = displayName(uri) ?: fileName(startMs)
            val stream = resolver.openOutputStream(uri, "w")
                ?: throw IOException("Cannot open the file")

            writer = BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8))
            writer.write(CSV_HEADER)
            writer.flush()

            var rows = 0L
            publish(ch, Info(name, rows))
            for (batch in ch) {
                for (entry in batch) rows += writeEntry(writer, entry)
                writer.flush()
                publish(ch, Info(name, rows))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(ch, e)
        } finally {
            try {
                writer?.close()
            } catch (_: IOException) {
                // Nothing more to do
            }
        }
    }

    /** Publishes [info] only if [ch] is still the current session. */
    private fun publish(ch: Channel<List<LogEntry>>, info: Info) {
        synchronized(this) {
            if (channel === ch) _info.value = info
        }
    }

    private fun fail(ch: Channel<List<LogEntry>>, e: Exception) {
        synchronized(this) {
            if (channel === ch) {
                channel = null
                _info.value = null
            }
            _error.value = appContext.getString(
                R.string.recording_failed,
                e.message ?: e.javaClass.simpleName,
            )
        }
        ch.close()
    }

    private fun fileName(startMs: Long): String {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneId.systemDefault())
        return "ebike_can_${formatter.format(Instant.ofEpochMilli(startMs))}.csv"
    }

    private fun displayName(uri: Uri): String? =
        appContext.contentResolver
            .query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }

    /** Writes one entry and returns the number of CSV data rows written (0 or 1). */
    private fun writeEntry(w: BufferedWriter, entry: LogEntry): Int = when (val m = entry.message) {
        is CanMessage.Frame -> {
            w.write(
                "${entry.timestampMs},${m.seq},${m.tsUs},${m.id.toString(16).uppercase()}," +
                    "${if (m.extended) 1 else 0},${if (m.rtr) 1 else 0},${m.dlc},"
            )
            for (b in m.data) {
                val v = b.toInt() and 0xFF
                w.write(HEX_DIGITS[v shr 4].code)
                w.write(HEX_DIGITS[v and 0x0F].code)
            }
            w.write("\n")
            1
        }
        is CanMessage.Status -> {
            w.write("# status,${entry.timestampMs},missed=${m.missed},overrun=${m.overrun}\n")
            0
        }
        is CanMessage.Unknown -> {
            // Diagnostic comment lines ("# link", "# event", "# info") are queued as Unknown entries
            if (m.raw.startsWith("# ")) w.write(m.raw + "\n")
            0
        }
    }
}
