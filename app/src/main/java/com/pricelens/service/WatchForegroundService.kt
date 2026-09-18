package com.pricelens.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pricelens.R
import com.pricelens.ui.main.MainActivity
import com.pricelens.worker.WatchCheckRunner
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * 盯价前台服务：通知栏常驻一条低优先级状态通知（"盯价运行中 · N 个目标 ·
 * 上次检查 HH:mm"），并在服务内直接跑 30 分钟检查循环。
 *
 * 与 WorkManager 的 PriceCheckWorker 互补：国产 ROM 冻结后台时
 * WorkManager 周期会被大幅推迟，常驻通知显著提升进程存活率；
 * Worker 仍保留作为服务未运行时的兜底。
 *
 * targetSdk 35 要求声明 FGS 类型；本服务不属于系统列出的具体类型，
 * 按 specialUse 申报（子类型 price_watch_keepalive）。
 */
@AndroidEntryPoint
class WatchForegroundService : Service() {

    @Inject lateinit var runner: WatchCheckRunner

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification("—", 0)
        serviceScope.launch {
            while (isActive) {
                val outcome = runCatching { runner.runOnce(this@WatchForegroundService) }
                    .onFailure { com.pricelens.util.LogT.w("盯价循环失败：${it.message}") }
                    .getOrNull()
                if (outcome != null && outcome.total == 0) {
                    // 目标全部移除：自我停止（控制器也会停，双保险）
                    stopSelf()
                    return@launch
                }
                updateNotification(outcome?.total ?: 0)
                delay(CHECK_INTERVAL_MS)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startForegroundNotification(lastCheck: String, targets: Int) {
        val notification = buildNotification(lastCheck, targets)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(targets: Int) {
        NotificationManagerCompat.from(this)
            .notify(NOTIFICATION_ID, buildNotification(timeFormat.format(Date()), targets))
    }

    private fun buildNotification(lastCheck: String, targets: Int) =
        NotificationCompat.Builder(this, WatchCheckRunner.CHANNEL_WATCH_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.watch_notification_running, targets, lastCheck))
            .setContentText(getString(R.string.watch_notification_desc))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHECK_INTERVAL_MS = 30 * 60 * 1000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, WatchForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WatchForegroundService::class.java))
        }
    }
}
