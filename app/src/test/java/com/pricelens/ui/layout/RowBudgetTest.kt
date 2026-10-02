package com.pricelens.ui.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 紧凑行高的**派生过程**（不是"看着顺眼就写 60"）。
 *
 * 台账里的教训：上一轮 ListItemHeight 直接写死 100dp，真机上高字号一开就裁字。
 * 这里把三个变量摆到台面上——字号（sp 随 fontScale 放大）、dp 内边距（不放大）、
 * 可用宽度（真机最窄那台）——数字由算式产出，改输入就得先让这条测试变红。
 */
class RowBudgetTest {

    @Test
    fun `真机最窄可用宽`() {
        // 1256px ÷ 3.5 = 358.86dp；两侧 contentPadding 20dp、行自身左右 8dp
        assertEquals(358.86f, RowBudget.DEVICE_WIDTH_DP, 0.01f)
        assertEquals(302.86f, RowBudget.CONTENT_WIDTH_DP, 0.01f)
    }

    @Test
    fun `紧凑行高由最坏字号档推得 60dp`() {
        assertEquals(60, RowBudget.rowHeightDp(RowBudget.ROW_CONTENT_DP, RowBudget.ROW_VPAD_DP, RowBudget.FONT_SCALE_LARGE))
        assertEquals(60, RowBudget.RowCompactDp)
    }

    /** 为什么不是 56dp：超大字号档（1.3）下 24dp 的内容要 32dp，56 会裁掉徽标下沿 */
    @Test
    fun `56dp 在超大字号下不够`() {
        assertEquals(64, RowBudget.rowHeightDp(24, 16, RowBudget.FONT_SCALE_XLARGE))
        assertTrue(RowBudget.RowCompactDp > 56)
    }

    @Test
    fun `行高不低于触控下限`() {
        assertEquals(RowBudget.TOUCH_MIN_DP, RowBudget.rowHeightDp(8, 2, 1f))
    }

    /** 最坏负载：长中文源名 + 时间戳 + 徽标同时取最长 */
    @Test
    fun `固定段最坏 212dp 仍留得下源名`() {
        assertEquals(212f, RowBudget.fixedSlotsDp(1f), 0.01f)
        assertEquals(90.86f, RowBudget.labelWidthDp(), 0.01f)
        assertEquals(6, RowBudget.labelUnits())
        assertTrue(RowBudget.worstCaseFits(RowBudget.CONTENT_WIDTH_DP, RowBudget.FONT_SCALE_LARGE))
    }

    /** 反面用例：宽度不够时诚实报"放不下"，而不是让 Compose 悄悄压扁 */
    @Test
    fun `窄屏固定段放不下`() {
        assertFalse(RowBudget.worstCaseFits(220f, 1f))
        assertTrue(RowBudget.labelWidthDp(200f, 1f) < 0f)
    }

    /** 源名预算不是两处各写一个数字 */
    @Test
    fun `源名预算与报价行同源`() {
        assertEquals(21, RowBudget.lineUnits(14f))
        assertEquals(25, RowBudget.lineUnits(12f))
        assertEquals(6, SourceQuotes.LABEL_UNITS)
        assertEquals(RowBudget.lineUnits(12f), SourceQuotes.STAMP_UNITS)
    }

    @Test
    fun `头卡缩略图与两行标题同高`() {
        // 两行 titleMedium（16sp/22dp 行高）+ 一行现价（20dp）= 64dp
        assertEquals(64, RowBudget.headerThumbDp(2, 22, 20))
    }
}
