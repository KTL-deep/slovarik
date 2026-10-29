package com.slovarik.app

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
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

private const val CHANNEL = "study"
private const val WORK = "study_reminder"

fun notificationsAllowed(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
    val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)
    return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
}

fun reminderEnabled(context: Context): Boolean =
    context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("reminder", false) && notificationsAllowed(context)

class ReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        if (!reminderEnabled(applicationContext)) return Result.success()
        val localized = InterfaceLanguage.wrap(applicationContext)
        val count = Store(applicationContext).use { it.dueCount() }
        if (count <= 0) return Result.success()
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, localized.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_DEFAULT))
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
        val openApp = PendingIntent.getActivity(applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(localized.getString(R.string.reminder_title))
            .setContentText(localized.getString(R.string.reminder_count, count)).setContentIntent(openApp).setAutoCancel(true).build()
        try {
            NotificationManagerCompat.from(applicationContext).notify(1, notification)
        } catch (_: SecurityException) {
            // Permission may be revoked between the check and notification delivery.
            return Result.success()
        }
        return Result.success()
    }
}

fun setReminder(context: Context, hour: Int, minute: Int, store: Store? = null) {
    if (store != null) { UserProfileBridge.saveReminder(context, store, true, hour, minute); return }
    applyReminderSchedule(context, true, hour, minute)
    context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putInt("hour", hour).putInt("minute", minute).putBoolean("reminder", true).apply()
}

/** Device-side effect only: never modifies user intent or the profile database. */
internal fun applyReminderSchedule(context: Context, enabled: Boolean, hour: Int, minute: Int) {
    require(hour in 0..23 && minute in 0..59)
    if (!enabled || !notificationsAllowed(context)) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
        NotificationManagerCompat.from(context).cancel(1)
        return
    }
    val now = Calendar.getInstance()
    val next = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        if (!after(now)) add(Calendar.DAY_OF_YEAR, 1)
    }
    val request = PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS)
        .setInitialDelay(next.timeInMillis - now.timeInMillis, TimeUnit.MILLISECONDS).build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request)
}

fun cancelReminder(context: Context, store: Store? = null) {
    if (store != null) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        UserProfileBridge.saveReminder(context, store, false, prefs.getInt("hour", 19), prefs.getInt("minute", 0))
        return
    }
    applyReminderSchedule(context, false, 19, 0)
    context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("reminder", false).apply()
}
