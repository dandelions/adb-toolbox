package com.toolbox.adbhelper.core.scrcpy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.toolbox.adbhelper.R
import com.toolbox.adbhelper.ui.ScrcpyActivity

/**
 * 远程音频后台播放前台服务 (含系统级 CPU WakeLock 与 WiFi 高性能锁保活)
 * 当主控机开启音频或切回手机桌面时，该服务在系统通知栏持续常驻保活，
 * 防止 Android 系统将应用进程休眠/冻结，确保被控端音频持续稳定流畅播放。
 */
class AudioPlaybackService : Service {

    constructor() : super()

    companion object {
        private const val TAG = "AudioPlaybackService"
        private const val CHANNEL_ID = "adb_audio_playback_channel"
        private const val NOTIFICATION_ID = 2001

        const val ACTION_START = "com.toolbox.adbhelper.action.START_AUDIO_SERVICE"
        const val ACTION_STOP = "com.toolbox.adbhelper.action.STOP_AUDIO_SERVICE"

        var onServiceStopRequested: (() -> Unit)? = null
        var isServiceRunning: Boolean = false
            private set

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
            } catch (e: Exception) {
                Log.w(TAG, "启动音频前台保活服务异常: ${e.message}")
            }
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

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireLocks()
        isServiceRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            onServiceStopRequested?.invoke()
            stopForegroundCompat()
            releaseLocks()
            stopSelf()
            isServiceRunning = false
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "startForeground 失败: ${e.message}")
        }

        acquireLocks()
        isServiceRunning = true
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "com.toolbox.adbhelper:AudioPlaybackWakeLock"
                )?.apply {
                    setReferenceCounted(false)
                    acquire(24 * 60 * 60 * 1000L) // 最多持有 24 小时防死锁
                }
                Log.i(TAG, "已成功获取 CPU WakeLock 保活锁")
            }

            if (wifiLock == null) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val lockType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wifiManager?.createWifiLock(lockType, "com.toolbox.adbhelper:AudioPlaybackWifiLock")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
                Log.i(TAG, "已成功获取 WiFi 高性能低延迟锁")
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取系统唤醒锁异常: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null

            wifiLock?.let {
                if (it.isHeld) it.release()
            }
            wifiLock = null
            Log.i(TAG, "已释放系统唤醒锁")
        } catch (e: Exception) {
            Log.w(TAG, "释放唤醒锁异常: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "远程音频后台播放",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "被控设备音频后台持续传输与播放保活通知"
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
            .setContentText("投屏画面已后台节能运行，被控端声音持续播放中")
            .setContentIntent(clickPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_nav_close, "停止音频", stopPendingIntent)
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
        isServiceRunning = false
        releaseLocks()
        stopForegroundCompat()
        super.onDestroy()
    }
}
