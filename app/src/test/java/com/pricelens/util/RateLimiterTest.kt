package com.pricelens.util

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RateLimiter] / [UserAgents] 行为基线（阶段0 测试安全网）。
 *
 * 该类无 Android 框架依赖（纯 kotlinx.coroutines + System.currentTimeMillis），
 * 可在 JVM 单测运行。但节流间隔直接读真实墙钟、无法注入虚拟时钟，
 * 故采用"缩短间隔 + 真实计时"的方式验证等待逻辑（总耗时保持在百毫秒级）。
 */
class RateLimiterTest {

    @Test
    fun `withLimit returns block result`() = runBlocking {
        val limiter = RateLimiter()
        assertEquals("ok", limiter.withLimit("jd.com") { "ok" })
    }

    @Test
    fun `block returning null is propagated as null`() = runBlocking {
        val limiter = RateLimiter()
        assertNull(limiter.withLimit("jd.com") { null })
    }

    @Test
    fun `penalized domain refused without invoking block until penalty expires`() = runBlocking {
        val limiter = RateLimiter(penaltyMs = 120)
        assertFalse(limiter.isPenalized("jd.com"))
        limiter.penalize("jd.com")
        assertTrue(limiter.isPenalized("jd.com"))
        var invoked = false
        assertNull(
            limiter.withLimit("jd.com") {
                invoked = true
                "ok"
            }
        )
        assertFalse(invoked) // 熔断期内不发起请求
        Thread.sleep(150)
        assertFalse(limiter.isPenalized("jd.com"))
        assertEquals("ok", limiter.withLimit("jd.com") { "ok" })
    }

    @Test
    fun `consecutive calls on same domain wait at least minInterval`() = runBlocking {
        val limiter = RateLimiter(minIntervalMs = 200)
        val start = System.nanoTime()
        limiter.withLimit("jd.com") { 1 }
        limiter.withLimit("jd.com") { 2 }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        // 留 50ms 余量防计时抖动；第二次调用前必须等待接近一个完整间隔
        assertTrue("期望 ≥150ms，实际 ${elapsedMs}ms", elapsedMs >= 150)
    }

    @Test
    fun `different domains do not wait for each other`() = runBlocking {
        val limiter = RateLimiter(minIntervalMs = 10_000)
        val start = System.nanoTime()
        limiter.withLimit("a.com") { 1 }
        limiter.withLimit("b.com") { 2 }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("期望 <1000ms，实际 ${elapsedMs}ms", elapsedMs < 1000)
    }

    @Test
    fun `concurrent in-flight domains capped by semaphore`() = runBlocking {
        val limiter = RateLimiter(maxConcurrentDomains = 2, minIntervalMs = 0)
        val running = AtomicInteger()
        val maxSeen = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val jobs = (1..3).map { i ->
            async(Dispatchers.Default) {
                limiter.withLimit("d$i.com") {
                    running.incrementAndGet().also { n -> maxSeen.accumulateAndGet(n) { a, b -> maxOf(a, b) } }
                    try {
                        gate.await()
                    } finally {
                        running.decrementAndGet()
                    }
                    "ok"
                }
            }
        }
        // 等待前两个占满名额（第 3 个应阻塞在信号量上）
        withTimeout(3000) { while (running.get() < 2) kotlinx.coroutines.delay(10) }
        kotlinx.coroutines.delay(100) // 给第 3 个协程足够的机会，验证它确实被挡住
        assertEquals(2, running.get())
        gate.complete(Unit)
        val results = jobs.awaitAll()
        assertEquals(listOf("ok", "ok", "ok"), results)
        assertEquals(0, running.get())
        assertEquals(2, maxSeen.get()) // 任意时刻并发不超过 2
    }

    @Test
    fun `user agents rotate through pool of 5 with period 5`() {
        val first = (1..5).map { UserAgents.next() }
        assertEquals("UA 池应有 5 个互不相同的 UA", 5, first.toSet().size)
        val second = (1..5).map { UserAgents.next() }
        assertEquals("轮换周期应为 5", first, second)
    }
}
