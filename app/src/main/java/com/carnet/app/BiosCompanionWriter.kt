package com.carnet.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.util.Log

/**
 * Announces completed recording sessions back to the Bios companion app via the
 * `com.bios.app.companion` provider — the second half of the Bios round-trip (read vitals
 * at record-start, write a session-completed event at record-stop). Best-effort: failures
 * here never bubble up to the user since the sidecar JSON is the local source of truth.
 */
class BiosCompanionWriter(private val context: Context) {

    fun postRecordingCompleted(metadata: SidecarMetadata) {
        try {
            val values = ContentValues().apply {
                put(COL_EVENT_TYPE, EVENT_RECORDING_COMPLETED)
                put(COL_SUBJECT, metadata.subject)
                put(COL_SESSION_LABEL, metadata.sessionLabel)
                put(COL_EXPERIMENT_LABEL, metadata.experimentLabel)
                put(COL_SESSION_NUMBER, metadata.sessionNumber)
                put(COL_UID, metadata.uid)
                put(COL_FILENAME, metadata.filename)
                put(COL_DATE_LOCAL, metadata.dateLocal)
                put(COL_STARTED_AT_MS, metadata.recordStartMillis)
                put(COL_ENDED_AT_MS, metadata.recordEndMillis)
                put(COL_DURATION_MS, metadata.recordEndMillis - metadata.recordStartMillis)
            }
            val uri = context.contentResolver.insert(EVENTS_URI, values)
            if (uri == null) Log.w(TAG, "Bios companion insert returned null URI")
        } catch (t: Throwable) {
            Log.w(TAG, "Bios companion write failed: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "Carnet/BiosCompanion"
        private const val AUTHORITY = "com.bios.app.companion"
        val EVENTS_URI: Uri = Uri.parse("content://$AUTHORITY/events")
        const val EVENT_RECORDING_COMPLETED = "recording_session_completed"
        private const val COL_EVENT_TYPE = "event_type"
        private const val COL_SUBJECT = "subject"
        private const val COL_SESSION_LABEL = "session_label"
        private const val COL_EXPERIMENT_LABEL = "experiment_label"
        private const val COL_SESSION_NUMBER = "session_number"
        private const val COL_UID = "uid"
        private const val COL_FILENAME = "filename"
        private const val COL_DATE_LOCAL = "date_local"
        private const val COL_STARTED_AT_MS = "started_at_ms"
        private const val COL_ENDED_AT_MS = "ended_at_ms"
        private const val COL_DURATION_MS = "duration_ms"
    }
}
