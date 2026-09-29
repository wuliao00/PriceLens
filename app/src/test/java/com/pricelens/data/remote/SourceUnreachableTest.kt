package com.pricelens.data.remote

import com.pricelens.util.RateLimiter
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * F4（2026-09-29）回归用例·其一：源「不可达」不得在解析层被塌成"这商品没结果"。
 *
 * 复现条件与真机一致：搜索 `mate 80` 时该域名根本取不到响应。这里用**真实**
 * `ApiClient` + `RateLimiter.penalize(domain)` 造"本轮去不成"的状态——熔断时
 * `withLimit` 直接返回 null（`RateLimiter.kt:80`），`requestWithLimiterResult`
 * 得到 `CrawlerResult.Blocked`，全程不发起任何网络请求，因此确定、离线、可重复。
 *
 * 本文件只使用修复前就存在的公开签名（`searchXxx(keyword): List<T>`），
 * 因此在未改动的实现上可编译、且必然失败——这就是"红"。
 * 断言刻意写成"必须冒泡为失败"而不是"必须返回某类型"：
 * 静默 `emptyList()` 让下游把徽标写成「正常」、文案写成「未匹配到关键词」，是缺陷本身。
 */
class SourceUnreachableTest {

    private val tmpCache = File.createTempFile("pl-f4-cache", "").let {
        it.delete(); File(it.absolutePath).also { f -> f.mkdirs() }
    }

    /** 目标域名处于熔断期 → 该域名的所有请求在限速闸口就被拒绝 */
    private fun blockedClient(domain: String): ApiClient {
        val limiter = RateLimiter(minIntervalMs = 0)
        limiter.penalize(domain)
        return ApiClient(limiter, tmpCache)
    }

    /** 运行解析类，返回"是否冒泡了失败" */
    private suspend fun probe(block: suspend () -> List<*>): Result<List<*>> =
        runCatching { block() }

    private fun assertBubbled(name: String, outcome: Result<List<*>>) {
        val failure = outcome.exceptionOrNull()
        if (failure == null) {
            fail(
                "$name：源不可达必须冒泡为失败，实际静默返回 ${outcome.getOrNull()} " +
                    "（下游会渲染 Success(emptyList) → 徽标「正常」+ 文案「未匹配到关键词」）"
            )
        } else {
            // 取消异常不算"源失败"
            assertTrue(
                "$name：不得把协程取消写成源失败，实际=${failure.javaClass.name}",
                failure !is kotlin.coroutines.cancellation.CancellationException
            )
        }
    }

    @Test
    fun `当当 被拦截时不得静默返回空列表`() = kotlinx.coroutines.test.runTest {
        val api = DangdangApi(blockedClient("search.dangdang.com"))
        assertBubbled("当当", probe { api.searchProducts("mate 80") })
    }

    @Test
    fun `值得买爆料 被拦截时不得静默返回空列表`() = kotlinx.coroutines.test.runTest {
        val api = SmzdmApi(blockedClient("search.smzdm.com"))
        assertBubbled("值得买", probe { api.searchPosts("mate 80") })
    }

    @Test
    fun `找券 被拦截时不得静默返回空列表`() = kotlinx.coroutines.test.runTest {
        val api = GwdangApi(blockedClient("search.smzdm.com"))
        assertBubbled("找券", probe { api.searchCoupons("mate 80") })
    }

    @Test
    fun `识货 被拦截时不得静默返回空列表`() = kotlinx.coroutines.test.runTest {
        val api = ShihuoApi(blockedClient("m.shihuo.cn"))
        assertBubbled("识货", probe { api.searchProducts("mate 80") })
    }

    @Test
    fun `B站 被拦截时不得静默返回空列表`() = kotlinx.coroutines.test.runTest {
        val api = BiliApi(blockedClient("api.bilibili.com"))
        assertBubbled("B站", probe { api.searchVideos("mate 80") })
    }

    @Test
    fun `网络层失败的结局本来就是四态之一（刻画现状：塌缩发生在解析层）`() =
        kotlinx.coroutines.test.runTest {
            // 127.0.0.1:1 立即 connection refused → 真实的 Network 结局，不依赖外网
            val client = ApiClient(RateLimiter(minIntervalMs = 0), tmpCache)
            val result = client.getHtmlResult("http://127.0.0.1:1/nope")
            assertTrue(
                "getHtmlResult 应把连接失败建模为 Network，实际=$result",
                result is CrawlerResult.Network
            )
            // 同一处经旧的可空桥 → null，与"合法空响应体"不可区分：F4 的根因（记录，不是断言新行为）
            assertTrue(client.getHtml("http://127.0.0.1:1/nope") == null)
        }
}
