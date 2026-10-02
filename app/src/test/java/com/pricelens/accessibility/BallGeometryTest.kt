package com.pricelens.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PL-29「浮窗收起为小圆球」的纯几何 / 形态状态机单测（[BallGeometry.kt] 零 Android 依赖）。
 *
 * 这些用例钉住的是真机上手最难验的三件事：
 *  - 球不能拖出屏、不能压状态栏 / 导航栏（inset 由调用方给，纯函数不读系统栏）；
 *  - 松手吸附的"最近边"判定必须有一个说得清的居中阈值，退化输入不许乱跳；
 *  - 球上那行极简价格文案在缺数据时不许自造百分数（浮窗的老毛病：把没有的说成有）。
 */
class BallGeometryTest {

    // ---------- clampPosition ----------

    @Test
    fun `in-band position is left untouched`() {
        val (x, y) = clampPosition(120, 400, 100, 1080, 2400, 60, 90)
        assertEquals(120, x)
        assertEquals(400, y)
    }

    @Test
    fun `ball is pinned to the right edge instead of leaving the screen`() {
        val (x, _) = clampPosition(5000, 400, 100, 1080, 2400, 60, 90)
        assertEquals(1080 - 100, x)
    }

    @Test
    fun `negative x clamps to zero not further off-screen`() {
        val (x, _) = clampPosition(-500, 400, 100, 1080, 2400, 60, 90)
        assertEquals(0, x)
    }

    @Test
    fun `ball never covers the status bar`() {
        val (_, y) = clampPosition(120, 0, 100, 1080, 2400, 60, 90)
        assertEquals(60, y)
        val (_, y2) = clampPosition(120, -800, 100, 1080, 2400, 60, 90)
        assertEquals(60, y2)
    }

    @Test
    fun `ball never covers the navigation bar`() {
        // 可用底边 = 2400 - 90(导航栏) - 100(球) = 2210
        val (_, y) = clampPosition(120, 2399, 100, 1080, 2400, 60, 90)
        assertEquals(2210, y)
    }

    @Test
    fun `degenerate short band prefers the top inset over a negative bottom`() {
        // 200 - 90 - 100 = 10 < 60：上下边界互相矛盾（横屏 + 大球）。必须落到 topInset，且绝不返回负数
        val (x, y) = clampPosition(5000, 5000, 100, 1080, 200, 60, 90)
        assertEquals(60, y)
        assertEquals(980, x)
    }

    @Test
    fun `ball bigger than the screen yields zero instead of a negative offset`() {
        // 横向放不下 → 左上角归零（绝不给负数）；纵向仍放得下，按可用底边 2400-90-2000=310 夹住
        val (x, y) = clampPosition(500, 500, 2000, 1080, 2400, 60, 90)
        assertEquals(0, x)
        assertEquals(310, y)
    }

    @Test
    fun `negative insets are ignored rather than shrinking the band from the wrong side`() {
        val (_, y) = clampPosition(120, 5000, 100, 1080, 2400, -100, -200)
        assertEquals(2400 - 100, y)
    }

    // ---------- snapEdge ----------

    @Test
    fun `left of the mid line snaps to the left edge`() {
        assertEquals(Side.Left, snapEdge(100, 100, 1080))
    }

    @Test
    fun `right of the mid line snaps to the right edge`() {
        assertEquals(Side.Right, snapEdge(900, 100, 1080))
    }

    @Test
    fun `ball still straddling the mid line keeps its position`() {
        // 球心 540 正好在中线上：左右等距，没有"最近边"，硬吸附会让球凭空跳一下
        assertEquals(Side.Keep, snapEdge(490, 100, 1080))
    }

    @Test
    fun `threshold is exclusive so a full ball width off centre already snaps`() {
        // 球心 590 = 中线 + 球宽/2：位移等于阈值即判边（阈值语义 = 严格小于才 Keep）
        assertEquals(Side.Right, snapEdge(540, 100, 1080))
        assertEquals(Side.Left, snapEdge(440, 100, 1080))
    }

