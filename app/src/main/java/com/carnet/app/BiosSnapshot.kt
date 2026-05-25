package com.carnet.app

/**
 * Snapshot of selected Bios metrics read at record-start. Keys are
 * [MetricSpec.key] (Bios metric type IDs); values are the post-aggregation reading
 * (raw bpm / kg / event count / etc., per [MetricSpec.unit]). Null when the metric
 * was unavailable — a take is never blocked by a missing reading.
 */
data class BiosSnapshot(
    val values: Map<String, Double?>,
    val capturedAtMillis: Long,
)
