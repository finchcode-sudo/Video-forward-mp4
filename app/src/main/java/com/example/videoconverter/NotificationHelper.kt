package com.example.videoconverter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * 统一管理"处理进度"通知：创建渠道、构建/更新前台服务通知。
 */
object NotificationHelper {

    const val CHANNEL_ID = "video_processing_channel"
    const val NOTIFICATION_ID = 1001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "视频处理进度",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "显示视频转换/压缩等任务的处理进度"
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    fun buildNotification(
        context: Context,
        title: String,
        content: String,
        percent: Int,
        indeterminate: Boolean = false
    ): android.app.Notification {
        ensureChannel(context)

        val contentIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(content)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setProgress(100, percent.coerceIn(0, 100), indeterminate)
            .build()
    }

    fun buildDoneNotification(context: Context, title: String, content: String): android.app.Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
    }

    fun notifyDone(context: Context, title: String, content: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID + 1, buildDoneNotification(context, title, content))
    }
}
