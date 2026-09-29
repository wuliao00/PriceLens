package com.pricelens.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 不可见字符清洗（2026-09-30 真机自 dump 取证）。
 *
 * 真机京东商详页的主标题节点文本是
 * `"\u200B \u200B 茅台飞天 53%vol 500ml 贵州茅台酒（带杯）【需预约购买】"`
 * —— 京东用 U+200B 零宽空格做防爬填充。它**不是** Unicode 空白字符，
 * `String.trim()`（按 `Char.isWhitespace()`）与正则 `\s` 都不认，所以旧实现里
 * 这串零宽字符一路活到下游：
 *  - 浮窗 CTA「识别到标题 · 点击在 App 内搜索」把整串当关键词丢进搜索，
 *    缓存 key（`gwd:coupon:$keyword`）与 [com.pricelens.util.QueryRelevance] 分词全部错位；
 *  - [PriceNodeMatcher.isPlausibleTitle] 的长度带被顶偏（30 个零宽字符能让短标题过 strict
 *    阈值，也能让长标题撞上 80 上限被拒）；
 *  - 底栏按钮文案一旦被同样填充，门控的 contains("立即预约") 会静默失配 → 浮窗又不弹。
 *
 * 源码里一律用 `\\u200B` 转义写，不放字面不可见字符（看不见就没法 review）。
 */
class InvisibleTextSanitizingTest {

    private val zwsp = "\u200B"

    /** 真机实际形态：若干零宽空格 + 一个普通空格 + 真标题 */
    private val paddedTitle = "$zwsp$zwsp$zwsp $zwsp$zwsp$zwsp 茅台飞天 53%vol 500ml 贵州茅台酒（带杯）【需预约购买】"

    @Test
    fun `cleanTitle strips zero width padding and keeps the real title`() {
        val cleaned = PriceNodeMatcher.cleanTitle(paddedTitle)
        assertEquals("茅台飞天 53%vol 500ml 贵州茅台酒（带杯）【需预约购买】", cleaned)
        assertFalse("清洗后不许残留零宽空格", cleaned!!.contains(zwsp))
    }

    @Test
    fun `cleanTitle strips bidi controls and word joiners too`() {
        val raw = "\uFEFF\u200C\u200D茅台\u200E 飞天"
        assertEquals("茅台 飞天", PriceNodeMatcher.cleanTitle(raw))
    }

    @Test
    fun `extractTitle returns sanitized text for a zero-width padded detail page`() {
        val hit = extractTitle(paddedDetailPage(), ShopPlatform.JD)
        assertTrue("三级启发式应能认出这条标题", hit != null)
        assertEquals("茅台飞天 53%vol 500ml 贵州茅台酒（带杯）【需预约购买】", hit!!.text)
    }

    @Test
    fun `gate still matches a buy action padded with zero width chars`() {
        val texts = subtreeTexts(paddedDetailPage())
        assertTrue("子树文本必须已清洗，否则门控 contains 静默失配", texts.any { it == "立即预约" })
        assertTrue(isProductPage(paddedDetailPage(), ShopPlatform.JD))
    }

    @Test
    fun `cleanTitle keeps ordinary spaces and collapses runs of them`() {
        assertEquals("茅台 飞天 500ml", PriceNodeMatcher.cleanTitle("  茅台  ${zwsp}飞天   500ml  "))
    }

    /** 与真机同形的商详页：主价 + 零宽填充的标题 + 逐字插了零宽空格底栏按钮 */
    private fun paddedDetailPage(): NodeSnapshot = container(
        kids = arrayOf(
            leaf(text = "¥1759"),
            leaf(text = paddedTitle),
            leaf(text = "${zwsp}立${zwsp}即${zwsp}预${zwsp}约$zwsp", clickable = true)
        )
    )
}
