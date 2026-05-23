package com.carnet.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Weekly recording-cadence nudge: the manifesto's "weekly default" turned into an actual
 * background reminder. Scheduling is one-shot via WorkManager (re-enqueued after every
 * finalised take with ExistingWorkPolicy.REPLACE, so the clock resets each session rather
 * than firing strictly every 7 days regardless of activity).
 */
object CadenceReminder {

    const val CHANNEL_ID = "carnet_cadence"
    private const val WORK_NAME = "carnet_cadence_weekly"
    private const val NOTIF_ID = 1001
    private const val WEEKLY_DELAY_DAYS = 7L

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.cadence_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.cadence_channel_description)
        }
        mgr.createNotificationChannel(channel)
    }

    fun scheduleNext(context: Context) {
        val request = OneTimeWorkRequestBuilder<CadenceWorker>()
            .setInitialDelay(WEEKLY_DELAY_DAYS, TimeUnit.DAYS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME, ExistingWorkPolicy.REPLACE, request,
        )
    }

    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    internal fun post(context: Context) {
        if (!hasNotificationPermission(context)) return
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pi = PendingIntent.getActivity(
            context, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.btn_record_idle)
            .setContentTitle(context.getString(R.string.cadence_notif_title))
            .setContentText(context.getString(R.string.cadence_notif_body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIF_ID, notif)
    }
}

class CadenceWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        CadenceReminder.post(applicationContext)
        return Result.success()
    }
}
