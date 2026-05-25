package com.carnet.app

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Writes the per-recording sidecar JSON to the same MediaStore folder as the video. The file
 * shares the recording's base name (no extension) and lands as `<base>.json`. On failure the
 * recording itself is unaffected — the sidecar is best-effort metadata, never a blocker.
 */
class SidecarWriter(private val context: Context) {

    fun write(displayBaseName: String, relativePath: String, metadata: SidecarMetadata): Boolean {
        val json = toJson(metadata).toString(2)
        val values = ContentValues().apply {
            put(MediaStore.Files.FileColumns.DISPLAY_NAME, "$displayBaseName.json")
            put(MediaStore.Files.FileColumns.MIME_TYPE, "application/json")
            put(MediaStore.Files.FileColumns.RELATIVE_PATH, relativePath)
        }
        return try {
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Files.getContentUri("external")
            }
            val uri = context.contentResolver.insert(collection, values) ?: return false
            context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                ?: return false
            true
        } catch (t: Throwable) {
            Log.w(TAG, "sidecar write failed for $displayBaseName", t)
            false
        }
    }

    private fun toJson(m: SidecarMetadata): JSONObject = JSONObject().apply {
        put("schema_version", m.schemaVersion)
        put("filename", m.filename)
        put("subject", m.subject)
        put("session_label", m.sessionLabel)
        put("experiment_label", m.experimentLabel)
        put("session_number", m.sessionNumber)
        put("uid", m.uid)
        put("date_local", m.dateLocal)
        put("record_start_ms", m.recordStartMillis)
        put("record_end_ms", m.recordEndMillis)
        put("record_start_iso", isoUtc(m.recordStartMillis))
        put("record_end_iso", isoUtc(m.recordEndMillis))
        put("duration_ms", m.recordEndMillis - m.recordStartMillis)
        put("series_id", m.seriesId ?: JSONObject.NULL)
        put("series_name", m.seriesName ?: JSONObject.NULL)
        put("bios_snapshot", m.biosSnapshot?.let(::biosJson) ?: JSONObject.NULL)
    }

    private fun biosJson(b: BiosSnapshot): JSONObject = JSONObject().apply {
        // Generic metric-keyed map so the sidecar shape doesn't drift every time a new
        // Bios metric becomes interesting. Each entry: { value, unit, aggregation }
        // resolved against the MetricSpec catalog so downstream consumers don't have to.
        val values = JSONObject()
        for ((key, raw) in b.values) {
            val spec = MetricSpec.byKey(key)
            values.put(key, JSONObject().apply {
                put("value", raw ?: JSONObject.NULL)
                put("unit", spec?.unit ?: JSONObject.NULL)
                put("aggregation", spec?.aggregation?.name ?: JSONObject.NULL)
            })
        }
        put("values", values)
        put("captured_at_iso", isoUtc(b.capturedAtMillis))
    }

    private fun isoUtc(millis: Long): String = ISO_FORMAT.format(Date(millis))

    companion object {
        private const val TAG = "Carnet/Sidecar"
        const val SCHEMA_VERSION = 2
        private val ISO_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
