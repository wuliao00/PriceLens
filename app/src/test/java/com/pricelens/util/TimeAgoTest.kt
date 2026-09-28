package com.pricelens.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TimeAgo] 口径测试（浮窗灰脚注"来源 慢慢买 · 3 小时前 · 非实时"必须项）。
 * now 注入固定值，纯函数无时钟依赖。
 */
class TimeAgoTest {

    private val now = 1_790_000_000_000L

    @Test
    fun `within one minute is just now`() {
        assertEquals("刚刚", TimeAgo.format(now - 30_000L, now))
        // 未来时间（设备时钟偏差）不报负数
        assertEquals("刚刚", TimeAgo.format(now + 5_000L, now))
    }

    @Test
    fun `minutes hours days buckets`() {
        assertEquals("5 分钟前", TimeAgo.format(now - 5 * 60_000L - 10L, now))
        assertEquals("59 分钟前", TimeAgo.format(now - 59 * 60_000L - 30L, now))
        assertEquals("3 小时前", TimeAgo.format(now - 3 * 3_600_000L - 1L, now))
        assertEquals("23 小时前", TimeAgo.format(now - 23 * 3_600_000L, now))
        assertEquals("2 天前", TimeAgo.format(now - 2 * 86_400_000L - 1L, now))
        assertEquals("6 天前", TimeAgo.format(now - 6 * 86_400_000L - 1L, now))
    }

    @Test
    fun `older than a week falls back to absolute date`() {
        // 数据时间是必须项：不能用"很久以前"糊弄，超过 7 天给绝对日期
        val ts = now - 40L * 86_400_000L
        assertTrue(Regex("\\d{4}-\\d{2}-\\d{2}").matches(TimeAgo.format(ts, now)))
    }
}
