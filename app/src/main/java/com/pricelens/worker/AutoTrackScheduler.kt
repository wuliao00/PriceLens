package com.pricelens.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.pricelens.data.repository.SettingsRepository
import com.pricelens.util.LogT
import java.util.concurrent.TimeUnit

/**
 * 自动追价的自适应调度器（一次性唯一链，唯一名 [UNIQUE_CHAIN] = `auto_track_chain`）。
 *
 * 为什么用「OneTimeWorkRequest + 完成后再排下一轮」而不是 PeriodicWorkRequest：
 *  - 自适应：每轮结束后按 [com.pricelens.domain.AutoTrackPolicy.decideNext] 的决策
 *    （最近目标距离/夜间/电量/WiFi/用户档位）决定下一轮间隔，周期任务做不到"这轮 30 分钟、下轮 180 分钟"；
 *  - 唯一性：全程只用一个 unique work name，`REPLACE` 排下一轮 = 队列里永远最多一个待跑轮次；
 *  - 没有双轮次：WorkManager 同名 unique work 不会并行，进程内再由
 *    [WatchCheckRunner] 的 Mutex 兜底（服务/磁贴/兜底 Worker 走同一把锁）。
 *
 * 约束：网络可用 + 电量不低；开了「仅 WiFi 追价」时再加 UNMETERED（系统会等 WiFi 再唤醒，
 * 比醒来再判断更省电）。真实决策仍由 Worker 里的 [AutoTrackWorker] 用 `decideNext` 复核。
 *
 * 所有状态（下一轮预计时刻）写回 [SettingsRepository.autoTrackNextRunAtMs]，UI 直接读。
 */
object AutoTrackScheduler {

    /** 自适应链的唯一名（整个 App 只此一个；改它等于把在跑的老链丢弃，不要随意改） */
    const val UNIQUE_CHAIN: String = "auto_track_chain"

    /** 链任务的 tag（排查用：`adb shell dumpsys jobscheduler` / WorkManager 诊断） */
    const val TAG_AUTO_TRACK: String = "auto_track"

    /** 查 WorkManager 状态的等待上限：查询本身是本地数据库读，2 秒足够；超时按"链在跑"保守处理 */
    private const val QUERY_TIMEOUT_SECONDS = 2L

    /** 排下一轮：`REPLACE` 保证同名队列里最多一个待跑轮次（自适应间隔在这里生效） */
    fun scheduleNext(context: Context, settings: SettingsRepository, delayMinutes: Int) {
        val delay = delayMinutes.coerceIn(0, MAX_DELAY_MINUTES)
        val request = buildRequest(settings, delay)
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_CHAIN, ExistingWorkPolicy.REPLACE, request)
        settings.autoTrackNextRunAtMs = System.currentTimeMillis() + delay * 60_000L
        LogT.i("自动追价链：下一轮约 $delay 分钟后（unique=$UNIQUE_CHAIN）")
    }

    /**
     * 补登记（应用启动/开机/用户重新开启时）：链已在队列里就保持原计划（KEEP），
     * 否则按用户档位排一轮。关闭自动追价时取消链并清掉预计时刻。
     */
    fun ensureChain(context: Context, settings: SettingsRepository) {
        if (!settings.autoTrackEnabled) {
            cancel(context, settings)
            return
        }
        val delay = settings.autoTrackIntervalMinutes.coerceIn(MIN_DELAY_MINUTES, MAX_DELAY_MINUTES)
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_CHAIN, ExistingWorkPolicy.KEEP, buildRequest(settings, delay))
        if (settings.autoTrackNextRunAtMs <= System.currentTimeMillis()) {
            settings.autoTrackNextRunAtMs = System.currentTimeMillis() + delay * 60_000L
        }
        LogT.i("自动追价链已登记（KEEP，档位 $delay 分钟）")
    }

    /** 用户点「立即运行」：REPLACE 掉待跑轮次、立刻排一轮（不是另开一条链，杜绝并行轮次） */
    fun runNow(context: Context, settings: SettingsRepository) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_CHAIN, ExistingWorkPolicy.REPLACE, buildRequest(settings, 0))
        settings.autoTrackNextRunAtMs = System.currentTimeMillis()
        LogT.i("自动追价链：已安排立即运行")
    }

    /** 取消链（关闭自动追价/没有目标）：同名 unique work 全部取消，预计时刻归零 */
    fun cancel(context: Context, settings: SettingsRepository) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_CHAIN) }
            .onFailure { LogT.w("取消自动追价链失败（下次启动会再试）：${it.javaClass.simpleName}") }
        settings.autoTrackNextRunAtMs = 0L
    }

    /**
     * 链是否在队列里/正在跑（判定"兜底轮次要不要让路"的唯一依据）。
     *
     * 查询失败（WorkManager 尚未初始化等异常）时按"用户开了自动追价就算在跑"保守处理：
     * 宁可让兜底 Worker 跳过一轮，也不能让同一轮重复发通知。
     */
    fun isChainActive(context: Context, settings: SettingsRepository): Boolean = runCatching {
        val infos: List<WorkInfo> = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(UNIQUE_CHAIN)
            .get(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        infos.any { !it.state.isFinished }
    }.getOrElse {
        LogT.w("查询自动追价链状态失败，按开关保守判断：${it.javaClass.simpleName}")
        settings.autoTrackEnabled
    }

    private fun buildRequest(settings: SettingsRepository, delayMinutes: Int): OneTimeWorkRequest {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (settings.autoTrackWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .setRequiresBatteryNotLow(true)
            .build()
        return OneTimeWorkRequestBuilder<AutoTrackWorker>()
            .setInitialDelay(delayMinutes.coerceAtLeast(0).toLong(), TimeUnit.MINUTES)
            .setConstraints(constraints)
            .addTag(TAG_AUTO_TRACK)
            .build()
    }

    /** 链可能的最长间隔（分钟）：与 [com.pricelens.domain.AutoTrackPolicy.MAX_INTERVAL_MINUTES] 对齐 */
    private const val MAX_DELAY_MINUTES = 360

    /** 链的最短间隔（分钟）：与 [com.pricelens.domain.AutoTrackPolicy.MIN_INTERVAL_MINUTES] 对齐 */
    private const val MIN_DELAY_MINUTES = 30
}
