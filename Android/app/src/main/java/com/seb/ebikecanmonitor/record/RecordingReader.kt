package com.seb.ebikecanmonitor.record

import android.content.Context
import android.net.Uri
import java.io.InputStream

/**
 * Incremental reader for a session file.
 *
 * Each [readNew] call reads only the bytes appended since the previous call, so the same instance
 * can follow a file that is still being written. Only the last [MAX_LINES] lines are kept in memory.
 * Not thread-safe: use it from a single coroutine.
 */
class RecordingReader(private val context: Context, private val uri: Uri) {

    companion object {
        const val MAX_LINES = 100_000
        private const val BUFFER_SIZE = 64 * 1024
    }

    private var offset = 0L
    private val partialLine = StringBuilder()
    private val lines = ArrayDeque<String>()

    /** Total number of complete lines read so far (may exceed the number of lines kept). */
    var totalLines = 0L
        private set

    /** Reads the bytes appended since the last call. Returns true if any new byte was read. */
    fun readNew(): Boolean {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return false
            input.use { stream ->
                if (!skipBytes(stream, offset)) return false
                val buffer = ByteArray(BUFFER_SIZE)
                var grew = false
                while (true) {
                    val n = stream.read(buffer)
                    if (n <= 0) break
                    offset += n
                    appendText(String(buffer, 0, n, Charsets.UTF_8))
                    grew = true
                }
                grew
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Copy of the kept lines, oldest first. */
    fun snapshot(): List<String> = lines.toList()

    private fun skipBytes(stream: InputStream, count: Long): Boolean {
        var remaining = count
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped <= 0) return false
            remaining -= skipped
        }
        return true
    }

    private fun appendText(text: String) {
        partialLine.append(text)
        var start = 0
        while (true) {
            val end = partialLine.indexOf("\n", start)
            if (end < 0) break
            val line = partialLine.substring(start, end).trimEnd('\r')
            start = end + 1
            if (line.isEmpty()) continue
            lines.addLast(line)
            totalLines++
            if (lines.size > MAX_LINES) lines.removeFirst()
        }
        // Keep only the incomplete last line for the next call
        partialLine.delete(0, start)
    }
}
