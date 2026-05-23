package com.carnet.app

/**
 * Snapshot of vitals read from the Bios companion app at record-start. Any field can be null
 * when Bios is unreachable or the metric hasn't been measured yet — sidecars persist whatever
 * was available so a take is never blocked by a missing reading.
 */
data class BiosSnapshot(
    val heartRate: Int?,
    val heartRateVariability: Int?,
    val restingHeartRate: Int?,
    val sleepScore: Int?,
    val tobaccoUseEvents24h: Int?,
    val capturedAtMillis: Long,
)
