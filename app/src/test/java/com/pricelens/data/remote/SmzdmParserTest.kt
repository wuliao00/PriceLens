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
    fun `a price highlight with a parenthetical note never fuses into a bigger number`() {
        // 2026-10-02 真机事故原文：值得买 z-highlight 里是「284元（淘金币可抵37.14元起）」，
        // 旧实现把非数字字符全删掉再 toDouble → 28437.14，一副 284 元的耳机显示成 ¥28,437.14。
        assertEquals(284.0, SmzdmApi.parsePriceText("284元（淘金币可抵37.14元起）") ?: 0.0, 0.001)
        assertEquals(209.0, SmzdmApi.parsePriceText("209元（需用券）") ?: 0.0, 0.001)
        assertEquals(226.1, SmzdmApi.parsePriceText("226.1元（需用券）") ?: 0.0, 0.001)
        assertEquals(287.07, SmzdmApi.parsePriceText("287.07元") ?: 0.0, 0.001)
        // 带千分位与货币符、没有「元」的写法
        assertEquals(1299.0, SmzdmApi.parsePriceText("¥1,299.00") ?: 0.0, 0.001)
        // 认不出就不给价（宁可不给，也不给错）
        assertEquals(null, SmzdmApi.parsePriceText("暂无报价"))
        assertEquals(null, SmzdmApi.parsePriceText(""))
        assertEquals(null, SmzdmApi.parsePriceText(null))
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

    @Test
    fun `list page SSR carries zhi votes per deal`() {
        val posts = SmzdmApi.parseSearchPage(fixture("smzdm_faxian.html"))
        val voted = posts.filter { it.positive + it.negative > 0 }
        // 旧实现写"票数需进文章页拉取"于是列表页恒置 0 → 社区页每条都显示「值 0 / 不值 0」。
        // 实况：span.J_zhi_like_fav[data-zhi-type] 里就带着计数，不必多打一次请求。
        assertTrue("列表页 SSR 就带票数，解析结果不应全为 0", voted.isNotEmpty())
        assertTrue("不该出现负票", posts.all { it.positive >= 0 && it.negative >= 0 })
        assertTrue("应解析出夹具里那条 4 值 / 1 不值", voted.any { it.positive == 4 && it.negative == 1 })
    }
}
