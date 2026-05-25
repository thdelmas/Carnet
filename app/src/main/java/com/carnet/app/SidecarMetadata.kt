package com.carnet.app

/**
 * Everything that gets written to the sidecar JSON next to a recording. One half of the
 * metadata story (the other half is the burned-in HUD on the video frames themselves).
 *
 * Series provenance (id + name) is denormalised onto every take so that even if the
 * series is later deleted the take's sidecar still records the protocol it ran under.
 */
data class SidecarMetadata(
    val schemaVersion: Int,
    val filename: String,
    val subject: String,
    val sessionLabel: String,
    val experimentLabel: String,
    val sessionNumber: Int,
    val uid: String,
    val dateLocal: String,
    val recordStartMillis: Long,
    val recordEndMillis: Long,
    val biosSnapshot: BiosSnapshot?,
    val seriesId: String?,
    val seriesName: String?,
)
