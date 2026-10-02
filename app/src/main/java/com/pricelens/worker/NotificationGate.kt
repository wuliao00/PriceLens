package com.pricelens.worker

/**
 * 降价通知的闸门（文档 UX「富通知」）：免打扰时段 + 仅 WiFi。
 *
 * 纯函数（无 Context、无时间 API，调用方传"现在几点/是否 WiFi"），边界全部可单测：
 *  - 免打扰时段是 **[start, end) 半开区间**，跨零点（start > end）自动按两段判断；
 *  - `start == end` 视为未设置该时段（不静默变成"全天免打扰"）；
 *  - 两个条件都满足才放行；被抑制时返回原因（进日志，便于排查"为什么没提醒"）。
 *
 * 口径说明：被抑制只是"这一轮不发"，下一轮（30 分钟后）会重新评估 ——
 * 价格仍达标且出了免打扰时段/连上 WiFi 时照常提醒，不会永久丢。
 */
object NotificationGate {

    const val MINUTES_PER_DAY = 24 * 60

    data class Decision(val allowed: Boolean, val reason: String?) {
        companion object {
            val ALLOW = Decision(true, null)
        }
    }

    fun decide(
        nowMinuteOfDay: Int,
        quietHoursEnabled: Boolean,
        quietStartMinute: Int,
        quietEndMinute: Int,
        wifiOnlyEnabled: Boolean,
        onWifi: Boolean
    ): Decision {
        if (quietHoursEnabled && inQuietWindow(nowMinuteOfDay, quietStartMinute, quietEndMinute)) {
            return Decision(false, "免打扰时段")
        }
        if (wifiOnlyEnabled && !onWifi) {
            return Decision(false, "仅 WiFi 提醒（当前非 WiFi）")
        }
        return Decision.ALLOW
    }

    /** [start, end) 半开区间；跨零点自动两段；start == end 视为未设置 */
    fun inQuietWindow(nowMinute: Int, start: Int, end: Int): Boolean {
        val s = normalize(start)
        val e = normalize(end)
        val n = normalize(nowMinute)
        if (s == e) return false
        return if (s < e) n in s until e else n >= s || n < e
    }

    private fun normalize(minute: Int): Int = ((minute % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
}
