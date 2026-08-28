package com.pricelens.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [judgePrice] 先涨后降判定行为基线（阶段0 测试安全网）。
 *
 * v2.4.4 源码实际规则（util/PriceFormatter.kt §6.2）：
 *  - history 为空 → NORMAL
 *  - current ≤ 历史最低价 × 1.05 → LOW
 *  - current ≥ 最近 7 个采样点均价 × 1.10 → SUSPICIOUS
 *  - 其余 → NORMAL
 */
class PriceJudgmentTest {

    @Test
    fun `empty history falls back to NORMAL`() {
        assertTrue(judgePrice(100.0, emptyList()) is PriceJudgment.NORMAL)
    }

    @Test
    fun `price within 5 percent of historical low is LOW`() {
        // 边界：105 == 100 * 1.05 → LOW（<=）
        assertTrue(judgePrice(105.0, listOf(100.0)) is PriceJudgment.LOW)
        assertTrue(judgePrice(89.0, listOf(100.0, 120.0)) is PriceJudgment.LOW)
    }

    @Test
    fun `price between thresholds is NORMAL`() {
        // lowest=100 → LOW 阈值 105；avg7d=100 → SUSPICIOUS 阈值 110
        assertTrue(judgePrice(106.0, listOf(100.0, 100.0)) is PriceJudgment.NORMAL)
        assertTrue(judgePrice(109.99, listOf(100.0, 100.0)) is PriceJudgment.NORMAL)
    }

    @Test
    fun `price at or above 110 percent of last-7 average is SUSPICIOUS`() {
        // 浮点注意：1.10 的 double 表示 ≈ 1.1000000000000001，故 100*1.10 = 110.00000000000001；
        // current=110.0 实际不命中（v2.4.4 真实行为，名义边界点等效于严格 >），基线如实记录。
        assertTrue(judgePrice(110.0, listOf(100.0, 100.0, 100.0)) is PriceJudgment.NORMAL)
        assertTrue(judgePrice(110.01, listOf(100.0, 100.0, 100.0)) is PriceJudgment.SUSPICIOUS)
        // avg=110 → 阈值 ≈121.00000000000001；取 125 留足余量，且 125 > 105 不落入 LOW
        assertTrue(judgePrice(125.0, listOf(100.0, 120.0)) is PriceJudgment.SUSPICIOUS)
    }

    @Test
    fun `LOW check takes priority over SUSPICIOUS check`() {
        // lowest=90 → LOW 阈值 94.5；avg=95 → SUSPICIOUS 阈值 104.5
        // current=94 命中 LOW；源码 when 分支顺序保证先判 LOW
        assertTrue(judgePrice(94.0, listOf(100.0, 90.0)) is PriceJudgment.LOW)
    }

    @Test
    fun `average uses only the last 7 sampling points`() {
        // 前 3 个 1000 不参与均价：后 7 点均价 = 100 → 阈值 110
        // 若错误地对全部 10 点取均值（370），112 不会命中 SUSPICIOUS
        val history = listOf(1000.0, 1000.0, 1000.0) + List(7) { 100.0 }
        assertTrue(judgePrice(112.0, history) is PriceJudgment.SUSPICIOUS)
    }

    @Test
    fun `judgment labels match UI copy`() {
        assertEquals("≈ 历史低价", PriceJudgment.LOW().label)
        assertEquals("⚠ 疑似先涨后降", PriceJudgment.SUSPICIOUS().label)
        assertEquals("常规价格", PriceJudgment.NORMAL().label)
    }
}
