package com.pricelens.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §2.4 入场动效纯判据：阶梯延迟封顶 + "一次入场"防重播守卫。
 *
 * 防重播是这批最容易做错的点：`key = 数据` 会让 AnimatedContent/remember 每次数据变化都重播，
 * 所以守卫只认**与数据无关的稳定 key**，且一个 key 只放行一次。这里把这条判据钉死。
 */
class MotionEnterTest {

    private val step = 40
    private val cap = 8

    @Test
    fun `delay climbs one step per index up to the cap`() {
        for (i in 0 until cap) {
            assertEquals(i * step, MotionEnter.staggerDelay(i, step, cap))
        }
    }

    @Test
    fun `delay stops accumulating past the cap so long lists do not wait half a second`() {
        val ceiling = (cap - 1) * step
        assertEquals(ceiling, MotionEnter.staggerDelay(cap, step, cap))
        assertEquals(ceiling, MotionEnter.staggerDelay(cap + 1, step, cap))
        assertEquals(ceiling, MotionEnter.staggerDelay(400, step, cap))
    }

    @Test
    fun `delay is monotone non decreasing and bounded`() {
        var prev = -1
        for (i in 0..200) {
            val delay = MotionEnter.staggerDelay(i, step, cap)
            assertTrue("索引 $i 的延迟回退了", delay >= prev)
            assertTrue("索引 $i 的延迟 $delay 超过封顶", delay <= (cap - 1) * step)
            prev = delay
        }
    }

    @Test
    fun `negative index is clamped to the first step`() {
        assertEquals(0, MotionEnter.staggerDelay(-3, step, cap))
    }

    @Test
    fun `cap of one means no stagger at all`() {
        assertEquals(0, MotionEnter.staggerDelay(0, step, 1))
        assertEquals(0, MotionEnter.staggerDelay(9, step, 1))
    }

    @Test
    fun `guard releases a key exactly once`() {
        val guard = EnterReplayGuard()
        assertTrue(guard.acquire("price:block:curve"))
        assertFalse("同 key 二次入场不得再淡（骨架→内容只播一次）", guard.acquire("price:block:curve"))
        assertFalse(guard.acquire("price:block:curve"))
    }

    @Test
    fun `guard keys are independent so each block still enters once`() {
        val guard = EnterReplayGuard()
        assertTrue(guard.acquire("a"))
        assertTrue(guard.acquire("b"))
        assertFalse(guard.acquire("a"))
        assertTrue(guard.hasRevealed("a"))
        assertFalse(guard.hasRevealed("c"))
    }

    @Test
    fun `scroll recycling re acquiring the same item key never replays`() {
        // 模拟 LazyColumn 复用：同一稳定 key 的条目滚出再滚回
        val guard = EnterReplayGuard()
        val itemKey = "identity:100012043978"
        assertTrue(guard.acquire(itemKey))
        repeat(20) { assertFalse(guard.acquire(itemKey)) }
    }

    @Test
    fun `two crossfade layers always sum to full opacity`() {
        for (i in 0..10) {
            val p = i / 10f
            assertEquals(1f, MotionEnter.contentAlpha(p) + MotionEnter.skeletonAlpha(p), 1e-6f)
        }
        assertEquals(0f, MotionEnter.contentAlpha(-1f), 1e-6f)
        assertEquals(1f, MotionEnter.contentAlpha(2f), 1e-6f)
        assertEquals(1f, MotionEnter.skeletonAlpha(-1f), 1e-6f)
        // 位移系数与内容进度相反：进度到 1 时位移归零（落位）
        assertEquals(1f, MotionEnter.enterTravel(-1f), 1e-6f)
        assertEquals(0f, MotionEnter.enterTravel(2f), 1e-6f)
    }
}
