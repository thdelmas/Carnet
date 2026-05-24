package com.carnet.app

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log

/**
 * Reads the current vitals from the Bios ContentProvider and exposes the connection
 * [Status] used by the Bios-integration UI block (per design system §6.a). Failing
 * fast and returning null is fine — Carnet records anyway and the sidecar just notes
 * Bios was unavailable.
 */
class BiosClient(private val context: Context) {

    enum class Status {
        /** Bios package not present on device. Show dimmed pill. */
        NOT_INSTALLED,
        /** Installed but companion-app permission not granted / provider not responding. */
        NOT_ENABLED,
        /** Vitals provider responded. */
        CONNECTED,
    }

    fun status(): Status {
        if (!isInstalled()) return Status.NOT_INSTALLED
        return try {
            context.contentResolver.query(VITALS_URI, null, null, null, null).use { cursor ->
                if (cursor != null) Status.CONNECTED else Status.NOT_ENABLED
            }
        } catch (_: SecurityException) {
            Status.NOT_ENABLED
        } catch (t: Throwable) {
            Log.w(TAG, "Bios status probe failed: ${t.message}")
            Status.NOT_ENABLED
        }
    }

    fun isInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(BIOS_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

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

        // Cross-app contract — referenced by Settings deep-link button and pending-approval
        // banner action. See Bios design system §6.c.
        const val BIOS_PACKAGE = "com.bios.app"
        const val BIOS_EXTRA_NAVIGATE_TO_COMPANIONS = "navigate_to_companions"
    }
}
