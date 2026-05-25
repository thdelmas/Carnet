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
        /** Count of readings in the trailing 24 h (e.g. cigarettes today, joints today). */
        COUNT_24H,
    }

    /**
     * Render the raw double as the intro slide displays it: "--" on null, integer for
     * count/integer-natured metrics, one decimal for kg/% etc.
     */
    fun formatValue(raw: Double?): String {
        if (raw == null) return "--"
        val asInt = raw.toInt()
        val isInteger = aggregation == Aggregation.COUNT_24H ||
            unit in INT_UNITS ||
            raw == asInt.toDouble()
        return if (isInteger) asInt.toString() else String.format(Locale.US, "%.1f", raw)
    }

    companion object {
        private val INT_UNITS = setOf("bpm", "score", "events", "ms")

        val CATALOG: List<MetricSpec> = listOf(
            MetricSpec("heart_rate", "HEART RATE", "bpm", Aggregation.LATEST),
            MetricSpec("heart_rate_variability", "HRV", "ms", Aggregation.LATEST),
            MetricSpec("resting_heart_rate", "RESTING HR", "bpm", Aggregation.LATEST),
            MetricSpec("body_mass", "BODY MASS", "kg", Aggregation.LATEST),
            MetricSpec("body_fat_pct", "BODY FAT", "%", Aggregation.LATEST),
            MetricSpec("sleep_score", "SLEEP", "score", Aggregation.LATEST),
            MetricSpec("tobacco_use", "TOBACCO 24H", "events", Aggregation.COUNT_24H),
            MetricSpec("cannabis_use", "CANNABIS 24H", "events", Aggregation.COUNT_24H),
            MetricSpec("caffeine_intake", "CAFFEINE 24H", "events", Aggregation.COUNT_24H),
            MetricSpec("alcohol_intake", "ALCOHOL 24H", "events", Aggregation.COUNT_24H),
        )

        fun byKey(key: String): MetricSpec? = CATALOG.firstOrNull { it.key == key }
    }
}
