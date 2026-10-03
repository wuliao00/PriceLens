package com.pricelens.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浮窗尺寸真相源（[OverlayMetrics]）的钉子。
 *
 * 两类断言各有其**只会红一次**的理由：
 *  1. 等值钉子钉住"搬家前后数值没变" —— `panelWindowMaxWidthDp()` 必须仍是 304，
 *     因为那是 `OverlayManager` 里原来手写死的数（真机窗口尺寸按它算，改了就会裁面板）；
 *     `capsuleCornerDp()` 必须仍是胶囊高度的一半，否则 pill 形变成圆角矩形。
 *  2. 不变量钉子钉住"窗口再窄也不许超过窗口" —— 这是本批要修的僵硬化本身：
 *     旧写法把 280 和"屏宽×0.4"当成绝对值，窄窗口（分屏、折叠半屏）下画出来的内容比窗口宽，
 *     被静默裁掉，而且只在数据最长的时候露出来。
 */
class OverlayMetricsTest {

    @Test
    fun `window width cap is derived from content plus gutters, value unchanged`() {
        assertEquals(PANEL_CONTENT_MAX_DP + WINDOW_GUTTER_DP * 2, panelWindowMaxWidthDp())
        // OverlayManager 以前写死 304f；搬家一个数都不许动
        assertEquals(304, panelWindowMaxWidthDp())
        assertEquals(WINDOW_GUTTER_DP + CAPSULE_MAX_HEIGHT_DP, capsuleBandDp())
        assertEquals(60, capsuleBandDp())
    }

    @Test
    fun `capsule corner stays half its height so it stays a pill`() {
        assertEquals(CAPSULE_MAX_HEIGHT_DP / 2, capsuleCornerDp())
        // 改高度时圆角必须跟着走：这里不是相等而是"永远等于一半"，所以断言上面那条就够，
        // 再加一条防"把圆角写死回 24"的回归
        assertEquals(24, capsuleCornerDp())
    }

    @Test
    fun `capsule shares the panel's width budget so the price is never squeezed to an ellipsis`() {
        // 这条测试**曾经钉着的是病灶**：旧规则 `min(280, 屏宽×0.4)` 在 360dp 手机上给 144dp，
        // 而"页面价 ¥11,579 + 收起 + ×"需要 ~200dp ⇒ 真机把价格挤成一个"…"
        // （2026-10-04 PLB110 实拍 shots/detail_after_back.png）。当时这条是**绿的**。
        // 现在折叠态与展开态共用 [panelContentMaxWidthDp]：手机本档拿到的是设计上限 280。
        assertEquals(PANEL_CONTENT_MAX_DP, capsuleMaxWidthDp(360))
        assertEquals(PANEL_CONTENT_MAX_DP, capsuleMaxWidthDp(1280))
        // 窗口真的窄时跟着收（分屏/折叠半屏），与面板同一条曲线，不留两个版本
        assertEquals(216, capsuleMaxWidthDp(240))
        assertEquals(capsuleMaxWidthDp(240), panelContentMaxWidthDp(240))
        assertEquals(capsuleMaxWidthDp(360), panelContentMaxWidthDp(360))
        // 再窄也留可读下限，而不是算出 0 或负数
        assertEquals(120, capsuleMaxWidthDp(120))
    }

    @Test
    fun `panel never asks for more width than the window can show`() {
        assertEquals(PANEL_CONTENT_MAX_DP, panelContentMaxWidthDp(1280))
        // 320dp 屏：窗口能给出 320−2×12=296dp，比设计上限 280 还宽 ⇒ 仍按 280 排（不放大）
        assertEquals(PANEL_CONTENT_MAX_DP, panelContentMaxWidthDp(320))
        // 分屏 / 折叠半屏窄到装不下 280 时，才跟着窗口收：内容宽 = 窗口宽 − 左右外圈
        assertEquals(216, panelContentMaxWidthDp(240))
        // 再窄也留一条可读下限，而不是算出 0 或负数
        assertEquals(120, panelContentMaxWidthDp(120))
        assertEquals(120, panelContentMaxWidthDp(60))
    }

    @Test
    fun `invariants hold across every plausible window width`() {
        for (width in 60..2000 step 7) {
            val panel = panelContentMaxWidthDp(width)
            val capsule = capsuleMaxWidthDp(width)
            // 面板 + 左右外圈不得超过窗口；只有"下限兜底"那一档允许顶破（窗口本来就装不下 120dp）
            assertTrue("w=$width 面板 $panel 超出窗口", panel + WINDOW_GUTTER_DP * 2 <= width || panel == 120)
            assertTrue("w=$width 胶囊 $capsule 比面板 $panel 还宽", capsule <= panel)
            assertTrue("w=$width 算出 panel=$panel capsule=$capsule", panel >= 120 && capsule >= 120)
        }
    }
}
