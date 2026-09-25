package com.pricelens.data.remote

import com.pricelens.util.QueryRelevance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 当当搜索解析基线（夹具：2026-09-25 实况页面，已裁剪首 8 条）。
 * 回归点：旧实现按 `p.price .price_n` 取价，站点改版后该节点消失 → 恒返回 0 条；
 * 现按 `span.search_now_price`（新）→ `.price_n`（旧）→ 价格文本 三级取价。
 */
class DangdangParserTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) { "缺少夹具 $name" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test
    fun `price is parsed from new search_now_price node`() {
        val items = DangdangApi.parseSearchPage(fixture("dangdang_search.html"))
        assertEquals(8, items.size)
        val first = items[0]
        assertEquals("p12368946079", first.skuId)
        assertEquals(214.17, first.price, 0.001)
        assertTrue(first.title.contains("iPhone15"))
        assertTrue(first.url.startsWith("https://product.dangdang.com/"))
        assertTrue(first.image.startsWith("https://"))
    }

    @Test
    fun `original price equal to current is dropped`() {
        // 实况页里"定价：¥214.17"与现价相同，不应展示为划线原价
        val items = DangdangApi.parseSearchPage(fixture("dangdang_search.html"))
        assertNull(items[0].originalPrice)
    }

    @Test
    fun `relevance filter drops cases and guide books for device query`() {
        val items = DangdangApi.parseSearchPage(fixture("dangdang_search.html"))
        val relevant = items.filter { QueryRelevance.isRelevant("iPhone 15", it.title) }
        // 实况首 8 条全是手机壳/保护套/英文说明书——这正是"候选错误"的来源，
        // 过滤后应当为空（候选会退回值得买/识货的过滤结果）。
        assertTrue("期望首 8 条全部被过滤，实际 ${relevant.map { it.title }}", relevant.isEmpty())
    }
}
