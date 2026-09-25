package com.pricelens.data.remote

import com.pricelens.util.QueryRelevance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 识货搜索解析基线。
 * 回归点：旧实现请求的 `www.shihuo.cn/search?keywords=` 已 302 到首页，
 * `__NEXT_DATA__` 里是首页热榜（adidas 板鞋/洗发水），被当成搜索结果展示。
 * 现解析 m 站真接口 `m.shihuo.cn/search?type=goods`，且结构不符时返回空。
 */
class ShihuoParserTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) { "缺少夹具 $name" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test
    fun `null body yields empty list instead of junk`() {
        // 实况：该接口对匿名请求常返回字面量 null（4 字节）
        assertTrue(ShihuoApi.parseSearchPage(fixture("shihuo_null.json"), "iPhone 15").isEmpty())
    }

    @Test
    fun `error status yields empty list`() {
        assertTrue(ShihuoApi.parseSearchPage(fixture("shihuo_error.json"), "iPhone 15").isEmpty())
    }

    @Test
    fun `goods list is parsed with subsidy flag and search page url`() {
        val items = ShihuoApi.parseSearchPage(fixture("shihuo_search.json"), "iPhone 15")
        assertEquals(4, items.size)
        val iphone17 = items.first()
        assertEquals(7929848L, iphone17.goodsId)
        assertEquals(7499.0, iphone17.price, 0.001)
        assertTrue(iphone17.hasSubsidy)
        assertEquals("Apple/苹果", iphone17.brand)
        assertTrue(iphone17.url.contains("shihuo.cn/search/goods"))
        assertTrue(iphone17.url.contains("iPhone"))
    }

    @Test
    fun `relevance filter drops unrelated hot goods and accessories`() {
        val items = ShihuoApi.parseSearchPage(fixture("shihuo_search.json"), "iPhone 15")
        val relevant = items.filter { QueryRelevance.isRelevant("iPhone 15", it.title) }
        // 夹具含：iPhone17 Pro（机型不符剔除）、iPhone 15 手机（保留）、
        // Nike 板鞋（热榜，剔除）、"适用 iPhone 15 手机壳"（配件前缀，剔除）
        assertEquals(1, relevant.size)
        assertTrue(relevant.first().title.contains("iPhone 15"))
        assertFalse(relevant.any { it.title.contains("Nike") || it.title.contains("手机壳") })
    }

    @Test
    fun `device query with no matching model yields empty rather than other models`() {
        // 只有 iPhone17 Pro 能命中「iPhone 17」，搜 iPhone 15 不该退化到它
        val items = ShihuoApi.parseSearchPage(fixture("shihuo_search.json"), "iPhone 15")
        assertTrue(items.none { QueryRelevance.isRelevant("iPhone 15", it.title) && it.title.contains("iPhone17") })
    }
}
