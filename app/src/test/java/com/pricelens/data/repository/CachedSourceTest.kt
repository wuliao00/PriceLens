package com.pricelens.data.repository

import com.pricelens.data.cache.TLRUCache
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CachedSource] 三级缓存模板行为基线（阶段3）。
 * 全部用假 L2（内存 Map）+ 纯字符串编解码器（避开 org.json 的 Android 桩），
 * 时钟注入虚拟时间；验证：L1 命中 / L2 新鲜复用 / 写回 / 失败降级返回旧快照 / 源降级跳过。
 */
class CachedSourceTest {

    private class Clock(var t: Long = 0L) {
        val read: () -> Long get() = { t }
    }

    private object PlainCodec : CacheCodec<String> {
        override fun encode(value: String) = value
        override fun decode(raw: String): String? = raw
    }

    private object ListCodec : CacheCodec<List<String>> {
        override fun encode(value: List<String>) = value.joinToString(",")
        override fun decode(raw: String): List<String> = if (raw.isEmpty()) emptyList() else raw.split(",")
    }

    /** 假 L2：key → (value, cachedAt) */
    private class FakeL2 {
        val store = HashMap<String, Pair<Any?, Long>>()
    }

    private class Harness(
        val clock: Clock = Clock(),
        val health: SourceHealth = SourceHealth(3, 60_000, clock.read),
        val tracker: StaleTracker = StaleTracker(),
        val cache: TLRUCache<String> = TLRUCache(now = clock.read),
        val hub: RevalidateHub = RevalidateHub(),
        val l2: FakeL2 = FakeL2()
    )

    private fun Harness.source(
        key: String = "k",
        ttl: Long = 1_000,
        fetch: suspend () -> String?,
        cacheable: (String) -> Boolean = { true }
    ) = CachedSource(
        cache = cache, health = health, tracker = tracker, hub = hub,
        key = key, ttlMs = ttl, codec = PlainCodec, source = "test",
        fetch = fetch,
        l2Load = {
            @Suppress("UNCHECKED_CAST")
            l2.store[key]?.let { (it.first as String) to it.second }
        },
        l2Save = { v -> l2.store[key] = v to clock.t },
        cacheable = cacheable,
        now = clock.read
    )

    @Test
    fun `fetch success is served and written back to L1 and L2`() = runTest {
        val h = Harness()
        var fetches = 0
        val src = h.source(fetch = {
            fetches++
            "v1"
        })

        assertEquals("v1", src.get())
        assertEquals("v1", src.get()) // L1 命中
        assertEquals(1, fetches)
        assertEquals("v1" to 0L, h.l2.store["k"])
        assertFalse(h.tracker.stale.value.contains("k"))
    }

    @Test
    fun `fresh L2 snapshot is served without touching network`() = runTest {
        val h = Harness()
        h.l2.store["k"] = "snap" to 500L
        var fetches = 0
        val src = h.source(ttl = 1_000, fetch = {
            fetches++
            "live"
        })

        h.clock.t = 900 // 500+1000 > 900 → 仍新鲜
        assertEquals("snap", src.get())
        assertEquals(0, fetches)
    }

    @Test
    fun `expired L2 plus fetch failure returns stale snapshot and marks stale`() = runTest {
        val h = Harness()
        h.l2.store["k"] = "old" to 0L
        val src = h.source(ttl = 1_000, fetch = { null })

        h.clock.t = 2_000 // L2 已陈旧
        assertEquals("old", src.get())
        assertTrue("降级返回旧数据必须标记 stale", h.tracker.stale.value.contains("k"))
    }

    @Test
    fun `no snapshot and fetch failure returns null without stale mark`() = runTest {
        val h = Harness()
        val src = h.source(fetch = { null })
        assertNull(src.get())
        assertTrue(h.tracker.stale.value.isEmpty())
    }

    @Test
    fun `degraded source skips network and falls back to stale snapshot`() = runTest {
        val h = Harness()
        h.l2.store["k"] = "old" to 0L
        repeat(3) { h.health.recordFailure("test") } // 触发降级
        var fetches = 0
        val src = h.source(ttl = 1_000, fetch = {
            fetches++
            "live"
        })

        h.clock.t = 2_000
        assertEquals("old", src.get())
        assertEquals("降级期内不得发起网络请求", 0, fetches)
        assertTrue(h.tracker.stale.value.contains("k"))
    }

    @Test
    fun `successful fetch after failures restores health and clears stale`() = runTest {
        val h = Harness()
        h.l2.store["k"] = "old" to 0L
        h.health.recordFailure("test")
        h.health.recordFailure("test")
        val src = h.source(ttl = 1_000, fetch = { "live" })

        h.clock.t = 2_000
        assertEquals("live", src.get())
        assertEquals(0, h.health.failureCount("test"))
        assertFalse(h.tracker.stale.value.contains("k"))
    }

    @Test
    fun `non-cacheable result is neither stored nor served from L1`() = runTest {
        val h = Harness()
        var fetches = 0
        val src = CachedSource(
            cache = h.cache, health = h.health, tracker = h.tracker, hub = h.hub,
            key = "list", ttlMs = 1_000, codec = ListCodec, source = "test",
            fetch = {
                fetches++
                emptyList<String>()
            },
            cacheable = { it.isNotEmpty() },
            now = h.clock.read
        )

        assertEquals(emptyList<String>(), src.get())
        assertEquals(emptyList<String>(), src.get())
        assertEquals("空结果不缓存：第二次仍走网络", 2, fetches)
        assertNull(h.cache.get("list"))
        assertTrue(h.l2.store.isEmpty())
    }

    @Test
    fun `refresh bypasses L1 and updates caches on success`() = runTest {
        val h = Harness()
        var fetches = 0
        var payload = "v1"
        val src = h.source(fetch = {
            fetches++
            payload
        })
        assertEquals("v1", src.get())

        payload = "v2"
        src.refresh()
        assertEquals(2, fetches)
        assertEquals("v2" to 0L, h.l2.store["k"])
    }
}
