package com.example.videoconverter

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * 一个很轻量的前台服务：任务开始时 startForeground 显示通知，
 * 任务过程中通过 updateProgress 刷新通知进度，任务结束时 stopSelf。
 * 目的是防止系统在应用退到后台时把处理进程杀掉。
 */
class ProcessingService : Service() {

    companion object {
        const val ACTION_START = "action_start"
        const val ACTION_UPDATE = "action_update"
        const val ACTION_STOP = "action_stop"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_CONTENT = "extra_content"
        const val EXTRA_PERCENT = "extra_percent"
        const val EXTRA_INDETERMINATE = "extra_indeterminate"

        fun start(context: android.content.Context, title: String, content: String) {
            val intent = Intent(context, ProcessingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_CONTENT, content)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun update(context: android.content.Context, title: String, content: String, percent: Int, indeterminate: Boolean = false) {
            val intent = Intent(context, ProcessingService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_CONTENT, content)
                putExtra(EXTRA_PERCENT, percent)
                putExtra(EXTRA_INDETERMINATE, indeterminate)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                // 服务可能已经在停止过程中，忽略即可
            }
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, ProcessingService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "正在处理视频"
                val content = intent.getStringExtra(EXTRA_CONTENT) ?: "处理中..."
                startForeground(NotificationHelper.NOTIFICATION_ID, NotificationHelper.buildNotification(this, title, content, 0, true))
            }
            ACTION_UPDATE -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "正在处理视频"
                val content = intent.getStringExtra(EXTRA_CONTENT) ?: "处理中..."
                val percent = intent.getIntExtra(EXTRA_PERCENT, 0)
                val indeterminate = intent.getBooleanExtra(EXTRA_INDETERMINATE, false)
                val manager = getSystemService(android.app.NotificationManager::class.java)
                manager.notify(
                    NotificationHelper.NOTIFICATION_ID,
                    NotificationHelper.buildNotification(this, title, content, percent, indeterminate)
                )
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
}
