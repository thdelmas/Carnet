package com.carnet.app

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/**
 * A single past recording surfaced in the history view. Parsed from the
 * V##_LABEL_YYYY-MM-DD_UID.mp4 filename pattern that [MainActivity.startRecording] writes,
 * plus duration / size from MediaStore. The label slot can contain underscores (an
 * experiment label is allowed to be 'MY_LABEL'), so we anchor on the V## prefix and the
 * trailing date+UID pair.
 */
data class Recording(
    val id: Long,
    val displayName: String,
    val sessionNumber: Int?,
    val sessionLabel: String,
    val date: String,
    val uid: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedSec: Long,
) {
    val contentUri: Uri get() = ContentUris.withAppendedId(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id,
    )

    companion object {
        private val DATE_REGEX = Regex("""^\d{4}-\d{2}-\d{2}$""")
        private val UID_REGEX = Regex("""^[A-F0-9]{8}$""")
        private val VERSION_REGEX = Regex("""^V(\d+)$""")

        /** Parse a `V##_LABEL_YYYY-MM-DD_UID.mp4` filename; returns null on malformed names. */
        fun parse(
            id: Long,
            displayName: String,
            durationMs: Long,
            sizeBytes: Long,
            dateAddedSec: Long,
        ): Recording? {
            val base = displayName.removeSuffix(".mp4")
            val parts = base.split('_')
            if (parts.size < 4) return null
            val sessionNumber = VERSION_REGEX.matchEntire(parts[0])
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
            val uid = parts.last().takeIf { UID_REGEX.matches(it) } ?: return null
            val date = parts[parts.size - 2].takeIf { DATE_REGEX.matches(it) } ?: return null
            val label = parts.subList(1, parts.size - 2).joinToString("_")
            return Recording(
                id = id,
                displayName = displayName,
                sessionNumber = sessionNumber,
                sessionLabel = label,
                date = date,
                uid = uid,
                durationMs = durationMs,
                sizeBytes = sizeBytes,
                dateAddedSec = dateAddedSec,
            )
        }

        /** Query all takes Carnet has written under Movies/Carnet/, newest first. */
        fun loadAll(context: Context, relativePath: String): List<Recording> {
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_ADDED,
            )
            val selection = "${MediaStore.Video.Media.RELATIVE_PATH} = ?"
            val args = arrayOf(relativePath)
            val sort = "${MediaStore.Video.Media.DATE_ADDED} DESC"
            val out = mutableListOf<Recording>()
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection, selection, args, sort,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val addedIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val rec = parse(
                        id = cursor.getLong(idIdx),
                        displayName = cursor.getString(nameIdx),
                        durationMs = cursor.getLong(durIdx),
                        sizeBytes = cursor.getLong(sizeIdx),
                        dateAddedSec = cursor.getLong(addedIdx),
                    ) ?: continue
                    out += rec
                }
            }
            return out
        }
    }
}
