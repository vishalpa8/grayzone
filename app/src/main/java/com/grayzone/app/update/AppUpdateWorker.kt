package com.grayzone.app.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.grayzone.app.BuildConfig
import com.grayzone.app.GrayzoneLogger
import com.grayzone.app.LogComponent
import com.grayzone.app.MainActivity
import com.grayzone.app.PrefsKeys
import com.grayzone.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Daily check for a newer app release; posts one notification per new version. */
class AppUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!BuildConfig.SELF_UPDATE) return@withContext Result.success()
        val result = AppUpdater(applicationContext).check()
        if (result is AppUpdater.CheckResult.Available) notifyOnce(result.info)
        Result.success()
    }

    private fun notifyOnce(info: AppUpdateInfo) {
        val context = applicationContext
        val prefs = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getInt(PrefsKeys.APP_UPDATE_NOTIFIED_CODE, 0) >= info.versionCode) return

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Tells you when a new version of Grayzone is ready"
                setShowBadge(true)
            }
        )
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_TAB, "SETTINGS")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF0F766E.toInt())
            .setContentTitle("Grayzone ${info.versionName} is available")
            .setContentText("Tap to update from Settings")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID, notification)
            prefs.edit().putInt(PrefsKeys.APP_UPDATE_NOTIFIED_CODE, info.versionCode).apply()
        } catch (e: SecurityException) {
            // Notifications not allowed; Settings still shows the update.
            GrayzoneLogger.d(LogComponent.UI, "Update notification not shown: notifications are off")
        }
    }

    companion object {
        private const val CHANNEL_ID = "app_updates"
        /** Must not reuse OverlayService's ids (1-3) or the VPN's (4). */
        private const val NOTIF_ID = 5
        private const val WORK_NAME = "app_update_check"

        fun schedule(context: Context) {
            if (!BuildConfig.SELF_UPDATE) return
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<AppUpdateWorker>(1, TimeUnit.DAYS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .setRequiresBatteryNotLow(true)
                            .build()
                    )
                    .build()
            )
        }
    }
}
