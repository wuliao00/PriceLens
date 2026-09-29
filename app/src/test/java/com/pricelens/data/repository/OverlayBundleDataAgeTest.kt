package com.pricelens.data.repository

import com.pricelens.data.cache.TLRUCache
import com.pricelens.util.TimeAgo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F3 缺陷二：浮窗脚注的数据时间必须是**数据自己的抓取时刻**，不是 bundle 的组装时刻。
 *
 * 病灶：`overlayBundle` 里 `fetchedAtMs = System.currentTimeMillis()`，而数据全部经
 * [CachedSource] 的 L1/L2 通道取——命中缓存时那份值可能早就是几小时前/几天前抓的，
 * 脚注却说「刚刚」。[StaleTracker] 救不了：它只在**网络/解析失败降级回吐旧快照**时置位，
 * 正常缓存命中（这次压根没联网重取）不在其中，所以「缓存降级」那行也不会出。
 *
 * 修复口径（[bundleFetchedAtMs]）：取本次 bundle 里**最老那份数据**的抓取时刻（多来源 min）。
 * 两条支撑断言：
 *  - L2 快照提升到 L1 时，L1 条目必须带上快照自己的时刻（否则数据年龄被续命成"刚写的"）；
 *  - 任一参与 key 的时间不可知 → 整体判为不可知（null），绝不退回"现在"假装刚抓。
 *
 * 时钟全部注入虚拟时间，不 sleep、不依赖墙钟。
 */
class OverlayBundleDataAgeTest {

    private companion object {
        const val DAY = 86_400_000L
        const val NOW = 1_790_000_000_000L // 固定"现在"，避免断言窗口依赖墙钟
        const val TEN_DAYS = 10L * DAY

        /** 与仓储层 keyAge 探针同一语义：L1 条目写入时刻 + L2 行 cachedAt（都可能缺失） */
        fun candidates(l1CreatedAt: Long?, l2CachedAt: Long?): List<Long?> = listOfNotNull(l1CreatedAt, l2CachedAt)
    }

    private class Clock(var t: Long = NOW) {
        val read: () -> Long get() = { t }
    }

    private object PlainCodec : CacheCodec<String> {
        override fun encode(value: String) = value
        override fun decode(raw: String): String? = raw
    }

    /** 假 L2：key → (序列化值, 抓取时刻) */
    private class FakeL2 {
        val store = HashMap<String, Pair<String, Long>>()
        fun cachedAt(key: String): Long? = store[key]?.second
    }

    private class Harness(
        val clock: Clock = Clock(),
        val health: SourceHealth = SourceHealth(3, 60_000, clock.read),
        val tracker: StaleTracker = StaleTracker(),
        val cache: TLRUCache<String> = TLRUCache(now = clock.read),
        val hub: RevalidateHub = RevalidateHub(),
        val l2: FakeL2 = FakeL2()
    )

    /** ttl 显式放宽到 10 天：[CachedSource] 对 TTL 取值无感，"新鲜窗口内的陈旧命中"是同一条代码路径 */
    private fun Harness.source(key: String, ttl: Long = TEN_DAYS, fetch: suspend () -> String?) = CachedSource(
        cache = cache, health = health, tracker = tracker, hub = hub,
        key = key, ttlMs = ttl, codec = PlainCodec, source = "test",
        fetch = fetch,
        l2Load = { l2.store[key]?.let { it.first to it.second } },
        l2Save = { v -> l2.store[key] = v to clock.t },
        now = clock.read
    )

    /** 复刻仓储层的 keyAge 探针：从 L1/L2 两个存储里问出这个 key 的抓取时刻 */
    private fun Harness.probe(): suspend (String) -> List<Long?> = { key ->
        candidates(cache.peek(key)?.createdAt, l2.cachedAt(key))
    }

    // ---------- 支撑断言：L2 提升不许把数据年龄洗成"刚写的" ----------

