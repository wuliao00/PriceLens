package com.pricelens.data.repository

import com.pricelens.data.local.DayCurve
import com.pricelens.data.remote.ManmanbuyApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 曲线绘图与脚注的口径一致性（2026-09-30 盯价自采接入后才有这个问题）。
 *
 * 盯价页脚注按 `price_history.source` 统计"这条线有几天是本机自采的、几天是慢慢买给的"，
 * 而线画的是 [CurveMerge.fillMissingDays] 的返回值。两者必须说的是同一批日子：
 * 脚注列了"本机盯价自采 4 天"，图上却只有外源的点，就是一句对不上号的说明
 * （F3 那两条"声明与数据不符"的同类问题）。
 */
class CurveMergeTest {

    private fun ext(vararg pairs: Pair<String, Double>) = pairs.map { ManmanbuyApi.PricePoint(it.first, it.second) }

    private fun self(date: String, close: Double) = DayCurve.Point(
        id = date.hashCode().toLong(),
        date = date,
        close = close,
        dayLow = close,
        source = "SELF_WATCH",
        recordedAt = 0L
    )

    @Test
    fun `self collected days fill the gaps the external curve leaves`() {
        val merged = CurveMerge.fillMissingDays(
            external = ext("2026-09-01" to 100.0, "2026-09-03" to 120.0),
            self = listOf(self("2026-09-02", 110.0))
        )
        assertEquals(
            "外源缺的日子由本机自采补上，脚注说的天数才在图上找得到",
            listOf("2026-09-01" to 100.0, "2026-09-02" to 110.0, "2026-09-03" to 120.0),
            merged.map { it.date to it.price }
        )
    }

    @Test
    fun `external points win on a day both sides have`() {
        val merged = CurveMerge.fillMissingDays(
            external = ext("2026-09-03" to 120.0),
            self = listOf(self("2026-09-03", 999.0))
        )
        assertEquals("同一天只留一个点", 1, merged.size)
        assertEquals("外源（慢慢买）的日线历史优先于本机偶发采样", 120.0, merged.first().price, 0.001)
    }

    @Test
    fun `a curve without any external data is entirely self collected`() {
        val merged = CurveMerge.fillMissingDays(
            external = emptyList(),
            self = listOf(self("2026-09-04", 88.0), self("2026-09-05", 86.0))
        )
        assertEquals(
            "没配慢慢买 Cookie 时曲线也要画得出来",
            listOf("2026-09-04" to 88.0, "2026-09-05" to 86.0),
            merged.map { it.date to it.price }
        )
    }

    @Test
    fun `merged curve is one ascending point per day whatever order it came in`() {
        val merged = CurveMerge.fillMissingDays(
            external = ext("2026-09-06" to 70.0, "2026-09-04" to 90.0),
            self = listOf(self("2026-09-05", 80.0), self("2026-09-04", 85.0))
        )
        assertEquals(listOf("2026-09-04", "2026-09-05", "2026-09-06"), merged.map { it.date })
        assertEquals("日期不能重复", merged.size, merged.map { it.date }.toSet().size)
    }

    @Test
    fun `a zero priced point never joins the curve`() {
        // 0 价上曲线，"历史最低"就恒等于 0 —— 与 collapse 同一道防线，别只信上游
        val merged = CurveMerge.fillMissingDays(
            external = ext("2026-09-04" to 90.0),
            self = listOf(self("2026-09-05", 0.0))
        )
        assertEquals(listOf("2026-09-04"), merged.map { it.date })
    }

    @Test
    fun `no data at all says nothing`() {
        val merged = CurveMerge.fillMissingDays(external = emptyList(), self = emptyList())
        assertTrue("两侧都没有点时返回空表，让上游照常走「暂无历史价」", merged.isEmpty())
    }
}
