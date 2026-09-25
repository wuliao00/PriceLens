package com.pricelens.data.remote

import com.pricelens.util.QueryRelevance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 什么值得买列表解析 + 相关性过滤基线（夹具：2026-09-25 实况页面，已裁剪首 8 条） */
class SmzdmParserTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) { "缺少夹具 $name" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test
    fun `search page items are parsed with price and url`() {
        val posts = SmzdmApi.parseSearchPage(fixture("smzdm_faxian.html"))
        assertEquals(8, posts.size)
        assertTrue(posts.all { it.url.startsWith("https://www.smzdm.com/") })
        // 实况第 3 条：Apple iPhone 17 5G手机 5499 元
        val iphone17 = posts.first { it.title.contains("iPhone 17") }
        assertEquals(5499.0, iphone17.price ?: 0.0, 0.001)
    }

    @Test
    fun `relevance filter keeps only matching device deals`() {
        val posts = SmzdmApi.parseSearchPage(fixture("smzdm_faxian.html"))
        val relevant = posts.filter { QueryRelevance.isRelevant("iPhone 15", it.title) }
        // 实况 8 条里混入：数据线(0.9)、镜头膜(5.99)、小米 15、iPhone 17/Air；
        // 过滤后只应留下标题确含 iPhone 15 的那条（全通用配件标题无配件词，保留）
        assertTrue("过滤后不应为空", relevant.isNotEmpty())
        assertTrue("过滤后不应含其它机型", relevant.none { it.title.contains("iPhone 17") || it.title.contains("Air") })
        assertTrue("过滤后不应含其它品牌", relevant.none { it.title.contains("小米") })
        assertTrue("过滤后不应含配件", relevant.none { it.title.contains("镜头膜") || it.title.contains("数据线") })
    }
}
