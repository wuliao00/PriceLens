package com.pricelens.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 降价通知闸门（免打扰时段 + 仅 WiFi）：边界全部钉住 ——
 * 半开区间 [start, end)、跨零点、start == end 视为未设置、两条件叠加、抑制原因可读。
 */
class NotificationGateTest {

    // ---- 免打扰时段 ----

    @Test
    fun `quiet window is half open start inclusive end exclusive`() {
        // 22:00 – 23:00（同一天内）
        assertFalse(NotificationGate.inQuietWindow(21 * 60 + 59, 22 * 60, 23 * 60))
        assertTrue(NotificationGate.inQuietWindow(22 * 60, 22 * 60, 23 * 60))
        assertTrue(NotificationGate.inQuietWindow(22 * 60 + 59, 22 * 60, 23 * 60))
        assertFalse(NotificationGate.inQuietWindow(23 * 60, 22 * 60, 23 * 60))
    }

    @Test
    fun `quiet window wraps over midnight`() {
        val start = 22 * 60 // 22:00
        val end = 8 * 60 // 08:00
        assertTrue("睡前 22:30 在时段内", NotificationGate.inQuietWindow(22 * 60 + 30, start, end))
        assertTrue("跨零点后的 00:10 仍在时段内", NotificationGate.inQuietWindow(10, start, end))
        assertTrue("早上 07:59 仍在时段内", NotificationGate.inQuietWindow(7 * 60 + 59, start, end))
        assertFalse("08:00 结束边界外", NotificationGate.inQuietWindow(8 * 60, start, end))
        assertFalse("中午不在时段内", NotificationGate.inQuietWindow(12 * 60, start, end))
    }

    @Test
    fun `start equal end means window not set instead of all day`() {
        assertFalse(NotificationGate.inQuietWindow(0, 600, 600))
        assertFalse(NotificationGate.inQuietWindow(24 * 60, 600, 600))
    }

    @Test
    fun `decide reports quiet hours before wifi gate`() {
        val d = NotificationGate.decide(
            nowMinuteOfDay = 23 * 60,
            quietHoursEnabled = true,
            quietStartMinute = 22 * 60,
            quietEndMinute = 8 * 60,
            wifiOnlyEnabled = true,
            onWifi = false
        )
        assertFalse(d.allowed)
        assertEquals("免打扰时段", d.reason)
    }

    // ---- 仅 WiFi ----

    @Test
    fun `wifi only suppresses on mobile and allows on wifi`() {
        val suppressed = NotificationGate.decide(10 * 60, false, 0, 0, wifiOnlyEnabled = true, onWifi = false)
        assertFalse(suppressed.allowed)
        assertEquals("仅 WiFi 提醒（当前非 WiFi）", suppressed.reason)

        val allowed = NotificationGate.decide(10 * 60, false, 0, 0, wifiOnlyEnabled = true, onWifi = true)
        assertTrue(allowed.allowed)
    }

    @Test
    fun `quiet disabled and wifi off always allows`() {
        val d = NotificationGate.decide(3 * 60, false, 22 * 60, 8 * 60, wifiOnlyEnabled = false, onWifi = false)
        assertTrue(d.allowed)
        assertEquals(null, d.reason)
    }

    @Test
    fun `minute of day normalizes out of range input`() {
        // 越界值先归一化再判：25:00 → 01:00（落在 22:00–08:00 窗口内）；-12:00 → 12:00（窗口外）
        assertTrue(NotificationGate.inQuietWindow(25 * 60, 22 * 60, 8 * 60))
        assertFalse(NotificationGate.inQuietWindow(-12 * 60, 22 * 60, 8 * 60))
    }
}
