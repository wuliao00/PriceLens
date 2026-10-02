package com.pricelens.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pricelens.rules.RuleSyncRepository
import com.pricelens.util.LogT
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * 选择器规则同步 Worker（设计文档 §3.3 的调度侧）：
 *  - 冷启动 `enqueueNow` 一次：当天发布的规则第一时间生效；
 *  - 周期 6 小时一次（带网络约束）：App 不打开也能跟上游规则。
 *
 * 与盯价/清理 Worker 同一套 HiltWorker 模式。同步自身绝不抛异常、失败保持旧规则，
 * 所以这里恒返回 success（不做退避重试，避免无网时反复唤醒）。
 */
@HiltWorker
class RuleSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val ruleSync: RuleSyncRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val updated = ruleSync.syncIfNeeded()
        LogT.i("RULES 同步轮次结束 updated=$updated")
        return Result.success()
    }

    companion object {
        private const val UNIQUE_PERIODIC = "rule_sync"
        private const val UNIQUE_NOW = "rule_sync_now"

        private val networkConstraint = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** 冷启动触发一次；KEEP 防重复入队堆叠 */
        fun enqueueNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NOW,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<RuleSyncWorker>()
                    .setConstraints(networkConstraint)
                    .build()
            )
        }

        /** 周期同步（6 小时；KEEP 保证同一时刻只有一条） */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RuleSyncWorker>(6, TimeUnit.HOURS)
                    .setConstraints(networkConstraint)
                    .build()
            )
        }
    }
}
