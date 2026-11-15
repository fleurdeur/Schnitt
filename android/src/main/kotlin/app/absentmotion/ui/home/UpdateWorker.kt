package app.absentmotion.ui.home

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.absentmotion.R
import app.absentmotion.i18n.inAppLanguage
import app.absentmotion.library.AppSettings
import app.absentmotion.platform.versionName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Checks GitHub while the app is closed and posts a single update notification. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val settings = AppSettings(context)
        if (!settings.autoUpdateCheck) return Result.success()
        val release = withContext(Dispatchers.IO) { runCatching { Updates.latest(context) }.getOrNull() } ?: return Result.success()
        val current = Updates.installed(context)
        if (isNewer(release.version, current) != true) return Result.success()
        if (settings.ignoredUpdate == release.version) return Result.success()
        val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
        if (prefs.getString(KEY_NOTIFIED, null) == release.version) return Result.success()
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return Result.success()
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.update_notify_channel))
                .setDescription(context.getString(R.string.update_notify_available, current))
                .setShowBadge(true)
                .build(),
        )
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return Result.success()
        val body = context.getString(R.string.update_notify_available, current)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val appIntent = PendingIntent.getActivity(context, 1, open, flags)
        val downloadUrl = release.apk?.url ?: release.link
        val downloadIntent = PendingIntent.getActivity(
            context,
            2,
            Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            flags,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF7ED9A0.toInt())
            .setContentTitle(context.getString(R.string.update_available, release.version))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(appIntent)
            .addAction(R.drawable.ic_notification, context.getString(R.string.update_notify_download), downloadIntent)
            .addAction(R.drawable.ic_notification, context.getString(R.string.update_notify_open_app), appIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try { manager.notify(NOTIFICATION_ID, notification) } catch (_: SecurityException) { return Result.success() }
        prefs.edit().putString(KEY_NOTIFIED, release.version).apply()
        return Result.success()
    }

    companion object {
        private const val CHANNEL = "updates"
        private const val NOTIFICATION_ID = 4105
        private const val KEY_NOTIFIED = "notifiedVersion"
        private const val PERIODIC = "update-check-periodic"
        private const val ONCE = "update-check-once"

        fun schedule(context: Context) {
            val app = context.applicationContext
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val periodic = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInitialDelay(30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(app).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, periodic)
            val once = OneTimeWorkRequestBuilder<UpdateWorker>().setConstraints(constraints).build()
            WorkManager.getInstance(app).enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP, once)
        }
    }
}
