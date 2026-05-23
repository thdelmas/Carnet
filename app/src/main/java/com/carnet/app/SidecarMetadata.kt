package com.carnet.app

/**
 * Everything that gets written to the sidecar JSON next to a recording. One half of the
 * metadata story (the other half is the burned-in HUD on the video frames themselves).
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
)
