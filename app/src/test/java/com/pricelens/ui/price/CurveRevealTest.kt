package com.pricelens.ui.price

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §6.2 曲线描线进度的纯几何：progress → 可见部分。
 * 钉的是"入场动画只算数、不改布局"这件事的算法本身：
 * 端点、空/单点退化、单调不减、越界钳制、以及分数段插值不外推。
 */
class CurveRevealTest {

    /** 已描出的段数（可见点数 - 1 + 残段比例），用于判单调 */
    private fun travelled(reveal: CurveReveal): Float = reveal.visiblePoints - 1f + reveal.segmentFraction

    @Test
    fun `progress zero shows only the first point and no stroke segment`() {
        val reveal = curveReveal(0f, 30)
        assertEquals(1, reveal.visiblePoints)
        assertEquals(0f, reveal.segmentFraction, 1e-6f)
    }

    @Test
    fun `progress one shows every point with no leftover segment`() {
        val reveal = curveReveal(1f, 30)
        assertEquals(30, reveal.visiblePoints)
        assertEquals(0f, reveal.segmentFraction, 1e-6f)
    }

    @Test
    fun `empty input draws nothing`() {
        assertEquals(CurveReveal(0, 0f), curveReveal(0f, 0))
        assertEquals(CurveReveal(0, 0f), curveReveal(1f, 0))
        assertEquals(CurveReveal(0, 0f), curveReveal(1f, -5))
    }

    @Test
    fun `single point input never asks for a second point`() {
        // 单点曲线是真实状态（盯价自采只攒到 1 个采样日）：绝不能索引到第 2 点
        assertEquals(CurveReveal(1, 0f), curveReveal(0f, 1))
        assertEquals(CurveReveal(1, 0f), curveReveal(0.5f, 1))
        assertEquals(CurveReveal(1, 0f), curveReveal(1f, 1))
    }

    @Test
    fun `out of range progress is clamped`() {
        assertEquals(curveReveal(0f, 12), curveReveal(-3f, 12))
        assertEquals(curveReveal(1f, 12), curveReveal(7f, 12))
    }

    @Test
    fun `reveal is monotone non decreasing and never exceeds point count`() {
        val points = 24
        var prevVisible = 0
        var prevTravelled = -1f
        var i = 0
        while (i <= 200) {
            val reveal = curveReveal(i / 200f, points)
            assertTrue("可见点数回退", reveal.visiblePoints >= prevVisible)
            assertTrue("描线长度回退", travelled(reveal) >= prevTravelled - 1e-6f)
            assertTrue("可见点数越界", reveal.visiblePoints in 1..points)
            assertTrue("残段比例出格", reveal.segmentFraction in 0f..1f)
            prevVisible = reveal.visiblePoints
            prevTravelled = travelled(reveal)
            i++
        }
    }

    @Test
    fun `fraction advances the stroke head inside the current segment`() {
        // 5 个点 = 4 段；进度 0.375 → 走完 1 段再多半段
        val half = curveReveal(0.375f, 5)
        assertEquals(2, half.visiblePoints)
        assertEquals(0.5f, half.segmentFraction, 1e-6f)
        assertEquals(15.0, segmentPoint(10.0, 20.0, half.segmentFraction), 1e-9)
    }

    @Test
    fun `segment point never extrapolates beyond the two ends`() {
        assertEquals(10.0, segmentPoint(10.0, 20.0, 0f), 1e-9)
        assertEquals(20.0, segmentPoint(10.0, 20.0, 1f), 1e-9)
        assertEquals(10.0, segmentPoint(10.0, 20.0, -1f), 1e-9)
        assertEquals(20.0, segmentPoint(10.0, 20.0, 5f), 1e-9)
        // 零长度段（两点同价）：怎么插都是同一个价，不许被 fraction 带跑
        assertEquals(7.0, segmentPoint(7.0, 7.0, 0.5f), 1e-9)
    }
}
