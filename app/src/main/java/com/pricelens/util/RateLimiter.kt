package com.pricelens.util

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock

/**
 * 熔断状态持久化抽象（阶段3）：域名 → 解封时间戳。
 * 实现见 data/local/RoomPenaltyStore（Room 小表）；单测/无存储场景传 null。
 */
interface PenaltyStore {
    /** 启动恢复：返回仍在熔断期内的 域名→解封时间戳 */
    suspend fun loadActive(nowMs: Long): Map<String, Long>

    /** 保存/续期某域名的熔断 */
    suspend fun save(domain: String, untilMs: Long)

    /** 清理已解封的历史行（写路径顺带调用，避免小表无界增长） */
    suspend fun cleanup(nowMs: Long) {}
}

/**
 * §7.1 请求规范：
 *  - 同域名频率 ≤ 1 req / 3s
 *  - 最大并发域名 3
 *  - 单请求超时 10s，重试 1 次
 *  - 反爬 403 → 暂停该域名 5min（阶段3：可持久化，重启后恢复）
 *
 * 阶段3 修复：
 *  - 等待间隔只保留一次 delay（原为 withTimeoutOrNull(wait){delay(wait)} 双重等待）
 *  - domainMutex 引用计数，释放归零即移除；penalizedUntil/lastRequestAt
 *    访问时惰性清过期 + 周期性清扫，杜绝无界增长
 *  - 熔断状态经 [PenaltyStore] 持久化，首次 withLimit 时惰性恢复
 */
class RateLimiter(
    private val minIntervalMs: Long = 3_000,
    private val maxConcurrentDomains: Int = 3,
    private val penaltyMs: Long = 5 * 60_000L,
    private val penaltyStore: PenaltyStore? = null,
    private val persistScope: CoroutineScope? = null,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val lastRequestAt = ConcurrentHashMap<String, Long>()
    private val penalizedUntil = ConcurrentHashMap<String, Long>()
    private val domainLocks = ConcurrentHashMap<String, DomainLock>()
    private val domainSemaphore = Semaphore(maxConcurrentDomains)
    private val restored = AtomicBoolean(false)
    private val sweepCounter = AtomicLong()

    /** 域锁引用计数：保证"取出→移除"之间没有持有者，避免竞态下双锁并存 */
    private class DomainLock {
        val mutex = Mutex()
        var inUse = 0 // 由 domainLocks 监视器保护
    }

    /** 403 时调用：接下来 [penaltyMs] 拒绝该域名请求（并持久化） */
    fun penalize(domain: String) {
        val until = now() + penaltyMs
        penalizedUntil[domain] = until
        val store = penaltyStore ?: return
        persistScope?.launch {
            runCatching {
                store.save(domain, until)
                store.cleanup(now())
            }
        }
        sweep(now())
    }

    fun isPenalized(domain: String): Boolean {
        val until = penalizedUntil[domain] ?: return false
        return if (until > now()) {
            true
        } else {
            penalizedUntil.remove(domain, until) // 访问时清过期条目
            false
        }
    }

    /**
     * 在限速与并发约束下执行请求；被熔断/超时返回 null。
     * block 返回 null 视为业务失败（由调用方决定是否重试）。
     */
    suspend fun <T> withLimit(domain: String, block: suspend () -> T?): T? {
        ensureRestored()
        if (isPenalized(domain)) return null
        maybeSweep()
        domainSemaphore.acquire()
        val lock = synchronized(domainLocks) {
            domainLocks.getOrPut(domain) { DomainLock() }.also { it.inUse++ }
        }
        try {
            return lock.mutex.withLock {
                val wait = lastRequestAt[domain]?.let {
                    minIntervalMs - (now() - it)
                } ?: 0
                if (wait > 0) delay(wait) // 阶段3 修复：只等待一次
                lastRequestAt[domain] = now()
                block()
            }
        } finally {
            synchronized(domainLocks) {
                lock.inUse--
                if (lock.inUse == 0) domainLocks.remove(domain, lock) // 无持有者即回收
            }
            domainSemaphore.release()
        }
    }

    /** 熔断表惰性恢复：进程首次过闸时执行一次 */
    private suspend fun ensureRestored() {
        val store = penaltyStore ?: return
        if (!restored.compareAndSet(false, true)) return
        runCatching {
            store.loadActive(now()).forEach { (domain, until) ->
                penalizedUntil[domain] = until
            }
        }.onFailure { restored.set(false) } // 恢复失败允许下次重试
    }

    /** 每 64 次过闸清扫一次：过期熔断 + 久远未访问的节流记录 */
    private fun maybeSweep() {
        if (sweepCounter.incrementAndGet() and 63L == 0L) sweep(now())
    }

    private fun sweep(nowMs: Long) {
        penalizedUntil.entries.removeIf { it.value <= nowMs }
        lastRequestAt.entries.removeIf { nowMs - it.value > STALE_KEEP_MS }
    }

    companion object {
        /** 节流记录保留窗口：远超单次间隔即可（32 × 默认 3s） */
        private const val STALE_KEEP_MS = 96_000L
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