    @Test
    fun `fresh L2 snapshot promoted into L1 keeps its own fetch time`() = runTest {
        val h = Harness()
        val key = "mmb:history:https://item.jd.com/123456.html"
        val fetchedAgo = NOW - 3 * DAY // 三天前抓的
        h.l2.store[key] = "旧曲线" to fetchedAgo
        var fetches = 0
        val src = h.source(key, fetch = {
            fetches++
            "新曲线"
        })

        assertEquals("旧曲线", src.get())
        assertEquals("新鲜窗口内命中缓存，本轮不该联网", 0, fetches)
        assertEquals(
            "L1 条目的 createdAt 是这份数据的年龄凭证，提升时必须带快照自己的时刻",
            fetchedAgo,
            h.cache.peek(key)!!.createdAt
        )
    }

    // ---------- 主断言：三天前的缓存命中，脚注不许说"刚刚" ----------

    @Test
    fun `three day old cache hit must not be reported as just now`() = runTest {
        val h = Harness()
        val key = "mmb:history:https://item.jd.com/987654.html"
        h.l2.store[key] = "旧曲线" to (NOW - 3 * DAY)
        var fetches = 0
        val src = h.source(key, fetch = {
            fetches++
            "新曲线"
        })
        src.get()

        val age = bundleFetchedAtMs(listOf(key), h.probe(), NOW)!!
        assertEquals("必须等于快照自己的抓取时刻", NOW - 3 * DAY, age)
        assertEquals("脚注时间槽", "3 天前", TimeAgo.format(age, NOW))
        assertNotEquals("绝不出现刚刚", "刚刚", TimeAgo.format(age, NOW))
        assertTrue("本轮没联网重取", fetches == 0)
        assertFalse(
            "stale 标记在此场景是 false：正常缓存命中不在降级集合里，靠它兜不住这个谎",
            h.tracker.stale.value.contains(key)
        )
    }

    // ---------- 聚合口径 ----------

    @Test
    fun `oldest contributing key wins across sources`() = runTest {
        val h = Harness()
        val fresh = "gwd:coupon:某商品"
        val old = "dd:search:某商品"
        h.l2.store[old] = "当当旧快照" to (NOW - 3 * DAY)
        h.cache.put(fresh, "券", TEN_DAYS) // 本次刚抓的
        assertEquals(
            "多来源取 min：脚注按最老那份说",
            NOW - 3 * DAY,
            bundleFetchedAtMs(listOf(fresh, old), h.probe(), NOW)!!
        )
    }

    @Test
    fun `unknown age yields null instead of pretending now`() = runTest {
        val h = Harness()
        // 贡献了数据但两个存储里都问不到时刻（L1 被淘汰、L2 行被清理）
        assertNull(bundleFetchedAtMs(listOf("sh:search:某商品"), h.probe(), NOW))
        // 部分 key 时间不可知 → 整体不可知（绝不拿知道的那几个凑出一个"最新"）
        val known = "dd:search:某商品"
        h.l2.store[known] = "快照" to (NOW - 2 * DAY)
        assertNull(bundleFetchedAtMs(listOf(known, "sh:search:某商品"), h.probe(), NOW))
    }

    @Test
    fun `empty bundle has no data time at all`() = runTest {
        val h = Harness()
        assertNull(bundleFetchedAtMs(emptyList(), h.probe(), NOW))
    }

    @Test
    fun `oldest candidate wins when L1 and L2 disagree`() = runTest {
        // L1 带着提升时继承的时刻、L2 行更老 → 保守取更老者
        val h = Harness()
        val key = "mmb:history:https://item.jd.com/777777.html"
        h.cache.put(key, "值", TEN_DAYS, createdAtMs = NOW - DAY)
        h.l2.store[key] = "值" to (NOW - 5 * DAY)
        assertEquals(NOW - 5 * DAY, bundleFetchedAtMs(listOf(key), h.probe(), NOW)!!)
    }
}
