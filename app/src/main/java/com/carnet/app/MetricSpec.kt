package com.carnet.app

import java.util.Locale

/**
 * Catalog of Bios metrics Carnet can pull into a recording's intro slides. Each spec
 * pins one Bios `/readings/{metricType}` key to the display label, unit, and the kind
 * of aggregation that makes sense for an at-a-glance "where am I right now" slide.
 *
 * The Bios provider exposes far more metrics than this list — we intentionally limit
 * to the ones with intuitive intro semantics so the picker doesn't drown the user in
 * physiological obscurities. Add more as series demand them.
 */
data class MetricSpec(
    val key: String,
    val label: String,
    val unit: String,
    val aggregation: Aggregation,
) {
    enum class Aggregation {
        /** Most recent reading (e.g. current heart rate, latest body mass weigh-in). */
        LATEST,
        /**
         * Sum of the `value` column over the trailing 24 h. Robust for both
         * one-row-per-event ledgers (value=1, sum == count) and
         * one-row-per-session ledgers where `value` carries the dose (sum == dose
         * total). Replaces the earlier COUNT_24H which mis-read multi-quantity rows
         * as 1 each.
         */
        SUM_24H,
        /** Mean of the `value` column over the trailing 7 days. For sleep-class
         *  metrics where one number per night smoothes to something meaningful. */
        AVG_7D,
    }

    /**
     * Render the raw double as the intro slide displays it: "--" on null, integer for
     * count/integer-natured metrics, one decimal for kg/% etc.
     */
    fun formatValue(raw: Double?): String {
        if (raw == null) return "--"
        val asInt = raw.toInt()
        val isInteger = aggregation == Aggregation.SUM_24H ||
            unit in INT_UNITS ||
            raw == asInt.toDouble()
        return if (isInteger) asInt.toString() else String.format(Locale.US, "%.1f", raw)
    }

    companion object {
        private val INT_UNITS = setOf("bpm", "score", "events", "ms")

        val CATALOG: List<MetricSpec> = listOf(
            // Passive biometrics ---------------------------------------------------
            MetricSpec("heart_rate", "HEART RATE", "bpm", Aggregation.LATEST),
            MetricSpec("heart_rate_variability", "HRV", "ms", Aggregation.LATEST),
            MetricSpec("resting_heart_rate", "RESTING HR", "bpm", Aggregation.LATEST),
            MetricSpec("body_mass", "BODY MASS", "kg", Aggregation.LATEST),
            MetricSpec("body_fat_pct", "BODY FAT", "%", Aggregation.LATEST),
            MetricSpec("sleep_score", "SLEEP SCORE", "score", Aggregation.LATEST),
            MetricSpec("sleep_duration", "SLEEP 7D AVG", "h", Aggregation.AVG_7D),
            MetricSpec("sleep_efficiency", "SLEEP EFFICIENCY", "%", Aggregation.LATEST),
            MetricSpec("sleep_regularity", "SLEEP REGULARITY", "score", Aggregation.LATEST),
            // Substance use events -------------------------------------------------
            MetricSpec("tobacco_use", "TOBACCO 24H", "events", Aggregation.SUM_24H),
            MetricSpec("cannabis_use", "CANNABIS 24H", "events", Aggregation.SUM_24H),
            MetricSpec("caffeine_intake", "CAFFEINE 24H", "events", Aggregation.SUM_24H),
            MetricSpec("alcohol_intake", "ALCOHOL 24H", "events", Aggregation.SUM_24H),
            // Self-evaluation — mood / state --------------------------------------
            MetricSpec("mood_self_rating", "MOOD", "score", Aggregation.LATEST),
            MetricSpec("energy_self_rating", "ENERGY", "score", Aggregation.LATEST),
            MetricSpec("focus_self_rating", "FOCUS", "score", Aggregation.LATEST),
            MetricSpec("stress_score", "STRESS", "score", Aggregation.LATEST),
            // Self-evaluation — craving intensity ---------------------------------
            MetricSpec("tobacco_craving_intensity", "TOBACCO CRAVING", "score", Aggregation.LATEST),
            MetricSpec("cannabis_craving_intensity", "CANNABIS CRAVING", "score", Aggregation.LATEST),
            // Self-evaluation — identity / agency ---------------------------------
            MetricSpec("smoker_identity_self_rating", "SMOKER IDENTITY", "score", Aggregation.LATEST),
            MetricSpec("change_agency_self_rating", "CHANGE AGENCY", "score", Aggregation.LATEST),
            MetricSpec("social_belonging_self_rating", "SOCIAL BELONGING", "score", Aggregation.LATEST),
            // Self-evaluation — symptoms ------------------------------------------
            MetricSpec("pain_score", "PAIN", "score", Aggregation.LATEST),
            MetricSpec("headache_intensity_nrs", "HEADACHE", "score", Aggregation.LATEST),
            // Self-evaluation — chemosensory --------------------------------------
            MetricSpec("smell_self_rating", "SMELL", "score", Aggregation.LATEST),
            MetricSpec("taste_self_rating", "TASTE", "score", Aggregation.LATEST),
            // Self-evaluation — respiratory ---------------------------------------
            MetricSpec("breath_ease_self_rating", "BREATH EASE", "score", Aggregation.LATEST),
            MetricSpec("cough_frequency_self_rating", "COUGH FREQUENCY", "score", Aggregation.LATEST),
            MetricSpec("sputum_self_rating", "SPUTUM", "score", Aggregation.LATEST),
        )

        fun byKey(key: String): MetricSpec? = CATALOG.firstOrNull { it.key == key }
    }
}
