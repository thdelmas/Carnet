package com.carnet.app

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import org.json.JSONObject

/**
 * Scans every sidecar JSON in `Movies/Carnet/` and returns the chronological series
 * of recorded snapshots for a given series id. Used by the intro slides to show the
 * "where I started" anchor and the trajectory line graph.
 *
 * Pulled synchronously at record-start because the intro deck needs the data ready
 * before the first encoded frame. Typical series sizes (tens of takes, ~1 KB sidecar
 * each) read in well under the recording-start latency budget; if a series ever grows
 * pathologically large we'll move this to a coroutine + in-memory cache.
 */
object SeriesHistory {

    /** One past take's metric snapshot, sorted oldest-first by caller. */
    data class TakeSnapshot(
        val takenAtMillis: Long,
        val values: Map<String, Double?>,
    )

    fun loadForSeries(context: Context, seriesId: String): List<TakeSnapshot> {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.Files.FileColumns._ID)
        // RELATIVE_PATH is stored with a trailing slash; MIME filter narrows to sidecars.
        val selection = "${MediaStore.Files.FileColumns.RELATIVE_PATH} = ? AND " +
            "${MediaStore.Files.FileColumns.MIME_TYPE} = ?"
        val args = arrayOf("Movies/Carnet/", "application/json")

        val out = mutableListOf<TakeSnapshot>()
        try {
            context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                while (cursor.moveToNext()) {
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol))
                    val snapshot = parseSidecar(context, uri, seriesId) ?: continue
                    out += snapshot
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "history scan failed", t)
        }
        out.sortBy { it.takenAtMillis }
        return out
    }

    private fun parseSidecar(context: Context, uri: android.net.Uri, seriesId: String): TakeSnapshot? {
        return try {
            val text = context.contentResolver.openInputStream(uri)?.use {
                it.bufferedReader().readText()
            } ?: return null
            val json = JSONObject(text)
            if (json.optString("series_id") != seriesId) return null
            val takenAt = json.optLong("record_start_ms", -1L)
                .takeIf { it > 0 } ?: return null
            val biosSnapshot = json.optJSONObject("bios_snapshot") ?: return TakeSnapshot(takenAt, emptyMap())
            val valuesJson = biosSnapshot.optJSONObject("values") ?: return TakeSnapshot(takenAt, emptyMap())
            val values = mutableMapOf<String, Double?>()
            val keys = valuesJson.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val entry = valuesJson.optJSONObject(k) ?: continue
                values[k] = if (entry.isNull("value")) null else entry.optDouble("value").takeUnless { it.isNaN() }
            }
            TakeSnapshot(takenAt, values)
        } catch (t: Throwable) {
            // A corrupt sidecar shouldn't poison the whole history scan; skip and move on.
            Log.w(TAG, "skip sidecar $uri: ${t.message}")
            null
        }
    }

    private const val TAG = "Carnet/History"
}
