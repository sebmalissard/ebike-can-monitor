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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Records one CSV file per session in Downloads/eBikeCanMonitor.
 *
 * The file name is derived from the session start time, e.g. `ebike_can_2026-11-03_14-32-05.csv`.
 * Batches are queued by [write] and written on an IO thread, so the UI/parsing path never blocks
 * on disk access. Every batch is flushed, so a crash loses almost nothing.
 *
 * CSV columns: phone_time_ms,seq,esp_ts_us,id_hex,extended,rtr,dlc,data_hex
 * Lines starting with '#' are comments (bus status counters from the ESP32).
 */
class SessionRecorder(context: Context) {

    /** Info about the file currently being recorded. */
    data class Info(val fileName: String, val rows: Long)

    companion object {
        private const val CSV_HEADER = "phone_time_ms,seq,esp_ts_us,id_hex,extended,rtr,dlc,data_hex\n"
        private const val HEX_DIGITS = "0123456789ABCDEF"
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

    /** Starts a new session file named after [startMs]. No-op if a session is already running. */
    @Synchronized
    fun start(startMs: Long) {
        if (channel != null) return
        val ch = Channel<List<LogEntry>>(Channel.UNLIMITED)
        channel = ch
        _error.value = null
        scope.launch { record(startMs, ch) }
    }

    /** Queues a batch for writing. Ignored when no session is running. */
    fun write(batch: List<LogEntry>) {
        channel?.trySend(batch)
    }

    /** Ends the current session. Already queued batches are still written before the file is closed. */
    @Synchronized
    fun stop() {
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
        is CanMessage.Unknown -> 0
    }
}
