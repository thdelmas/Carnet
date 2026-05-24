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
 *
 * Tracks [lastPushOutcome] so the Settings screen can render the pending-approval banner
 * required by Bios design system §6.b when the per-app companion permission hasn't been
 * granted on the Bios side yet.
 */
class BiosCompanionWriter(private val context: Context) {

    enum class LastPushOutcome {
        NEVER_PUSHED,
        OK,
        /** Bios installed, write attempted, provider declined — usually because the
         *  per-app permission isn't granted in Bios → Settings → Companion Apps. */
        PENDING_APPROVAL,
        /** Generic failure (network, exception, unknown). */
        FAILED,
    }

    @Volatile
    var lastPushOutcome: LastPushOutcome = LastPushOutcome.NEVER_PUSHED
        private set

    fun postRecordingCompleted(metadata: SidecarMetadata): LastPushOutcome {
        val installed = try {
            context.packageManager.getPackageInfo(BiosClient.BIOS_PACKAGE, 0)
            true
        } catch (_: Throwable) {
            false
        }
        if (!installed) {
            // Don't flip the outcome to FAILED — Bios isn't installed, so there's
            // nothing to "fail at". Leaves NEVER_PUSHED so the Settings UI shows the
            // not-installed pill rather than an alarming error banner.
            return lastPushOutcome
        }
        return try {
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
            val outcome = when {
                uri == null -> LastPushOutcome.PENDING_APPROVAL
                uri.lastPathSegment == "pending" -> LastPushOutcome.PENDING_APPROVAL
                else -> LastPushOutcome.OK
            }
            lastPushOutcome = outcome
            outcome
        } catch (e: SecurityException) {
            Log.w(TAG, "Bios companion write blocked: ${e.message}")
            lastPushOutcome = LastPushOutcome.PENDING_APPROVAL
            lastPushOutcome
        } catch (t: Throwable) {
            Log.w(TAG, "Bios companion write failed: ${t.message}")
            lastPushOutcome = LastPushOutcome.FAILED
            lastPushOutcome
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
