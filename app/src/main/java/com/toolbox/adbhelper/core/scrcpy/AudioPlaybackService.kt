package com.toolbox.adbhelper.core.scrcpy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.toolbox.adbhelper.R
import com.toolbox.adbhelper.ui.ScrcpyActivity

/**
 * 远程音频后台播放前台服务
 * 当主控机按 Home 键切后台时，该服务在通知栏常驻保活，
 * 确保被控设备音频持续低延迟/高保真传输播放，同时视频画面停止传输以极大节约两端电量与带宽。
 */
class AudioPlaybackService : Service {

    constructor() : super()

    companion object {
        private const val CHANNEL_ID = "adb_audio_playback_channel"
        private const val NOTIFICATION_ID = 2001

        const val ACTION_START = "com.toolbox.adbhelper.action.START_AUDIO_SERVICE"
        const val ACTION_STOP = "com.toolbox.adbhelper.action.STOP_AUDIO_SERVICE"

        var onServiceStopRequested: (() -> Unit)? = null

        fun start(context: Context) {
            val intent = Intent(context, AudioPlaybackService::class.java).apply {
                action = ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }

        fun stop(context: Context) {
            val intent = Intent(context, AudioPlaybackService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            onServiceStopRequested?.invoke()
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (_: Exception) {}

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "远程音频后台播放",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "被控设备音频后台持续传输与播放通知"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val clickIntent = Intent(this, ScrcpyActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val clickPendingIntent = PendingIntent.getActivity(
            this,
            0,
            clickIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val stopIntent = Intent(this, AudioPlaybackService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("🎵 正在后台接收远程音频播放")
            .setContentText("投屏画面已暂停以节约电量，被控端音乐与声音持续播放中")
            .setContentIntent(clickPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_nav_close, "停止播放", stopPendingIntent)
            .build()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        stopForegroundCompat()
        super.onDestroy()
    }
}
