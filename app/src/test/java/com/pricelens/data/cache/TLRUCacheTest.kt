package com.pricelens.data.cache

import app.cash.turbine.test
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [TLRUCache] 行为基线（阶段0 测试安全网）。
 *
 * v2.4.4 源码实际行为：
 *  - 过期读取 stale-while-revalidate：立即返回旧值 + 异步触发 onStale
 *  - evict 顺序：过期且最久未访问 → 未过期最久未访问（LRU）→ pinned 豁免
 *  - 时钟通过构造参数 now 注入，天然支持虚拟时间
 *  - backgroundScope 为 TestScope 成员属性（runTest 内直接使用，无需 import）
 */
class TLRUCacheTest {

    /** 可控时钟：测试内虚拟时间 */
    private class Clock(var t: Long = 0L) {
        val read: () -> Long get() = { t }
    }

    private val tenBytes = "1234567890"

    @Test
    fun `put then get returns stored value, missing key returns null`() {
        val cache = TLRUCache<String>(now = { 0L })
        cache.put("k", "v")
        assertEquals("v", cache.get("k"))
        assertNull(cache.get("missing"))
    }

    @Test
    fun `sizeBytes tracks put overwrite remove and clear`() {
        val cache = TLRUCache<String>(now = { 0L })
        cache.put("a", "12345")
        assertEquals(5L, cache.sizeBytes())
        cache.put("a", "12") // 覆盖旧值，字节数重算
        assertEquals(2L, cache.sizeBytes())
        cache.put("b", "123")
        assertEquals(5L, cache.sizeBytes())
        cache.remove("a")
        assertEquals(3L, cache.sizeBytes())
        cache.clear()
        assertEquals(0L, cache.sizeBytes())
    }

    @Test
    fun `expired entry still returned then removed by clearExpired`() {
        val clock = Clock()
        val cache = TLRUCache<String>(defaultTtlMs = 1000, now = clock.read)
        cache.put("k", "v")
        clock.t = 1001
        assertEquals("v", cache.get("k")) // stale-while-revalidate：过期仍返回旧值
        cache.clearExpired()
        assertNull(cache.get("k"))
    }

    @Test
    fun `clearExpired keeps unexpired entries`() {
        val clock = Clock()
        val cache = TLRUCache<String>(defaultTtlMs = 1000, now = clock.read)
        cache.put("stale", "s")
        cache.put("fresh", "f", ttlMs = 10_000)
        clock.t = 2000
        cache.clearExpired()
        assertNull(cache.get("stale"))
        assertEquals("f", cache.get("fresh"))
    }

    @Test
    fun `eviction prefers expired entries least recently accessed first`() {
        val clock = Clock()
        val cache = TLRUCache<String>(maxSizeBytes = 20, defaultTtlMs = 1000, now = clock.read)
        cache.put("a", tenBytes) // lastAccessed=0
        clock.t = 100
        cache.put("b", tenBytes) // lastAccessed=100
        clock.t = 2000 // a、b 均已过期
        cache.put("c", tenBytes) // 30 > 20 → 先淘汰过期且最久未访问的 a
        assertNull(cache.get("a"))
        assertEquals(tenBytes, cache.get("b"))
        assertEquals(tenBytes, cache.get("c"))
        assertEquals(20L, cache.sizeBytes())
    }

    @Test
    fun `eviction falls back to pure LRU when nothing expired`() {
        val clock = Clock()
        val cache = TLRUCache<String>(maxSizeBytes = 20, defaultTtlMs = 60_000, now = clock.read)
        cache.put("a", tenBytes)
        clock.t = 10
        cache.put("b", tenBytes)
        clock.t = 20
        cache.get("a") // 访问 a，刷新其 lastAccessed
        clock.t = 30
        cache.put("c", tenBytes) // 30 > 20 → 淘汰最久未访问的 b
        assertNull(cache.get("b"))
        assertEquals(tenBytes, cache.get("a"))
        assertEquals(tenBytes, cache.get("c"))
    }

    @Test
    fun `pinned entries are exempt from eviction`() {
        val clock = Clock()
        val cache = TLRUCache<String>(maxSizeBytes = 20, defaultTtlMs = 1000, now = clock.read)
        cache.put("pin", tenBytes, pinned = true)
        clock.t = 2001 // pin 已过期，但收藏豁免
        cache.put("x", tenBytes)
        clock.t = 2002
        cache.put("y", tenBytes) // 30 > 20 → 过期集合无非收藏项，退化为 LRU 淘汰 x
        assertEquals(tenBytes, cache.get("pin"))
        assertNull(cache.get("x"))
        assertEquals(tenBytes, cache.get("y"))
    }

    @Test
    fun `eviction stops when all remaining entries are pinned`() {
        val cache = TLRUCache<String>(maxSizeBytes = 20, now = { 0L })
        cache.put("a", tenBytes, pinned = true)
        cache.put("b", tenBytes, pinned = true)
        cache.put("c", tenBytes, pinned = true)
        // 30 > 20 但无可用牺牲者：保留全部，不抛异常
        assertEquals(30L, cache.sizeBytes())
        assertNotNull(cache.get("a"))
        assertNotNull(cache.get("b"))
        assertNotNull(cache.get("c"))
    }

    @Test
    fun `pin and unpin toggle eviction protection`() {
        val clock = Clock()
        val cache = TLRUCache<String>(maxSizeBytes = 20, defaultTtlMs = 60_000, now = clock.read)
        cache.put("a", tenBytes)
        cache.pin("a")
        clock.t = 1
        cache.put("b", tenBytes)
        clock.t = 2
        cache.put("c", tenBytes) // 30 > 20 → a 收藏豁免，淘汰 b
        clock.t = 3
        assertNotNull(cache.get("a")) // 刷新 a 的 lastAccessed
        assertNull(cache.get("b"))
        cache.unpin("a")
        clock.t = 4
        cache.put("d", tenBytes) // 30 > 20 → a(3)/c(2)/d(4) 中最久未访问的 c 被淘汰
        assertNull(cache.get("c"))
        assertNotNull(cache.get("a"))
        assertNotNull(cache.get("d"))
    }

    @Test
    fun `stale read triggers async revalidate callback`() = runTest {
        val clock = Clock()
        val events = Channel<String>(Channel.UNLIMITED)
        val cache = TLRUCache<String>(
            defaultTtlMs = 1000,
            onStale = { events.send(it) },
            now = clock.read,
            revalidateScope = backgroundScope
        )
        cache.put("k", "v")
        clock.t = 1001
        assertEquals("v", cache.get("k"))
        events.receiveAsFlow().test {
            assertEquals("k", awaitItem())
            cancel()
        }
    }
}
