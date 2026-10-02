package com.pricelens.worker

/**
 * 「下次检查」的展示口径（文档 UX：盯价页显示下一次检查倒计时）。
 *
 * 事实基线：前台服务与 WorkManager 都是 **30 分钟一轮**的轮询，没有可靠公开接口能查
 * WorkManager 周期任务的确切下次触发时刻（WorkInfo 不暴露 nextScheduleTimeMillis）。
 * 所以这里只给"本进程最近一轮 + 30 分钟"的**估算**，文案一律带「约」；
 * 本进程还没跑过任何一轮时不猜、返回 null。
 */
object WatchCountdown {

    /** 轮询预算：与 WatchForegroundService / PriceCheckWorker 的 30 分钟一致 */
    const val PERIOD_MS = 30 * 60 * 1000L

    /** 下一轮预计时刻；从没跑过 → null */
    fun nextCheckAt(lastRoundAt: Long?): Long? = lastRoundAt?.plus(PERIOD_MS)

    /** 已经到点（还在跑/即将跑）：文案改用"即将进行" */
    fun isDue(nextAt: Long?, nowMs: Long): Boolean = nextAt != null && nowMs >= nextAt
}
