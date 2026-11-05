package com.seb.ebikecanmonitor.record

import android.content.ClipData
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore

/** Location of the session files and helpers to list and share them. */
object Recordings {

    const val FOLDER = "eBikeCanMonitor"

    /** Folder relative to the shared storage root, e.g. `Download/eBikeCanMonitor`. */
    val RELATIVE_PATH = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER"

    /** One saved session file. */
    data class Recording(val uri: Uri, val name: String, val sizeBytes: Long, val modifiedMs: Long)

    /** Lists the session files of the app folder, newest first. Blocking: call from an IO thread. */
    fun list(context: Context): List<Recording> {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        // MediaStore stores RELATIVE_PATH with a trailing slash
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf("$RELATIVE_PATH/")
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

        val result = ArrayList<Recording>()
        context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            while (c.moveToNext()) {
                result += Recording(
                    uri = ContentUris.withAppendedId(collection, c.getLong(idCol)),
                    name = c.getString(nameCol),
                    sizeBytes = c.getLong(sizeCol),
                    modifiedMs = c.getLong(dateCol) * 1000L, // DATE_MODIFIED is in seconds
                )
            }
        }
        return result
    }

    /** Opens the Android share sheet for [recording]. */
    fun share(context: Context, recording: Recording) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, recording.uri)
            clipData = ClipData.newRawUri(recording.name, recording.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, recording.name))
    }
}