    @Test
    fun `unusable metrics cannot decide an edge`() {
        assertEquals(Side.Keep, snapEdge(100, 100, 0))
        assertEquals(Side.Keep, snapEdge(100, 100, -1080))
        // 球比屏还宽时无所谓左右，保持原位
        assertEquals(Side.Keep, snapEdge(100, 2000, 1080))
    }

    // ---------- isDrag ----------

    @Test
    fun `movement up to and including the slop is a tap`() {
        assertFalse(isDrag(0f, 8))
        assertFalse(isDrag(7.9f, 8))
        assertFalse(isDrag(8f, 8))
    }

    @Test
    fun `movement beyond the slop is a drag`() {
        assertTrue(isDrag(8.5f, 8))
        assertTrue(isDrag(300f, 8))
    }

    @Test
    fun `garbage slop degrades to any-movement-is-a-drag but never swallows a still finger`() {
        assertFalse(isDrag(0f, -5))
        assertTrue(isDrag(0.5f, -5))
    }

    // ---------- ballLabel ----------

    @Test
    fun `missing or non-positive price yields an empty label`() {
        // 空串 = 交给 UI 用 strings_overlay.xml 的兜底文案，纯函数不产词
        assertEquals("", ballLabel(null, 4900.0))
        assertEquals("", ballLabel(0.0, 4900.0))
        assertEquals("", ballLabel(-5.0, null))
    }

    @Test
    fun `without a usable historical low the label is the price alone`() {
        assertEquals("¥5,499", ballLabel(5499.0, null))
        assertEquals("¥100", ballLabel(100.0, 0.0))
        assertEquals("¥100", ballLabel(100.0, -1.0))
    }

    @Test
    fun `price above the low carries the same ratio the panel line computes`() {
        // (5499-4900)/4900 = 12.2% → 12，与浮窗第②行「当前高 N%」同分子同分母，两处数字不许打架
        assertEquals("¥5,499\n↑12%", ballLabel(5499.0, 4900.0))
    }

    @Test
    fun `price that undercuts the recorded low is shown as a drop`() {
        // (4900-4800)/4900 = 2.04% → 2
        assertEquals("¥4,800\n↓2%", ballLabel(4800.0, 4900.0))
    }

    @Test
    fun `equal prices have nothing to quantify`() {
        assertEquals("¥4,900", ballLabel(4900.0, 4900.0))
    }

    @Test
    fun `a sub-percent gap is not inflated into a percent sign`() {
        // (4920-4900)/4900 = 0.41% → 四舍五入为 0：宁可只出价，也不写「↑0%」
        assertEquals("¥4,920", ballLabel(4920.0, 4900.0))
        assertEquals("¥4,890", ballLabel(4890.0, 4900.0))
    }

    // ---------- OverlayMode 形态状态机 ----------

    @Test
    fun `panel collapses to ball and ball expands back to panel`() {
        assertEquals(OverlayMode.Ball, nextOverlayMode(OverlayMode.Panel, OverlayEvent.COLLAPSE))
        assertEquals(OverlayMode.Panel, nextOverlayMode(OverlayMode.Ball, OverlayEvent.EXPAND))
    }

    @Test
    fun `repeating an event on the wrong form is a no-op`() {
        assertEquals(OverlayMode.Ball, nextOverlayMode(OverlayMode.Ball, OverlayEvent.COLLAPSE))
        assertEquals(OverlayMode.Panel, nextOverlayMode(OverlayMode.Panel, OverlayEvent.EXPAND))
    }

    @Test
    fun `dragging never changes the form`() {
        assertEquals(OverlayMode.Panel, nextOverlayMode(OverlayMode.Panel, OverlayEvent.DRAG))
        assertEquals(OverlayMode.Ball, nextOverlayMode(OverlayMode.Ball, OverlayEvent.DRAG))
    }

    @Test
    fun `collapse and expand gates match the machine`() {
        assertTrue(canCollapse(OverlayMode.Panel))
        assertFalse(canCollapse(OverlayMode.Ball))
        assertTrue(canExpand(OverlayMode.Ball))
        assertFalse(canExpand(OverlayMode.Panel))
    }
}
