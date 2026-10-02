package com.pricelens.domain

import com.pricelens.domain.PriceAdvice.Advice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 购买建议的分位算法（文档 §10）。
 *
 * 边界是重点：分位在 20%/50% 上、样本刚好 8 条、价格非法、历史里混进 0 价 ——
 * 这些地方最容易"差一条就换个结论"。
 */
class PriceAdviceTest {

    private fun series(vararg v: Double) = v.toList()

    /** 10 条：1..10（升序），便于手算分位 */
    private val oneToTen = series(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0)

    @Test
    fun `fewer than eight samples is never judged`() {
        assertEquals(Advice.UNKNOWN, PriceAdvice.advise(1.0, series(1.0, 2.0, 3.0)))
        assertEquals(Advice.UNKNOWN, PriceAdvice.advise(1.0, emptyList()))
        assertNull(PriceAdvice.percentile(1.0, series(1.0, 2.0)))
    }

    @Test
    fun `near the historical lowest is its own verdict`() {
        assertEquals(Advice.HIST_LOW, PriceAdvice.advise(1.0, oneToTen))
        assertEquals("2% 以内仍算接近", Advice.HIST_LOW, PriceAdvice.advise(1.02, oneToTen))
        assertEquals("超过 2% 就交给分位", Advice.GOOD, PriceAdvice.advise(1.03, oneToTen))
    }

    @Test
    fun `percentile bands are inclusive as documented`() {
        // 1.0 是 HIST_LOW，2.0 的分位 = 2/10 = 20% → GOOD
        assertEquals(Advice.GOOD, PriceAdvice.advise(2.0, oneToTen))
        assertEquals(20, PriceAdvice.percentile(2.0, oneToTen))
        // 5.0 与 5.5 的 rank 都是 5/10 = 50% → FAIR（band 是闭区间）
        assertEquals(Advice.FAIR, PriceAdvice.advise(5.0, oneToTen))
        assertEquals(Advice.FAIR, PriceAdvice.advise(5.5, oneToTen))
        // 6.0 → 60% → HIGH
        assertEquals(60, PriceAdvice.percentile(6.0, oneToTen))
        assertEquals(Advice.HIGH, PriceAdvice.advise(6.0, oneToTen))
        assertEquals(100, PriceAdvice.percentile(10.0, oneToTen))
    }

    @Test
    fun `prices above every sample are expensive not unknown`() {
        assertEquals(Advice.HIGH, PriceAdvice.advise(99.0, oneToTen))
    }

    @Test
    fun `illegal prices and zero-filled history never produce a verdict`() {
        assertEquals(Advice.UNKNOWN, PriceAdvice.advise(0.0, oneToTen))
        assertEquals(Advice.UNKNOWN, PriceAdvice.advise(-5.0, oneToTen))
        // 历史里 0 价（迁移前老行/脏数据）要被剔除，不能把"最低价"拉成 0 之后人人都是 HIST_LOW
        val dirty = series(0.0, 0.0, 5.0, 5.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0)
        assertEquals(Advice.HIGH, PriceAdvice.advise(9.5, dirty))
        assertEquals("剔除 0 价后仍按 8 个样本算分位", 0, PriceAdvice.percentile(3.0, dirty))
    }

    @Test
    fun `a flat history makes any equal price the lowest`() {
        val flat = List(10) { 100.0 }
        assertEquals(Advice.HIST_LOW, PriceAdvice.advise(100.0, flat))
    }

    @Test
    fun `current price below all samples is the lowest`() {
        assertEquals(Advice.HIST_LOW, PriceAdvice.advise(0.5, oneToTen))
    }
}
