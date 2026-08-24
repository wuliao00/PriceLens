package com.pricelens.util

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * §7.1 请求规范：
 *  - 同域名频率 ≤ 1 req / 3s
 *  - 最大并发域名 3
 *  - 单请求超时 10s，重试 1 次
 *  - 反爬 403 → 暂停该域名 5min
 */
class RateLimiter(
    private val minIntervalMs: Long = 3_000,
    private val maxConcurrentDomains: Int = 3,
    private val penaltyMs: Long = 5 * 60_000L,
) {
    private val lastRequestAt = ConcurrentHashMap<String, Long>()
    private val domainMutex = ConcurrentHashMap<String, Mutex>()
    private val domainSemaphore = Semaphore(maxConcurrentDomains)
    private val penalizedUntil = ConcurrentHashMap<String, Long>()

    /** 403 时调用：接下来 5min 拒绝该域名请求 */
    fun penalize(domain: String) {
        penalizedUntil[domain] = System.currentTimeMillis() + penaltyMs
    }

    fun isPenalized(domain: String): Boolean =
        (penalizedUntil[domain] ?: 0) > System.currentTimeMillis()

    /**
     * 在限速与并发约束下执行请求；被熔断/超时返回 null。
     * block 返回 null 视为业务失败（由调用方决定是否重试）。
     */
    suspend fun <T> withLimit(domain: String, block: suspend () -> T?): T? {
        if (isPenalized(domain)) return null
        domainSemaphore.acquire()
        try {
            val mutex = domainMutex.getOrPut(domain) { Mutex() }
            return mutex.withLock {
                val wait = lastRequestAt[domain]?.let {
                    minIntervalMs - (System.currentTimeMillis() - it)
                } ?: 0
                if (wait > 0) {
                    withTimeoutOrNull(wait) {
                        kotlinx.coroutines.delay(wait)
                        true
                    }
                }
                lastRequestAt[domain] = System.currentTimeMillis()
                block()
            }
        } finally {
            domainSemaphore.release()
        }
    }
}

/** §7.1 UA 轮换：5 个真实 Chrome UA */
object UserAgents {
    private val POOL = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    )

    private val counter = java.util.concurrent.atomic.AtomicInteger()

    fun next(): String = POOL[counter.getAndIncrement() % POOL.size]
}
