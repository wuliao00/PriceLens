package com.pricelens.data.repository

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §阶段3 数据源健康度与降级：
 *  - 每源（jd/mmb/bili/gwd/smz/dd/sh）记录连续失败次数
 *  - 连续失败达到 [failureThreshold] → 进入 [cooldownMs] 降级期：
 *    期间 [CachedSource] 跳过该源网络请求，直接返回 L2 陈旧快照（若有）
 *  - 任一次成功即清零失败计数并解除降级
 *
 * 目的：站点改版/反爬风暴时避免反复撞墙拖慢整页，先展示旧数据保可用性。
 * 纯内存即可（重启后重新试探，无需持久化；反爬熔断由 RateLimiter 持久化）。
 */
@Singleton
class SourceHealth(
    private val failureThreshold: Int,
    private val cooldownMs: Long,
    private val now: () -> Long
) {
    /** Hilt 注入点（Dagger 不支持默认参数，用无参次级构造器提供产品默认值） */
    @Inject constructor() : this(3, 2 * 60_000L, System::currentTimeMillis)

    private val consecutiveFailures = ConcurrentHashMap<String, Int>()
    private val degradedUntil = ConcurrentHashMap<String, Long>()

    /** 是否处于降级期（访问时顺带清过期条目，避免无界增长） */
    fun isDegraded(source: String): Boolean {
        val until = degradedUntil[source] ?: return false
        return if (until > now()) {
            true
        } else {
            degradedUntil.remove(source, until)
            consecutiveFailures.remove(source)
            false
        }
    }

    /** 取数成功：清零该源失败计数 */
    fun recordSuccess(source: String) {
        consecutiveFailures.remove(source)
        degradedUntil.remove(source)
    }

    /** 取数失败：累加连续失败，超阈值进入降级冷却 */
    fun recordFailure(source: String) {
        val count = (consecutiveFailures[source] ?: 0) + 1
        if (count >= failureThreshold) {
            degradedUntil[source] = now() + cooldownMs
            consecutiveFailures.remove(source)
        } else {
            consecutiveFailures[source] = count
        }
    }

    /** 距解除降级剩余毫秒（未降级返回 0；诊断/测试用） */
    fun remainingCooldownMs(source: String): Long {
        val until = degradedUntil[source] ?: return 0
        return (until - now()).coerceAtLeast(0)
    }

    /** 当前连续失败次数（诊断/测试用） */
    fun failureCount(source: String): Int = consecutiveFailures[source] ?: 0
}
