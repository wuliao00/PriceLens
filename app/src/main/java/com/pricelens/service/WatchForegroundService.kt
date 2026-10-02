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
import com.pricelens.util.LogT
import com.pricelens.worker.WatchCheckRunner
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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

    /** 30 分钟检查循环的唯一句柄（A4：服务被反复 start 也只跑一条循环） */
    private var loopJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification("—", 0)
        // A4：控制器侧每次 observeActive() emission、以及 START_STICKY 重启都会走这里。
        // 无条件 launch 会攒出「与目标数成正比」的并行循环：同一轮重复问不同源、
        // 重复降价通知、重复写日点，还会并发读写 WatchCheckRunner 的轮次状态。
        if (loopJob?.isActive != true) {
            loopJob = serviceScope.launch {
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
        }
        return START_STICKY
    }

    override fun onDestroy() {
        loopJob = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startForegroundNotification(lastCheck: String, targets: Int) {
        val notification = buildNotification(lastCheck, targets)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(targets: Int) {
        NotificationManagerCompat.from(this)
            .notify(NOTIFICATION_ID, buildNotification(timeFormat.format(Date()), targets))
    }

    private fun buildNotification(lastCheck: String, targets: Int) = NotificationCompat.Builder(this, WatchCheckRunner.CHANNEL_WATCH_STATUS)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(getString(R.string.watch_notification_running, targets, lastCheck))
        .setContentText(getString(R.string.watch_notification_desc))
        .setOngoing(true)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHECK_INTERVAL_MS = 30 * 60 * 1000L

        fun start(context: Context) {
            val intent = Intent(context, WatchForegroundService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                // Android 12+ 的后台启动限制：进程在后台（无障碍服务把它拉起来、WorkManager 建进程、
                // 开机广播）时 startForegroundService() 会抛 ForegroundServiceStartNotAllowedException，
                // 而调用点在 Room Flow 的 collect 里 → 未捕获 = 主线程崩，且进程一被拉起就再崩一次（崩溃循环）。
                // PLB110 / Android 15 真机实测到（crash-1790963779323.log，2026-10-03 01:56，
                // 打开京东触发无障碍事件那一刻）。这条路径的"盯价"实际由无障碍服务在做，
                // 常驻通知只是保活与可见性，起不来不该赔上进程：降级试普通 startService，再不行就等下一次跳变。
                LogT.w("常驻通知服务后台启动被拒（${e.javaClass.simpleName}），降级为普通 startService")
                runCatching { context.startService(intent) }
                    .onFailure { LogT.w("普通 startService 同样失败（${it.javaClass.simpleName}），本轮跳过常驻通知") }
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WatchForegroundService::class.java))
        }
    }
}
