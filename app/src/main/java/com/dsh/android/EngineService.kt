package com.dsh.android

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 前台服务：让引擎进程在 App 切到后台后继续存活。
 *
 * 没有它，系统会在内存紧张时回收整个进程，正在进行的对话就断了。
 *
 * 这里承担两件事，缺一不可：
 *
 * 1. **前台服务 + 常驻通知** —— 把进程的 oom_adj 钉在前台档位，系统不会因内存压力
 *    回收它。这是「App 在后台还活着」的前提。
 * 2. **PARTIAL_WAKE_LOCK** —— 只保活进程是不够的：锁屏后 CPU 会进 suspend，
 *    node 的事件循环、定时器、网络回调全被冻住，长任务照样断。PARTIAL 只保 CPU
 *    不保屏幕，正好是引擎需要的。
 *
 * ⚠️ 光有这两样在国产 ROM 上仍可能被「电池优化 / 后台冻结」杀掉，那属于系统策略，
 * 只能由用户把本 App 加进电池优化白名单（见 `MainActivity.ensureBatteryExempt()`）。
 */
class EngineService : Service() {

    /** 只在服务存续期间持有；进程被杀时内核会自动释放，不会漏。 */
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_engine_running))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            // Android 12+ 在后台启动前台服务可能抛异常；此时退化为普通服务
            Log.w(DshApplication.TAG, "startForeground 失败: ${t.message}")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    /** 点通知回到 App（引擎还活着，MainActivity 会直接复用已有的 WebView 状态）。 */
    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 拿一把 PARTIAL_WAKE_LOCK。
     *
     * 刻意**不带超时**：超时到期会静默释放，长任务跑到一半 CPU 就被允许休眠了。
     * 生命周期由服务自己管（`onDestroy` 释放），服务在则锁在。
     */
    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            try {
                acquire()
                Log.i(DshApplication.TAG, "已持有 PARTIAL_WAKE_LOCK，锁屏后引擎继续运行")
            } catch (t: Throwable) {
                Log.w(DshApplication.TAG, "获取 WakeLock 失败: ${t.message}")
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            runCatching { if (lock.isHeld) lock.release() }
            Log.i(DshApplication.TAG, "已释放 WakeLock")
        }
        wakeLock = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "dsh-engine"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TAG = "dsh:engine"

        fun start(context: Context) {
            val intent = Intent(context, EngineService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EngineService::class.java))
        }
    }
}
