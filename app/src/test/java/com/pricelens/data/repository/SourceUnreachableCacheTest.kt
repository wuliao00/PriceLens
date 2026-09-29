package com.pricelens.data.repository

import com.pricelens.data.cache.TLRUCache
import com.pricelens.data.local.CacheTTL
import com.pricelens.data.remote.ApiClient
import com.pricelens.data.remote.DangdangApi
import com.pricelens.util.RateLimiter
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * F4（2026-09-29）回归用例·其二：缓存模板 / 源健康度 / 空态三者在"源不可达"时的分工。
 *
 * 接线 1:1 复制 `PriceRepository.kvSource` 的当当搜索源（key 前缀、TTL、编解码器、
 * `source = "dd"`、`cacheable = { it.isNotEmpty() }`，见 `PriceRepository.kt:167-172,301-329`），
 * `fetch` 就是同一个 `dangdangApi.searchProducts(keyword)`；只有 Room（L2）换成内存 Map——
 * 因为 `PriceRepository` 需要 Room/Hilt 依赖，不在 JVM 单测里实例化。
 * L2 默认留空 = 复现现场的条件（冷启动、无缓存、无网络）。
 *
 * 只用修复前就存在的公开签名 ⇒ 在未改动的实现上可编译；标注「红」的三条今天必失败。
 */
class SourceUnreachableCacheTest {

    private class Clock(var t: Long = 0L) {
        val read: () -> Long get() = { t }
    }

    private val tmpCache = File.createTempFile("pl-f4-l3", "").let {
        it.delete()
        File(it.absolutePath).also { f -> f.mkdirs() }
    }

    /** 真实 DangdangApi，域名处于熔断期：本轮根本去不成 search.dangdang.com */
    private fun blockedDangdangApi(): DangdangApi {
        val limiter = RateLimiter(minIntervalMs = 0)
        limiter.penalize("search.dangdang.com")
        return DangdangApi(ApiClient(limiter, tmpCache))
    }

    private fun item(sku: String, title: String) =
        DangdangApi.DangdangItem(sku, title, 4079.15, null, "https://img3m7.ddimg.cn/x.jpg", "https://product.dangdang.com/$sku.html")

    private class Harness(val clock: Clock = Clock()) {
        val health = SourceHealth(3, 60_000, clock.read)
        val tracker = StaleTracker()
        val cache = TLRUCache<String>(now = clock.read)
        val hub = RevalidateHub()
        val l2 = HashMap<String, Pair<List<DangdangApi.DangdangItem>, Long>>()
    }

    private fun Harness.source(fetch: suspend () -> List<DangdangApi.DangdangItem>) = CachedSource(
        cache = cache, health = health, tracker = tracker, hub = hub,
        key = "dd:search:mate 80", ttlMs = CacheTTL.SMZDM_FEED,
        codec = DangdangItemsCodec, source = "dd",
        fetch = { fetch() },
        l2Load = { l2["dd:search:mate 80"] },
        l2Save = { v -> l2["dd:search:mate 80"] = v to clock.t },
        cacheable = { it.isNotEmpty() },
        now = clock.read
    )

    /** (b) 连续不可达必须让 SourceHealth 真的降级；修复前 `fetch()` 返回空表被记成成功 → 冷却永远触发不了 */
    @Test
    fun `three unreachable rounds degrade the source instead of counting as success`() = runTest {
        val h = Harness()
        val api = blockedDangdangApi()
        val src = h.source { api.searchProducts("mate 80") }

        repeat(3) { runCatching { src.get() } }

        assertTrue(
            "连续 3 轮取不到数据后源应进入降级冷却；实际 failureCount=${h.health.failureCount("dd")} " +
                "degraded=${h.health.isDegraded("dd")}",
            h.health.isDegraded("dd")
        )
        assertTrue("降级冷却应剩余时间", h.health.remainingCooldownMs("dd") > 0)
    }

    /** (a) 本轮没够着、又没有旧快照时，不得把 emptyList 当成功结果交给上层（复现里界面就是这样说「未匹配到」的） */
    @Test
    fun `unreachable source without any snapshot must signal failure, not empty success`() = runTest {
        val h = Harness()
        val api = blockedDangdangApi()
        val src = h.source { api.searchProducts("mate 80") }

        // 前三轮：允许静默兜底（源还没被降级）
        repeat(3) { runCatching { src.get() } }
        // 第四轮已处于冷却期：本轮明确没有访问过数据源
        val outcome = runCatching { src.get() }
        val thrown = outcome.exceptionOrNull()
        if (thrown == null) {
            fail(
                "冷却期取不到数据又无快照时必须冒泡失败；实际返回 ${outcome.getOrNull()} " +
                    "→ SearchViewModel 会写成 Success(emptyList) → 徽标「正常」"
            )
        }
        assertFalse(
            "不得把协程取消当源失败",
            thrown is kotlin.coroutines.cancellation.CancellationException
        )
    }

    /** 同一缺陷的第二条用户可见后果：假的"空结果"把 L2 里真实的旧数据顶掉了 */
    @Test
    fun `unreachable source falls back to the cached snapshot instead of replacing it with empty`() = runTest {
        val h = Harness()
        val snapshot = listOf(item("240001", "华为 Mate 80 手机 12GB+512GB 雪域白"))
        h.l2["dd:search:mate 80"] = snapshot to 0L
        val api = blockedDangdangApi()
        val src = h.source { api.searchProducts("mate 80") }

        h.clock.t = CacheTTL.SMZDM_FEED + 1 // 快照过期 → 走网络 → 走不通
        val outcome = runCatching { src.get() }
        outcome.exceptionOrNull()?.let {
            fail("有旧快照时应降级回吐旧数据而不是抛出：${it.javaClass.simpleName} ${it.message}")
        }
        val value = outcome.getOrNull()

        assertEquals("应回吐 L2 旧快照", snapshot, value)
        assertTrue(
            "降级回吐旧数据必须标记 stale（UI 据此提示「上一次商品数据」）",
            h.tracker.stale.value.contains("dd:search:mate 80")
        )
        assertEquals("连续失败应记在健康度上", 1, h.health.failureCount("dd"))
    }

    /** (c) 护栏：够着了、确实 0 条，仍是合法空结果——不记失败、不降级、不抛 */
    @Test
    fun `reached source with zero results stays a legitimate empty success`() = runTest {
        val h = Harness()
        val src = h.source { emptyList() }

        assertEquals(emptyList<DangdangApi.DangdangItem>(), src.get())
        assertEquals("合法空结果不得计失败", 0, h.health.failureCount("dd"))
        assertFalse("合法空结果不得降级", h.health.isDegraded("dd"))
    }
}
