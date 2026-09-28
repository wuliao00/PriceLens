package com.pricelens.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 相对时间描述（A2 新增，浮窗底部灰脚注"来源 慢慢买 · 3 小时前 · 非实时"用，
 * 概览/设置页亦复用）。纯函数、now 可注入，便于 JVM 单测。
 *
 * 口径：
 *  - 未来时间/1 分钟内 → "刚刚"
 *  - 1 小时内 → "N 分钟前"
 *  - 24 小时内 → "N 小时前"
 *  - 7 天内 → "N 天前"
 *  - 更早 → 绝对日期 "yyyy-MM-dd"（数据时间是必须项，不能用"很久以前"糊弄）
 */
object TimeAgo {

    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 86_400_000L

    fun format(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val diff = nowMs - timestampMs
        if (diff < MINUTE_MS) return "刚刚"
        if (diff < HOUR_MS) return "${diff / MINUTE_MS} 分钟前"
        if (diff < DAY_MS) return "${diff / HOUR_MS} 小时前"
        if (diff < 7 * DAY_MS) return "${diff / DAY_MS} 天前"
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timestampMs))
    }
}
