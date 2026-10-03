package com.pricelens.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小组件行数分档（[WidgetLayout]）的钉子。
 *
 * 关键的一条是**默认形态不许变**：`pricing_widget_info.xml` 里 `android:minHeight=110dp`
 * 是对用户承诺的 4x2 形态，它必须仍然算出 3 行 —— 否则"自适应"就变成把已经验收过的
 * 小组件改丑了。第二条钉住边界（`>=` 写成 `>` 只在跨过那一 dp 时错，真机上看不出来）。
 */
class WidgetLayoutTest {

    @Test
    fun `declared minimum height still shows all three rows`() {
        val declaredMinHeightDp = 110f
        assertEquals(3, WidgetLayout.rowsFor(declaredMinHeightDp))
        // 110 这条承诺要真的装得下三行，而不是"刚好差一点然后裁第三行"
        assertTrue(
            "minHeight=$declaredMinHeightDp 但三行需要 ${WidgetLayout.contentHeightFor(3)}",
            declaredMinHeightDp >= WidgetLayout.contentHeightFor(3)
        )
    }

    @Test
    fun `row count drops one at a time as the widget gets shorter`() {
        assertEquals(3, WidgetLayout.rowsFor(WidgetLayout.contentHeightFor(3)))
        assertEquals(2, WidgetLayout.rowsFor(WidgetLayout.contentHeightFor(3) - 0.1f))
        assertEquals(2, WidgetLayout.rowsFor(WidgetLayout.contentHeightFor(2)))
        assertEquals(1, WidgetLayout.rowsFor(WidgetLayout.contentHeightFor(2) - 0.1f))
        // 拖成 2x1 那一档（约 55dp）：只剩主行，不再出现"第三行被裁半截"
        assertEquals(1, WidgetLayout.rowsFor(55f))
        assertEquals(1, WidgetLayout.rowsFor(0f))
    }

    @Test
    fun `unknown size keeps the promised form instead of collapsing`() {
        // SizeMode 没给尺寸时 height 是 Dp.Unspecified=NaN；猜成 1 行会把内容收没
        assertEquals(WidgetLayout.DefaultRows, WidgetLayout.rowsFor(Float.NaN))
    }

    @Test
    fun `height requirement grows linearly with rows plus padding twice`() {
        assertEquals(WidgetLayout.VerticalPaddingDp * 2 + WidgetLayout.RowLineDp, WidgetLayout.contentHeightFor(1))
        val step = WidgetLayout.contentHeightFor(3) - WidgetLayout.contentHeightFor(2)
        assertEquals(WidgetLayout.RowLineDp, step)
    }
}
