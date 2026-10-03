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

    // ---------- ballDropPosition（松手那一下吸附；拖动途中走下面的 ballDragPosition） ----------

    @Test
    fun `drag past the middle snaps to the right edge`() {
        val (x, y) = ballDropPosition(
            x = 500, y = 400, dx = 600f, dy = 100f, ballSize = 224,
            screenWidth = 1256, screenHeight = 2760, topInset = 140, bottomInset = 0
        )
        assertEquals(1256 - 224, x)
        assertEquals(500, y)
    }

    @Test
    fun `small drag keeps the position instead of snapping`() {
        val (x, y) = ballDropPosition(
            x = 500, y = 400, dx = 20f, dy = 0f, ballSize = 224,
            screenWidth = 1256, screenHeight = 2760, topInset = 140, bottomInset = 0
        )
        assertEquals(520, x)
        assertEquals(400, y)
    }

    /** 判别例：拖出下边界必须被 inset 夹住（球压导航栏是真机上最难看的错） */
    @Test
    fun `drag beyond the bottom is clamped above the nav bar`() {
        val (x, y) = ballDropPosition(
            x = 0, y = 2000, dx = 0f, dy = 5000f, ballSize = 224,
            screenWidth = 1256, screenHeight = 2760, topInset = 140, bottomInset = 120
        )
        assertEquals(0, x)
        assertEquals(2760 - 120 - 224, y)
    }

    @Test
    fun `drag above the top inset is clamped below the status bar`() {
        val (_, y) = ballDropPosition(
            x = 300, y = 400, dx = 0f, dy = -5000f, ballSize = 224,
            screenWidth = 1256, screenHeight = 2760, topInset = 140, bottomInset = 0
        )
        assertEquals(140, y)
    }

    // ---------- ballDragPosition（拖动每一帧：只夹取，绝不吸附） ----------

    /**
     * 判别例：同一组入参下，拖动中**不许**吸附、松手才吸附。
     *
     * 取 x=800 是因为它已经出了 [snapEdge] 的中线死区（死区是球心距中线 ±ballSize/2 = ±112；
     * 球心 800+112=912 距中线 628 有 284，判 Right）。若拖动途中就吸附，用户会看到球在手指
     * 还在走的时候突然跳去贴边 —— 这条把两种行为分开钉住：
     * ballDragPosition 给 800（跟着手指），ballDropPosition 给贴边的 1032。
     */
    @Test
    fun `live drag follows the finger without snapping`() {
        val (x, y) = ballDragPosition(
            originX = 100, originY = 100, dx = 700f, dy = 300f, ballSize = 224,
            screenWidth = 1256, screenHeight = 2760, topInset = 140, bottomInset = 0
        )
        assertEquals(800, x)
        assertEquals(400, y)
        val (dropX, dropY) = ballDropPosition(
            x = 800, y = 400, dx = 0f, dy = 0f, ballSize = 224,
            screenWidth = 1256, screenHeight = 2760, topInset = 140, bottomInset = 0
        )
        assertEquals("同一个位置松手才应该吸到右边缘", 1256 - 224, dropX)
        assertEquals("纵向不参与吸附", 400, dropY)
    }

    @Test
    fun `live drag is clamped at the edges but stays put horizontally`() {
        val (x, y) = ballDragPosition(
            originX = 900, originY = 400, dx = 900f, dy = 0f, ballSize = 224,
            screenWidth = 1256, screenHeight = 2760, topInset = 140, bottomInset = 0
        )
        assertEquals("拖出右边界只夹住，不等松手就贴边", 1256 - 224, x)
        val top = ballDragPosition(
            originX = 300, originY = 400, dx = 0f, dy = -5000f, ballSize = 224,
            screenWidth = 1256, screenHeight = 2760, topInset = 140, bottomInset = 0
        )
        assertEquals("状态栏那一条不能拖进去", 140, top.second)
    }

    /** 起点锚定：同一帧位移配不同起点就该差同样的距离（逐帧累加会漂移的那种写法过不了这条） */
    @Test
    fun `live drag position depends only on origin plus delta`() {
        val a = ballDragPosition(
            originX = 100, originY = 500, dx = 250f, dy = 0f, ballSize = 224,
            screenWidth = 1256, screenHeight = 2760, topInset = 140, bottomInset = 0
        )
        val b = ballDragPosition(
            originX = 120, originY = 500, dx = 250f, dy = 0f, ballSize = 224,
            screenWidth = 1256, screenHeight = 2760, topInset = 140, bottomInset = 0
        )
        assertEquals(350, a.first)
        assertEquals(370, b.first)
    }

    // ---------- 收窗时机与建窗形态（真机"球闪烁 + 变回胶囊条"那一条） ----------

    @Test
    fun `ball defers teardown while panel tears down at once`() {
        assertTrue(shouldDeferTeardown(OverlayMode.Ball))
        assertFalse(shouldDeferTeardown(OverlayMode.Panel))
    }

    @Test
    fun `new window follows the user's last explicit form`() {
        assertEquals(OverlayMode.Ball, initialForm(userCollapsed = true))
        assertEquals(OverlayMode.Panel, initialForm(userCollapsed = false))
    }
}
