package com.pricelens.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知迷你曲线的纯几何：
 *  - 少于两个点画不出线（返回 null，调用方回落纯文本）；
 *  - 价格高 → y 小（倒过来，符合"图往上=价高"的直觉）；
 *  - 全部等值画水平中线，不贴顶、不除零。
 */
class NotifyCurveGeometryTest {

    @Test
    fun `less than two points yields null`() {
        assertNull(NotifyCurveGeometry.pathPoints(emptyList(), 100f, 40f))
        assertNull(NotifyCurveGeometry.pathPoints(listOf(9.9), 100f, 40f))
    }

    @Test
    fun `higher price maps to smaller y and x spans the width`() {
        val points = NotifyCurveGeometry.pathPoints(listOf(10.0, 20.0, 15.0), 100f, 40f)!!
        assertEquals(3, points.size)
        assertEquals(0f, points.first().first, 0.001f)
        assertEquals(100f, points.last().first, 0.001f)
        // 20 是最高价 → y 最小（0）；10 是最低价 → y 最大（40）
        assertEquals(40f, points[0].second, 0.001f)
        assertEquals(0f, points[1].second, 0.001f)
        assertEquals(20f, points[2].second, 0.001f)
    }

    @Test
    fun `flat series draws a middle line instead of dividing by zero`() {
        val points = NotifyCurveGeometry.pathPoints(listOf(8.0, 8.0, 8.0), 100f, 40f)!!
        assertTrue(points.all { it.second == 20f })
    }

    @Test
    fun `padding shrinks the drawing area on both sides`() {
        val points = NotifyCurveGeometry.pathPoints(listOf(1.0, 2.0), 100f, 40f, pad = 10f)!!
        assertEquals(10f, points.first().first, 0.001f)
        assertEquals(90f, points.last().first, 0.001f)
    }
}
