package com.pricelens.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pricelens.data.repository.AutoTrackRepository
import com.pricelens.domain.AutoTrackDecision
import com.pricelens.util.LogT
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * 自动追价链的一次执行（唯一链 `auto_track_chain` 的 Worker）。
 *
 * 流程全部收口在 [AutoTrackRepository.runScheduled]：
 *  1. 先按当前环境做一次调度决策（夜间暂停/低电/仅 WiFi 不满足 → 只重排、不发网络请求）；
 *  2. 真跑一轮（复用 [WatchCheckRunner]：关键词同款搜索 + 直查 + 通知闸门 + 曲线）；
 *  3. 跑完按新输入再决策，经 [AutoTrackScheduler] REPLACE 排下一轮。
 *
 * 返回 `Result.success()` 是刻意的：链的间隔由自适应决策决定，**不能**交给 WorkManager 的
 * 指数退避（否则一次失败会把下一轮推迟几小时，用户看到的是"自动追价停了"）。
 * 异常时兜底排 30 分钟后重试，保证链不断。
 */
@HiltWorker
class AutoTrackWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: AutoTrackRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val outcome = repository.runScheduled()
            LogT.i("自动追价链完成：${outcome.note}")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 链不能因为一次异常断掉：兜底排 30 分钟后再来（日志用 e 级，Release 也可观测）
            LogT.e("自动追价链执行失败（已兜底重排）：${e.javaClass.simpleName} ${e.message}", e)
            runCatching {
                repository.applyPlan(
                    AutoTrackDecision(shouldRun = false, nextDelayMinutes = 30, reason = "上一轮异常，30 分钟后重试")
                )
            }.onFailure { LogT.e("自动追价链兜底重排也失败：${it.javaClass.simpleName}", it) }
            Result.success()
        }
    }
}
