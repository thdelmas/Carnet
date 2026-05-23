package com.carnet.app

import android.content.Context

/**
 * Subject + session/experiment labels the operator sets before recording. Persists across
 * launches so the cadence (weekly default) doesn't require re-entering identity every time.
 * The labels are sanitised (uppercased, non-word chars stripped) so they're safe to drop
 * straight into filenames and the HUD.
 */
data class SessionConfig(
    val subject: String,
    val sessionLabel: String,
    val experimentLabel: String,
) {
    companion object {
        const val DEFAULT_SUBJECT = "subject"
        const val DEFAULT_SESSION_LABEL = "SCAFFOLD"
        const val DEFAULT_EXPERIMENT_LABEL = ""

        private const val PREFS = "carnet_session_config"
        private const val KEY_SUBJECT = "subject"
        private const val KEY_SESSION_LABEL = "session_label"
        private const val KEY_EXPERIMENT_LABEL = "experiment_label"

        fun load(context: Context): SessionConfig {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return SessionConfig(
                subject = prefs.getString(KEY_SUBJECT, DEFAULT_SUBJECT) ?: DEFAULT_SUBJECT,
                sessionLabel = prefs.getString(KEY_SESSION_LABEL, DEFAULT_SESSION_LABEL)
                    ?: DEFAULT_SESSION_LABEL,
                experimentLabel = prefs.getString(KEY_EXPERIMENT_LABEL, DEFAULT_EXPERIMENT_LABEL)
                    ?: DEFAULT_EXPERIMENT_LABEL,
            )
        }

        fun save(context: Context, config: SessionConfig) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_SUBJECT, config.subject)
                .putString(KEY_SESSION_LABEL, config.sessionLabel)
                .putString(KEY_EXPERIMENT_LABEL, config.experimentLabel)
                .apply()
        }

        /** Strip whitespace and non-word chars, uppercase. Empty input falls back to [fallback]. */
        fun sanitise(raw: String, fallback: String): String {
            val cleaned = raw.trim().replace(Regex("[^A-Za-z0-9_-]"), "_").uppercase()
            return cleaned.ifBlank { fallback }
        }
    }
}
