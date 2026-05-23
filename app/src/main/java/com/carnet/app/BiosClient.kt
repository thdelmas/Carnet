package com.carnet.app

import android.content.Context
import android.net.Uri
import android.util.Log

/**
 * Reads the current vitals from the Bios ContentProvider. Failing fast and returning null is
 * fine — Carnet records anyway and the sidecar just notes Bios was unavailable. Contract
 * mirrors the manifest's `com.bios.app.health` authority + the metric set called out in the
 * project roadmap.
 */
class BiosClient(private val context: Context) {

    fun snapshot(): BiosSnapshot? {
        return try {
            context.contentResolver.query(VITALS_URI, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                BiosSnapshot(
                    heartRate = cursor.intOrNull(COL_HEART_RATE),
                    heartRateVariability = cursor.intOrNull(COL_HRV),
                    restingHeartRate = cursor.intOrNull(COL_RESTING_HR),
                    sleepScore = cursor.intOrNull(COL_SLEEP_SCORE),
                    tobaccoUseEvents24h = cursor.intOrNull(COL_TOBACCO_24H),
                    capturedAtMillis = System.currentTimeMillis(),
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Bios snapshot unavailable: ${t.message}")
            null
        }
    }

    private fun android.database.Cursor.intOrNull(column: String): Int? {
        val idx = getColumnIndex(column)
        return if (idx < 0 || isNull(idx)) null else getInt(idx)
    }

    companion object {
        private const val TAG = "Carnet/Bios"
        private const val AUTHORITY = "com.bios.app.health"
        val VITALS_URI: Uri = Uri.parse("content://$AUTHORITY/vitals/current")
        private const val COL_HEART_RATE = "heart_rate"
        private const val COL_HRV = "heart_rate_variability"
        private const val COL_RESTING_HR = "resting_heart_rate"
        private const val COL_SLEEP_SCORE = "sleep_score"
        private const val COL_TOBACCO_24H = "tobacco_use_24h"
    }
}
