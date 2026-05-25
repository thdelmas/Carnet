package com.carnet.app

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log

/**
 * Reads Bios metric readings via the canonical `/readings/{metricType}` provider and
 * exposes the connection [Status] used by the Bios-integration UI block (per design
 * system §6.a). Failing fast and returning null is fine — Carnet records anyway and
 * the sidecar just notes Bios was unavailable.
 *
 * History: an earlier draft pointed at `/vitals/current` (a non-existent endpoint),
 * which silently returned no rows on every device. The current shape mirrors the
 * actual Bios HealthProvider contract: per-metric reading streams plus a `/status`
 * endpoint we use only as a connection probe.
 */
class BiosClient(private val context: Context) {

    enum class Status {
        /** Bios package not present on device. Show dimmed pill. */
        NOT_INSTALLED,
        /** Installed but companion-app permission not granted / provider not responding. */
        NOT_ENABLED,
        /** Status provider responded. */
        CONNECTED,
    }

    fun status(): Status {
        if (!isInstalled()) return Status.NOT_INSTALLED
        return try {
            context.contentResolver.query(STATUS_URI, null, null, null, null).use { cursor ->
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

    /**
     * Snapshot the requested metrics now. Missing/unreadable values come back as null
     * in the resulting map so callers can render "--" without losing the other values.
     */
    fun snapshot(specs: List<MetricSpec>, nowMillis: Long = System.currentTimeMillis()): BiosSnapshot {
        val values = specs.associate { spec -> spec.key to readMetric(spec, nowMillis) }
        return BiosSnapshot(values = values, capturedAtMillis = nowMillis)
    }

    private fun readMetric(spec: MetricSpec, nowMillis: Long): Double? = when (spec.aggregation) {
        MetricSpec.Aggregation.LATEST -> readLatest(spec.key, nowMillis)
        MetricSpec.Aggregation.COUNT_24H -> readCount(spec.key, nowMillis - DAY_MS, nowMillis)
    }

    private fun readLatest(metricType: String, nowMillis: Long): Double? {
        // 7-day lookback gives sparse-frequency metrics like weight a fair shot at a hit
        // without dragging in stale readings — the intro slide is about "where am I now",
        // not "at any point ever".
        val start = nowMillis - LATEST_LOOKBACK_MS
        val uri = readingsUri(metricType, start, nowMillis)
        return query(uri) { cursor ->
            val valueIdx = cursor.getColumnIndex(COL_VALUE)
            val tsIdx = cursor.getColumnIndex(COL_TIMESTAMP)
            if (valueIdx < 0 || tsIdx < 0) return@query null
            var latestTs = Long.MIN_VALUE
            var latestVal: Double? = null
            while (cursor.moveToNext()) {
                val ts = cursor.getLong(tsIdx)
                if (ts > latestTs) {
                    latestTs = ts
                    latestVal = if (cursor.isNull(valueIdx)) null else cursor.getDouble(valueIdx)
                }
            }
            latestVal
        }
    }

    private fun readCount(metricType: String, startMillis: Long, endMillis: Long): Double? {
        val uri = readingsUri(metricType, startMillis, endMillis)
        return query(uri) { cursor -> cursor.count.toDouble() }
    }

    private fun readingsUri(metricType: String, startMillis: Long, endMillis: Long): Uri =
        Uri.parse("content://$AUTHORITY/readings/$metricType?start=$startMillis&end=$endMillis")

    private inline fun <T> query(uri: Uri, block: (android.database.Cursor) -> T?): T? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use(block)
        } catch (_: SecurityException) {
            null
        } catch (t: Throwable) {
            Log.w(TAG, "query($uri) failed: ${t.message}")
            null
        }
    }

    companion object {
        private const val TAG = "Carnet/Bios"
        private const val AUTHORITY = "com.bios.app.health"
        val STATUS_URI: Uri = Uri.parse("content://$AUTHORITY/status")
        private const val COL_VALUE = "value"
        private const val COL_TIMESTAMP = "timestamp"
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val LATEST_LOOKBACK_MS = 7L * DAY_MS

        // Cross-app contract — referenced by Settings deep-link button and pending-approval
        // banner action. See Bios design system §6.c.
        const val BIOS_PACKAGE = "com.bios.app"
        const val BIOS_EXTRA_NAVIGATE_TO_COMPANIONS = "navigate_to_companions"
    }
}
