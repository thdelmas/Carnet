package com.carnet.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground service that hosts the MediaProjection-driven [ScreenCaptureSource] while
 * screen capture is armed. Android 10+ requires a service of type `mediaProjection` to
 * exist before `getMediaProjection` is called (or `SecurityException`), and Android 14+
 * additionally mandates the `FOREGROUND_SERVICE_MEDIA_PROJECTION` permission.
 *
 * The activity reads the active source via [activeSource] inside the OverlayEffect
 * draw callback — kept as a static reference (intentionally over a Binder) so the
 * compositor never blocks on the service lifecycle.
 */
class ScreenCaptureService : Service() {

    private var source: ScreenCaptureSource? = null
    private var projection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        startForegroundCompat()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = intent?.let {
            @Suppress("DEPRECATION")
            it.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode == 0 || data == null) {
            Log.w(TAG, "started without consent payload — stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        val mgr = getSystemService(MediaProjectionManager::class.java)
        val proj = try {
            mgr.getMediaProjection(resultCode, data)
        } catch (t: Throwable) {
            Log.w(TAG, "getMediaProjection failed", t)
            null
        }
        if (proj == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        projection = proj
        val src = ScreenCaptureSource(this, proj).also { it.start() }
        source = src
        activeSource = src
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        activeSource = null
        source?.release()
        source = null
        projection = null
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_screen_capture)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.screen_capture_active))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.screen_capture_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    companion object {
        private const val TAG = "Carnet"
        private const val CHANNEL_ID = "carnet_screen_capture"
        private const val NOTIF_ID = 4711
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        @Volatile var activeSource: ScreenCaptureSource? = null
            private set

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            val intent = Intent(ctx, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ScreenCaptureService::class.java))
        }
    }
}
