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
import com.pricelens.util.LogT
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
 *
 * 2026-09 盯价链路修复（A3）：每轮把"跳过多少目标、因何跳过"如实记账并落日志；
 * 平台没有查价能力不算整轮失败（重试也不会凭空长出价格），零进展时由
 * [WatchCheckRunner] 发一条可点开的说明通知，UI 侧盯价页同步展示轮次实况。
 */
@HiltWorker
class PriceCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val runner: WatchCheckRunner
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val outcome = runner.runOnce(applicationContext)
        // 逐目标如实记账：无通道 / 主键非法 / 取不到现价（p.3.cn 已下线）都要有数，
        // 不再让"盯了没反应"只剩一句日志。零进展时由 WatchCheckRunner 发可点开的说明通知。
        LogT.i(
            "盯价轮次：目标 ${outcome.total}，查到价 ${outcome.checked}，" +
                "达标 ${outcome.triggered}，跳过 无通道/${outcome.skipped.noChannel}" +
                " 坏ID/${outcome.skipped.badTargetId} 无现价/${outcome.skipped.noPrice}，" +
                "整轮失败平台 ${outcome.failedPlatformCount}"
        )
        // 任一平台整轮失败 → retry，WorkManager 按退避策略重排，不影响 30 分钟周期；
        // 仅"平台没有查价能力"不算失败（重试也不会有结果，交给说明通知）
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
