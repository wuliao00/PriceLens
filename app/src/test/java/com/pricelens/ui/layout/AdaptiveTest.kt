package com.pricelens.ui.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 窗口分档与内容限宽的钉子（[Adaptive]）。
 *
 * 两类断言的目的不同，别合并成一条：
 *  - **边界**：600 / 840 是 Material 3 的官方分界，钉住"下界这一侧算下一档"这种最容易写反的
 *    一行（`>=` 写成 `>` 在真机上看不出来，只有跨过 600.0 这一像素时才错）；
 *  - **手机不得受影响**：COMPACT 的宽度上限必须是 `null` 而不是"一个大数"，
 *    因为 `widthIn(max = 很大)` 与"不写这一条 modifier"在 Compose 里不是等价的
 *    （前者仍会参与约束解析）。这条用例保证真机上已经验收过的排版逐像素不变。
 */
class AdaptiveTest {

    @Test
    fun `material 3 breakpoints are exact and exclusive at the lower edge`() {
        assertEquals(WindowWidthBucket.COMPACT, Adaptive.bucketOf(599.9f))
        assertEquals(WindowWidthBucket.MEDIUM, Adaptive.bucketOf(600f))
        assertEquals(WindowWidthBucket.MEDIUM, Adaptive.bucketOf(839.9f))
        assertEquals(WindowWidthBucket.EXPANDED, Adaptive.bucketOf(840f))
    }

    @Test
    fun `every real device width lands where it should`() {
        // 主流手机竖屏（360/393/411/428dp）全部留在 COMPACT：这批改动不许动它们
        listOf(360f, 393f, 411f, 428f, 500f).forEach {
            assertEquals("w=$it 不该被限宽", WindowWidthBucket.COMPACT, Adaptive.bucketOf(it))
        }
        // 折叠屏展开内屏（Pixel Fold 展开 ≈678dp）与 7 寸平板竖屏：限宽 600 单栏
        listOf(678f, 720f).forEach {
            assertEquals("w=$it", WindowWidthBucket.MEDIUM, Adaptive.bucketOf(it))
            assertEquals(Adaptive.CONTENT_MAX_MEDIUM_DP, Adaptive.contentMaxWidthDp(WindowWidthBucket.MEDIUM))
        }
        // 10 寸平板横屏 / 桌面自由窗口：侧栏形态 + 更宽的上限
        listOf(840f, 1080f, 1280f).forEach {
            assertEquals("w=$it", WindowWidthBucket.EXPANDED, Adaptive.bucketOf(it))
        }
    }

    @Test
    fun `compact has no cap at all rather than a huge one`() {
        assertNull(Adaptive.contentMaxWidthDp(WindowWidthBucket.COMPACT))
    }

    @Test
    fun `caps grow with the bucket and never exceed the window they sit in`() {
        val medium = Adaptive.contentMaxWidthDp(WindowWidthBucket.MEDIUM)!!
        val expanded = Adaptive.contentMaxWidthDp(WindowWidthBucket.EXPANDED)!!
        assertTrue("展开档上限 $expanded 应不小于中档 $medium", expanded >= medium)
        // 中档的 600 上限只在 ≥600 的窗口里生效；否则限宽本身会把内容压得比窗口还窄
        assertTrue(medium <= Adaptive.MEDIUM_MIN_DP)
        assertTrue(expanded <= Adaptive.EXPANDED_MIN_DP)
    }
}
