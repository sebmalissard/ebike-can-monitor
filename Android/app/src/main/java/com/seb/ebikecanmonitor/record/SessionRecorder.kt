package com.seb.ebikecanmonitor.record

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Records one JSON Lines file per session in Downloads/eBikeCanMonitor.
 *
 * The file name is derived from the session start time, e.g. `ebike_can_2026-11-03_14-32-05.jsonl`.
 * The records (`meta`, `frame`, `status`, `link`, `event`, `end`) are described in [JsonLines].
 *
 * Batches and records are queued by [write] and [writeRecord] and written on an IO thread, so the
 * UI/parsing path never blocks on disk access. Everything queued is flushed after each write, so a
 * crash loses almost nothing; a file closed normally ends with an `end` record.
 */
class SessionRecorder(context: Context) {

    /** Info about the file currently being recorded; [rows] is the number of `frame` records. */
    data class Info(val fileName: String, val rows: Long)

    /** One element of the write queue. */
    private sealed interface Item {
        class Entries(val batch: List<LogEntry>) : Item
        class Line(val json: String) : Item
    }

    companion object {
        private const val HEX_DIGITS = "0123456789ABCDEF"
        private const val MIME_TYPE = "application/x-ndjson"

        /** After a disconnection the file stays open this long: a reconnection continues in the same file. */
        private const val RESUME_WINDOW_MS = 30_000L

        /** How often the close timer checks the wall clock. */
        private const val CLOSE_POLL_MS = 1_000L
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _info = MutableStateFlow<Info?>(null)
    /** Non-null while a session file is open. */
    val info: StateFlow<Info?> = _info.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    /** Last recording error, cleared when a new session starts. */
    val error: StateFlow<String?> = _error.asStateFlow()

    /** True while a session file is open, including the short wait after a disconnection. */
    val isOpen: Boolean get() = channel != null

    /** Queue of the current session; null when no session is running. */
    @Volatile private var channel: Channel<Item>? = null

    /** Pending delayed close of the file after a disconnection (null when none is scheduled). */
    private var closeJob: Job? = null

    /** Wall-clock time of the last [stop], used to decide whether a file may still be resumed. */
    @Volatile private var stoppedAtMs = 0L

    /**
     * Starts a new session file named after [startMs].
     * If the previous file is still open (short disconnection, see [stop]), keeps writing in it.
     *
     * @return true when the previous file was resumed, false when a new file was created.
     */
    @Synchronized
    fun start(startMs: Long): Boolean {
        val pendingClose = closeJob
        pendingClose?.cancel()
        closeJob = null
        if (channel != null) {
            // The close timer can be late (nothing runs while the phone sleeps): the wall clock decides
            val expired = pendingClose != null && System.currentTimeMillis() - stoppedAtMs > RESUME_WINDOW_MS
            if (!expired) return true
            closeNow()
        }
        val ch = Channel<Item>(Channel.UNLIMITED)
        channel = ch
        _error.value = null
        scope.launch { record(startMs, ch) }
        return false
    }

    /** Queues a batch for writing. Ignored when no session is running. */
    fun write(batch: List<LogEntry>) {
        channel?.trySend(Item.Entries(batch))
    }

    /**
     * Queues one already serialized record (see [JsonLines]) in order with the batches.
     * Ignored when no session is running.
     */
    fun writeRecord(json: String) {
        channel?.trySend(Item.Line(json))
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
        stoppedAtMs = System.currentTimeMillis()
        closeJob = scope.launch {
            // Poll the wall clock instead of one long delay(): a delay does not elapse while the phone sleeps
            while (System.currentTimeMillis() - stoppedAtMs < RESUME_WINDOW_MS) delay(CLOSE_POLL_MS)
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

    private suspend fun record(startMs: Long, ch: Channel<Item>) {
        var writer: BufferedWriter? = null
        try {
            val resolver = appContext.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName(startMs))
                put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Recordings.RELATIVE_PATH)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Cannot create the file")
            // MediaStore may rename the file on collision, so read back the real name
            val name = displayName(uri) ?: fileName(startMs)
            val stream = resolver.openOutputStream(uri, "w")
                ?: throw IOException("Cannot open the file")

            writer = BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8))
            writeLine(writer, metaRecord(startMs))
            writer.flush()

            var rows = 0L
            publish(ch, Info(name, rows))
            for (item in ch) {
                when (item) {
                    is Item.Entries -> for (entry in item.batch) rows += writeEntry(writer, entry)
                    is Item.Line -> writeLine(writer, item.json)
                }
                writer.flush()
                publish(ch, Info(name, rows))
            }
            // The queue was closed normally: mark the file as complete
            writeLine(writer, JsonLines.record("end", "phone_time_ms" to System.currentTimeMillis(), "frame_count" to rows))
            writer.flush()
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
    private fun publish(ch: Channel<Item>, info: Info) {
        synchronized(this) {
            if (channel === ch) _info.value = info
        }
    }

    private fun fail(ch: Channel<Item>, e: Exception) {
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
        return "ebike_can_${formatter.format(Instant.ofEpochMilli(startMs))}.jsonl"
    }

    private fun displayName(uri: Uri): String? =
        appContext.contentResolver
            .query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }

    /** First line of every file: layout version, start time, phone and app description. */
    private fun metaRecord(startMs: Long): String = JsonLines.record(
        "meta",
        "version" to JsonLines.VERSION,
        "start_time_ms" to startMs,
        "phone" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "android" to Build.VERSION.RELEASE,
        "android_sdk" to Build.VERSION.SDK_INT,
        "app_version" to appVersion(),
    )

    private fun appVersion(): String? = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
    } catch (_: Exception) {
        null
    }

    private fun writeLine(w: BufferedWriter, json: String) {
        w.write(json)
        w.write("\n")
    }

    /** Writes one entry and returns the number of `frame` records written (0 or 1). */
    private fun writeEntry(w: BufferedWriter, entry: LogEntry): Int = when (val m = entry.message) {
        is CanMessage.Frame -> {
            writeLine(
                w,
                JsonLines.record(
                    "frame",
                    "phone_time_ms" to entry.timestampMs,
                    "sequence" to m.seq,
                    "esp_time_us" to m.tsUs,
                    "can_id" to m.id.toString(16).uppercase(),
                    "extended" to m.extended,
                    "remote_request" to m.rtr,
                    "data_length" to m.dlc,
                    "data" to hex(m.data),
                ),
            )
            1
        }
        is CanMessage.Status -> {
            writeLine(
                w,
                JsonLines.record(
                    "status",
                    "phone_time_ms" to entry.timestampMs,
                    "missed_frames" to m.missed,
                    "overrun_frames" to m.overrun,
                ),
            )
            0
        }
        // Lines the app could not decode are only kept in the on-screen log
        is CanMessage.Unknown -> 0
    }

    private fun hex(data: ByteArray): String {
        val sb = StringBuilder(data.size * 2)
        for (b in data) {
            val v = b.toInt() and 0xFF
            sb.append(HEX_DIGITS[v shr 4]).append(HEX_DIGITS[v and 0x0F])
        }
        return sb.toString()
    }
}
