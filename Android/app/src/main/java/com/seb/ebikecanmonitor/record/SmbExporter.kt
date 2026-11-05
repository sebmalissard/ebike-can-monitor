package com.seb.ebikecanmonitor.record

import android.content.Context
import java.io.IOException
import java.util.Properties
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile

/**
 * Uploads the saved session files to a Samba share (SMB2/3).
 *
 * Destination: `\\192.168.1.4\home-share\ebike-monitor`. Files that already exist on the server
 * with the same size are skipped, so the export can be run again at any time.
 */
object SmbExporter {

    const val HOST = "192.168.1.4"
    const val SHARE = "home-share"
    const val FOLDER = "ebike-monitor"

    /** Human readable destination, as shown on Windows. */
    const val DISPLAY_PATH = "\\\\$HOST\\$SHARE\\$FOLDER"

    private const val DIR_URL = "smb://$HOST/$SHARE/$FOLDER/"
    private const val COPY_BUFFER_SIZE = 64 * 1024

    /** Outcome of an export. */
    data class Result(val uploaded: Int, val skipped: Int)

    /**
     * Copies [recordings] to the server. Blocking: call from an IO thread.
     *
     * @param username SMB user name; blank means guest access.
     * @param onProgress called after each file with (files processed, total files).
     * @throws IOException if the server cannot be reached, the credentials are refused or a copy fails.
     */
    fun export(
        context: Context,
        recordings: List<Recordings.Recording>,
        username: String,
        password: String,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Result {
        val cifs = createContext(username, password)
        var uploaded = 0
        var skipped = 0

        SmbFile(DIR_URL, cifs).use { dir ->
            if (!dir.exists()) dir.mkdirs()

            recordings.forEachIndexed { index, recording ->
                SmbFile(DIR_URL + recording.name, cifs).use { remote ->
                    if (remote.exists() && remote.length() == recording.sizeBytes) {
                        skipped++
                    } else {
                        copy(context, recording, remote)
                        uploaded++
                    }
                }
                onProgress(index + 1, recordings.size)
            }
        }
        return Result(uploaded, skipped)
    }

    private fun copy(context: Context, recording: Recordings.Recording, remote: SmbFile) {
        val input = context.contentResolver.openInputStream(recording.uri)
            ?: throw IOException("Cannot open ${recording.name}")
        input.use { source ->
            remote.openOutputStream().use { target ->
                source.copyTo(target, COPY_BUFFER_SIZE)
            }
        }
    }

    private fun createContext(username: String, password: String): CIFSContext {
        val props = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.connTimeout", "5000")
            setProperty("jcifs.smb.client.responseTimeout", "15000")
        }
        val base = BaseContext(PropertyConfiguration(props))
        // Blank user name means guest access: authenticate as "guest" with an empty password
        val user = username.trim().ifEmpty { "guest" }
        val pass = if (username.isBlank()) "" else password
        return base.withCredentials(NtlmPasswordAuthenticator(null, user, pass))
    }
}
