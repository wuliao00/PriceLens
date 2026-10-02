package com.pricelens.ui.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §十一 商品详情页的三条纯逻辑：评测关键词派生、发布时间窗口、B 站跳转目标选择。
 *
 * 时间基准（UTC，秒）—— 用例里的窗口起点都是 `date -u -d @<秒>` 可独立核对的常量：
 *  - [NOW]            = 2026-06-15T12:00:00Z
 *  - [HALF_YEAR_START] = 2025-12-15T12:00:00Z（NOW 往前整 6 个日历月）
 *  - [YEAR_START]     = 2025-06-15T12:00:00Z（NOW 往前整 12 个日历月）
 */
class ProductDetailLogicTest {

    private val now = 1_781_524_800L
    private val halfYearStart = 1_765_800_000L
    private val yearStart = 1_749_988_800L

    // ---------- reviewKeyword：噪声剥离 ----------

    @Test
    fun `marketing words and bracket prefix are stripped`() {
        assertEquals("Apple iPhone 17 Pro", reviewKeyword("【京东自营】Apple iPhone 17 Pro 正品包邮"))
    }

    @Test
    fun `combined capacity and standalone color token are stripped`() {
        assertEquals("小米15 手机", reviewKeyword("小米15 12GB+256GB 黑色 手机"))
    }

    @Test
    fun `size code token is stripped while model number survives`() {
        assertEquals("李宁 赤兔9", reviewKeyword("李宁 赤兔9 42码 黑色"))
    }

    @Test
    fun `full width bracket content is stripped`() {
        assertEquals("戴森 V12 Detect Slim", reviewKeyword("戴森 V12 Detect Slim（国行正品）"))
    }

    @Test
    fun `zero width padding does not survive and specs are dropped`() {
        val keyword = reviewKeyword("荣耀\u2060Magic8\u200B\u200D 12GB+512GB 星空黑")
        for (invisible in charArrayOf('\u200B', '\u2060', '\u200D')) {
            assertFalse("零宽字符 U+%X 没删净：$keyword".format(invisible.code), keyword.contains(invisible))
        }
        assertEquals("荣耀Magic8", keyword)
    }

    @Test
    fun `packaging counters and variant suffix tokens are stripped`() {
        assertEquals("抽纸 家用", reviewKeyword("抽纸 12包3层 家用 标准版"))
    }

    @Test
    fun `link text alone yields no review keyword`() {
        // 链接里只有 SKU 数字，拿去 B 站搜"评测"必然搜到无关内容 —— 宁可空也不猜
        assertEquals("", reviewKeyword("https://item.jd.com/100012043978.html"))
        assertEquals("", reviewKeyword("100012043978"))
    }

    @Test
    fun `blank and all-noise input yield empty string instead of raw text`() {
        assertEquals("", reviewKeyword("   "))
        assertEquals("", reviewKeyword("【现货】正品 包邮"))
    }

    @Test
    fun `whitespace is compressed and truncation stays on token boundary`() {
        assertEquals("A B", reviewKeyword("A   B"))
        val long = reviewKeyword("商品 " + "x".repeat(30) + " " + "y".repeat(30))
        assertTrue("限长 40 没生效：len=${long.length}", long.length <= 40)
        assertEquals("整词保留，绝不截半型号 token", "商品 " + "x".repeat(30), long)
    }

    // ---------- withinRecentWindow：纯 epoch 秒运算 ----------

    @Test
    fun `window start is inclusive and one second before it is out`() {
        assertTrue(withinRecentWindow(halfYearStart, now, 6))
        assertFalse(withinRecentWindow(halfYearStart - 1, now, 6))
    }

    @Test
    fun `missing pubdate is kept so the filter never invents an empty list`() {
        // B 站搜索接口在部分响应里不返回 pubdate；缺失当"很旧"会让开关一按就清空列表
        assertTrue(withinRecentWindow(0, now, 6))
        assertTrue(withinRecentWindow(-1, now, 6))
    }

    @Test
    fun `today and future timestamps stay inside the window`() {
        assertTrue(withinRecentWindow(now, now, 6))
        assertTrue(withinRecentWindow(now + 86_400L, now, 6))
        assertTrue(withinRecentWindow(now - 30L * 86_400L, now, 6))
    }

    @Test
    fun `a one year old video is outside the six month window`() {
        assertFalse(withinRecentWindow(now - 365L * 86_400L, now, 6))
    }

    @Test
    fun `months actually moves the boundary and zero months means not yet published`() {
        assertFalse(withinRecentWindow(yearStart, now, 6))
        assertTrue(withinRecentWindow(yearStart, now, 12))
        assertFalse(withinRecentWindow(halfYearStart, now, 0))
    }

    // ---------- biliTarget：装了 B 站走深链，否则回落 https ----------

    @Test
    fun `installed app gets a native deep link`() {
        val target = biliTarget("BV1xx411c7mD", appInstalled = true, allowDeepLink = true)
        assertTrue("实际=${target.javaClass.simpleName}", target is BiliTarget.DeepLink)
        assertEquals("bilibili://video/BV1xx411c7mD", target.url)
    }

    @Test
    fun `without the app it falls back to https`() {
        val target = biliTarget("BV1xx411c7mD", appInstalled = false, allowDeepLink = true)
        assertTrue("实际=${target.javaClass.simpleName}", target is BiliTarget.Web)
        assertEquals("https://www.bilibili.com/video/BV1xx411c7mD", target.url)
    }

    @Test
    fun `deep link opt-out keeps web even when the app is installed`() {
        assertTrue(biliTarget("BV1xx411c7mD", appInstalled = true, allowDeepLink = false) is BiliTarget.Web)
    }

    @Test
    fun `blank bvid produces no url so the caller can skip the jump`() {
        assertEquals("", biliTarget("  ", appInstalled = true, allowDeepLink = true).url)
    }
}
