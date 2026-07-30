package dev.buhanzaz.rwms.worker

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Notification content intentionally contains no task or worker data from the push payload. */
object WorkerNotifications {
    private const val MANDATORY_CHANNEL = "rwms_worker_mandatory"
    private const val UPDATES_CHANNEL = "rwms_worker_updates"
    private const val SYNC_CHANNEL = "rwms_worker_sync"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(MANDATORY_CHANNEL, "Обязательные задания", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(UPDATES_CHANNEL, "Обновления заданий", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(SYNC_CHANNEL, "Синхронизация", NotificationManager.IMPORTANCE_LOW),
            ),
        )
    }

    /** Do not request notification permission or initialize Firebase without release config. */
    fun isFirebaseConfigured(context: Context): Boolean =
        context.resources.getIdentifier("google_app_id", "string", context.packageName) != 0

    @SuppressLint("MissingPermission")
    fun show(context: Context, invalidation: WorkerPushInvalidation) {
        if (!canPostNotifications(context)) return
        val contentIntent = PendingIntent.getActivity(
            context,
            invalidation.eventId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        NotificationManagerCompat.from(context).notify(
            invalidation.eventId.hashCode(),
            NotificationCompat.Builder(context, channelFor(invalidation.type))
                .setSmallIcon(R.drawable.ic_stat_rwms_worker)
                .setContentTitle("RWMS Рабочий")
                .setContentText(messageFor(invalidation.type))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun canPostNotifications(context: Context): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun channelFor(type: String): String = when {
        type.contains("MANDATORY", ignoreCase = true) -> MANDATORY_CHANNEL
        type.contains("SYNC", ignoreCase = true) -> SYNC_CHANNEL
        else -> UPDATES_CHANNEL
    }

    private fun messageFor(type: String): String = when {
        type.contains("MANDATORY", ignoreCase = true) -> "Появилось обязательное задание"
        type.contains("SYNC", ignoreCase = true) -> "Данные RWMS обновляются"
        else -> "Состояние заданий изменилось"
    }
}
