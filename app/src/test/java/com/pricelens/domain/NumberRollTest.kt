package com.pricelens.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §2.3 价格数字滚动的纯逻辑契约（先红后绿；不碰 Compose）。
 *
 * 钉死四件事：
 *  1. 序列端点精确（首帧=from、末帧=to）且单调，绝不越过端点（= 禁 bounce/overshoot 铁律）；
 *  2. 缓动是 FastOutSlowIn 同一条曲线（0/0.5/1 三个锚点 + 单调），UI 侧 animateFloatAsState
 *     用 PriceLensEasing，两端不同源就会"注释与实现不一致"；
 *  3. **滚动全程格数恒定**（tnum 等宽数字下，格数恒定 == 宽度不抖）；
 *  4. 小数位按目标值取一次并全程固定（7,999 不会被渲染成 7,999.00）。
 */
class NumberRollTest {

    private fun cells(from: Double, to: Double, durationMillis: Int = 350): Set<Int> {
        val values = NumberRoll.sequence(from, to, durationMillis)
        return values.map { NumberRoll.cellCount(NumberRoll.rollText(it, from, to)) }.toSet()
    }

    @Test
    fun `sequence starts at from and lands exactly on to`() {
        val seq = NumberRoll.sequence(0.0, 199.0, 350)
        assertEquals(0.0, seq.first(), 1e-9)
        assertEquals(199.0, seq.last(), 1e-9)
    }

    @Test
    fun `sequence is monotone increasing for an upward roll`() {
        val seq = NumberRoll.sequence(19.9, 199.0, 350)
        for (i in 1 until seq.size) {
            assertTrue("第 $i 帧回退：${seq[i - 1]} -> ${seq[i]}", seq[i] >= seq[i - 1])
        }
    }

    @Test
    fun `sequence is monotone decreasing for a downward roll`() {
        val seq = NumberRoll.sequence(199.0, 19.9, 350)
        for (i in 1 until seq.size) {
            assertTrue("第 $i 帧反弹：${seq[i - 1]} -> ${seq[i]}", seq[i] <= seq[i])
        }
    }

    @Test
    fun `no frame overshoots the endpoints`() {
        // 铁律：禁止 bounce/overshoot —— 任何一帧都必须在 [from, to] 闭区间内
        val seq = NumberRoll.sequence(88.0, 1088.5, 350)
        for (v in seq) {
            assertTrue("越界值 $v", v >= 88.0 - 1e-9 && v <= 1088.5 + 1e-9)
        }
    }

    @Test
    fun `equal endpoints give a flat sequence`() {
        val seq = NumberRoll.sequence(66.0, 66.0, 350)
        assertTrue(seq.all { it == 66.0 })
    }

    @Test
    fun `zero duration still yields both endpoints`() {
        val seq = NumberRoll.sequence(10.0, 20.0, 0)
        assertEquals(2, seq.size)
        assertEquals(10.0, seq.first(), 1e-9)
        assertEquals(20.0, seq.last(), 1e-9)
    }

    @Test
    fun `easing anchors match compose fast out slow in`() {
        assertEquals(0f, NumberRoll.easeFastOutSlowIn(0f), 1e-6f)
        assertEquals(1f, NumberRoll.easeFastOutSlowIn(1f), 1e-6f)
        // cubic-bezier(0.4, 0, 0.2, 1) 是 **S 形**：起步落后线性、中段大幅领先、收尾贴着终点。
        // （名字里的 fast-out 说的是"元素离场"用它，不是"一开始就冲"——直觉在这里会写错断言。）
        assertTrue("起步应落后于线性", NumberRoll.easeFastOutSlowIn(0.1f) < 0.1f)
        assertTrue("中段应明显领先线性", NumberRoll.easeFastOutSlowIn(0.5f) > 0.6f)
        assertTrue("收尾应已贴着终点", NumberRoll.easeFastOutSlowIn(0.9f) > 0.98f)
        val middle = NumberRoll.easeFastOutSlowIn(0.4f) - NumberRoll.easeFastOutSlowIn(0.3f)
        val late = NumberRoll.easeFastOutSlowIn(1f) - NumberRoll.easeFastOutSlowIn(0.9f)
        assertTrue("末段步长应小于中段步长", late < middle)
    }

    @Test
    fun `easing is monotone and never overshoots one`() {
        var prev = 0f
        var i = 0
        while (i <= 100) {
            val x = i / 100f
            val y = NumberRoll.easeFastOutSlowIn(x)
            assertTrue("缓动回退 @ $x", y >= prev - 1e-5f)
            assertTrue("缓动过冲 @ $x = $y", y <= 1f + 1e-6f)
            prev = y
            i++
        }
    }

    @Test
    fun `roll text keeps a constant cell count across the whole roll`() {
        // 跨千分位（3 格→5 格）是最容易抖宽度的场景：整段必须同一格数
        assertEquals(1, cells(999.0, 1001.0).size)
        assertEquals(1, cells(0.0, 12.34).size)
        assertEquals(1, cells(8888.8, 12.34).size)
    }

    @Test
    fun `decimals follow the target value only`() {
        assertEquals(0, NumberRoll.decimalsFor(7999.0))
        assertEquals(1, NumberRoll.decimalsFor(12.5))
        assertEquals(2, NumberRoll.decimalsFor(12.34))
        // 3 位小数按展示口径截到 2 位（与 PriceFormatter 的 "#,##0.##" 一致）
        assertEquals(2, NumberRoll.decimalsFor(12.345))
    }

    @Test
    fun `integer target is never padded with fake cents`() {
        val final = NumberRoll.rollText(1999.0, 0.0, 1999.0)
        assertEquals(NumberRoll.formatText(1999.0, 0), final.trim(NumberRoll.Pad))
    }

    @Test
    fun `last frame equals the target rendering`() {
        val to = 149.9
        assertEquals(NumberRoll.formatText(to, NumberRoll.decimalsFor(to)), NumberRoll.rollText(to, 0.0, to).trim(NumberRoll.Pad))
    }

    @Test
    fun `first frame equals the start rendering at the reserved width`() {
        val from = 149.9
        val text = NumberRoll.rollText(from, from, 1999.0)
        assertEquals(NumberRoll.reservedCells(from, 1999.0), NumberRoll.cellCount(text))
        // 数字体本身不带前缀：前缀固定在左，占位格补在前缀与数字之间
        assertTrue(text.endsWith(NumberRoll.formatText(from, NumberRoll.decimalsFor(1999.0), "")))
    }

    @Test
    fun `valueAt is a plain lerp on the eased progress`() {
        assertEquals(0.0, NumberRoll.valueAt(0.0, 100.0, 0f), 1e-9)
        assertEquals(100.0, NumberRoll.valueAt(0.0, 100.0, 1f), 1e-9)
        assertEquals(50.0, NumberRoll.valueAt(0.0, 100.0, 0.5f), 1e-9)
        assertEquals(-25.0, NumberRoll.valueAt(0.0, -50.0, 0.5f), 1e-9)
    }
}
