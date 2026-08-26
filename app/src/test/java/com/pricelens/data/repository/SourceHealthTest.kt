package com.pricelens.data.repository

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SourceHealth] 行为基线（阶段3：连续失败计数 + 冷却降级）。
 * 时钟经构造参数注入，纯虚拟时间，无真实等待。
 */
class SourceHealthTest {

    private class Clock(var t: Long = 0L) {
        val read: () -> Long get() = { t }
    }

    private fun health(clock: Clock, threshold: Int = 3, cooldown: Long = 1000) = SourceHealth(threshold, cooldown, clock.read)

    @Test
    fun `failures below threshold do not degrade`() {
        val h = health(Clock())
        h.recordFailure("jd")
        h.recordFailure("jd")
        assertFalse(h.isDegraded("jd"))
        assertEquals(2, h.failureCount("jd"))
    }

    @Test
    fun `reaching threshold degrades source for cooldown window`() = runTest {
        val clock = Clock()
        val h = health(clock)
        repeat(3) { h.recordFailure("jd") }
        assertTrue(h.isDegraded("jd"))
        assertEquals(1000L, h.remainingCooldownMs("jd"))
        assertEquals(0, h.failureCount("jd")) // 进入降级后计数清零重算
    }

    @Test
    fun `success resets failure count and lifts degradation`() {
        val clock = Clock()
        val h = health(clock)
        h.recordFailure("jd")
        h.recordFailure("jd")
        h.recordSuccess("jd")
        assertEquals(0, h.failureCount("jd"))
        h.recordFailure("jd")
        h.recordFailure("jd")
        assertFalse("成功后失败计数应重新累计", h.isDegraded("jd"))
    }

    @Test
    fun `degradation expires after cooldown with lazy cleanup`() {
        val clock = Clock()
        val h = health(clock)
        repeat(3) { h.recordFailure("bili") }
        assertTrue(h.isDegraded("bili"))
        clock.t = 1001
        assertFalse(h.isDegraded("bili"))
        assertEquals(0, h.remainingCooldownMs("bili"))
        assertEquals(0, h.failureCount("bili"))
    }

    @Test
    fun `sources are tracked independently`() {
        val h = health(Clock())
        repeat(3) { h.recordFailure("smz") }
        assertTrue(h.isDegraded("smz"))
        assertFalse(h.isDegraded("bili"))
    }
}
