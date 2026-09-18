package com.pricelens.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * §8 后台盯价：每 30 分钟检查一次用户设定的目标价，
 * 达标 → 高优先级通知。网络可用 + 电量不低时才执行。
 *
 * 查价与通知逻辑收口在 [WatchCheckRunner]（与通知栏常驻的
 * WatchForegroundService 共用）；本 Worker 是服务未运行时的兜底调度。
 * 任一平台整轮失败 → [Result.retry]，按退避策略（10 分钟线性）重排。
 */
@HiltWorker
class PriceCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val runner: WatchCheckRunner
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val outcome = runner.runOnce(applicationContext)
        // 任一平台整轮失败 → retry，WorkManager 按退避策略重排，不影响 30 分钟周期
        return if (outcome.failedPlatformCount > 0) Result.retry() else Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PriceCheckWorker>(30, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                // 失败重试退避：线性 10 分钟递增，避免网络抖动时密集重跑
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "price_check",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
