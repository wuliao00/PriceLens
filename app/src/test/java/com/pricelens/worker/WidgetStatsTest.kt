package com.pricelens.worker

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 小组件 / 磁贴快照的纯逻辑口径（用户优化文档 §七）。
 *
 * 覆盖三件事：
 *  1. 累计降价（dropped）只增不减 —— 口径 = 累计发出降价提醒的次数；
 *  2. 一轮收尾的快照合并 —— watching 归零可、累计数不清零、没有完成时刻时不许把 0 当检查时间；
 *  3. "HH:mm" 时间格式化与从未检查的占位符。
 *
 * SharedPreferences（[WidgetStateStore]）、Glance 组合与 QS 磁贴的系统绑定属于 Android
 * 运行时行为：本模块没有 Robolectric/设备，不在 JVM 用例里硬造，由真机核对。
 */
class WidgetStatsTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val root = Locale.ROOT

    // ---------- ① 累计降价：只增不减 ----------

    @Test
    fun `dropped accumulates this round's triggers onto the stored value`() {
        assertEquals(3, WidgetStats.nextDropped(previousDropped = 0, triggeredThisRound = 3))
        assertEquals(7, WidgetStats.nextDropped(previousDropped = 5, triggeredThisRound = 2))
        assertEquals("本轮没有命中时，累计数不动", 5, WidgetStats.nextDropped(previousDropped = 5, triggeredThisRound = 0))
    }

    @Test
    fun `dropped never decreases on corrupt inputs`() {
        assertEquals(
            "负的本轮数按 0 处理，不能把累计数改小",
            5,
            WidgetStats.nextDropped(previousDropped = 5, triggeredThisRound = -2)
        )
        assertEquals("旧的负数赃值先归零，再加本轮", 1, WidgetStats.nextDropped(previousDropped = -9, triggeredThisRound = 1))
    }

    // ---------- ② 一轮收尾的快照合并 ----------

    @Test
    fun `a finished round updates watching dropped and last check time together`() {
        val previous = WidgetSnapshot(watching = 2, dropped = 4, lastCheckAt = 1_000L)
        val next = WidgetStats.afterRound(previous, watching = 3, triggeredThisRound = 2, atMillis = 5_000L)
        assertEquals(3, next.watching)
        assertEquals(6, next.dropped)
        assertEquals(5_000L, next.lastCheckAt)
    }

    @Test
    fun `a round without a timestamp never fakes the check time with zero`() {
        val previous = WidgetSnapshot(watching = 2, dropped = 4, lastCheckAt = 1_000L)
        val next = WidgetStats.afterRound(previous, watching = 0, triggeredThisRound = 0, atMillis = 0L)
        assertEquals("目标清空轮：watching 归零", 0, next.watching)
        assertEquals("累计降价是历史事实，不清零", 4, next.dropped)
        assertEquals("没有完成时刻时保留原时间，不许把 0 写进去", 1_000L, next.lastCheckAt)
    }

    @Test
    fun `a dirty negative watching count is shown as zero`() {
        val next = WidgetStats.afterRound(WidgetSnapshot.EMPTY, watching = -3, triggeredThisRound = 0, atMillis = 5_000L)
        assertEquals(0, next.watching)
    }

    @Test
    fun `stored values are read back with corrupt entries zeroed`() {
        assertEquals(WidgetSnapshot.EMPTY, WidgetStats.snapshotOf(watching = 0, dropped = 0, lastCheckAt = 0L))
        val dirty = WidgetStats.snapshotOf(watching = -1, dropped = -2, lastCheckAt = -3L)
        assertEquals(0, dirty.watching)
        assertEquals(0, dirty.dropped)
        assertEquals(0L, dirty.lastCheckAt)
    }

    // ---------- ③ 时间格式化 ----------

    @Test
    fun `clock text is HH mm in the given zone`() {
        val calendar = Calendar.getInstance(utc, root).apply {
            set(2026, Calendar.OCTOBER, 6, 3, 4, 0)
            set(Calendar.MILLISECOND, 0)
        }
        assertEquals("03:04", WidgetStats.clockText(calendar.timeInMillis, zone = utc, locale = root))
    }

    @Test
    fun `clock text pads single digit hours and minutes`() {
        val calendar = Calendar.getInstance(utc, root).apply {
            set(2026, Calendar.JANUARY, 1, 0, 7, 0)
            set(Calendar.MILLISECOND, 0)
        }
        assertEquals("00:07", WidgetStats.clockText(calendar.timeInMillis, zone = utc, locale = root))
    }

    @Test
    fun `never checked shows a placeholder instead of a fake time`() {
        assertEquals(WidgetStats.NEVER_TEXT, WidgetStats.clockText(0L, zone = utc, locale = root))
        assertEquals(WidgetStats.NEVER_TEXT, WidgetStats.clockText(-5L, zone = utc, locale = root))
    }
}
