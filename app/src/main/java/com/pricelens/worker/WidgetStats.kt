package com.pricelens.worker

import android.content.Context
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 桌面小组件 / QS 磁贴的持久快照（用户优化文档 §七）。
 *
 * 存 SharedPreferences("watch_state")，三个字段（读写壳收口在 [WidgetStateStore]）：
 *  - watching      盯价中的目标数：每个盯价轮次收尾时取当轮活跃目标数写入；
 *  - dropped       累计降价次数 —— **口径 = 累计发出降价提醒的次数**：每轮把"命中降价"的
 *                  目标逐条发出提醒后按 [nextDropped] 累加，只增不减（是历史事实，目标清空也不清零）；
 *  - last_check_at 最近一轮检查完成时刻（毫秒）；0 = 从未检查，展示 [NEVER_TEXT] 而不是假时间。
 *
 * 计数累加、时间格式化、快照合并全部是**纯函数**（不碰 Android API），可直接 JVM 单测：
 * `app/src/test/java/com/pricelens/worker/WidgetStatsTest.kt`。
 * [WidgetStateStore] 只是 SharedPreferences 的 IO 壳，本身没有可单测的逻辑。
 */
object WidgetStats {

    const val PREFS_NAME = "watch_state"
    const val KEY_WATCHING = "watching"
    const val KEY_DROPPED = "dropped"
    const val KEY_LAST_CHECK_AT = "last_check_at"

    /** 从未检查过的占位：小组件与磁贴都不许把没有的时间编出来 */
    const val NEVER_TEXT = "—"

    /**
     * 累计降价的推进：旧值 + 本轮发出的降价提醒数。
     * 两个入参都不该为负（负数是脏数据），一律按 0 处理，保证累计数不会反而变小。
     */
    fun nextDropped(previousDropped: Int, triggeredThisRound: Int): Int =
        previousDropped.coerceAtLeast(0) + triggeredThisRound.coerceAtLeast(0)

    /**
     * 一轮收尾后的新快照：watching 取本轮值、dropped 累加、last_check_at 取本轮完成时刻。
     * [atMillis] <= 0 表示"这一轮没有完成时刻"（如目标清空轮），保留原时间而不是把 0 写进去。
     */
    fun afterRound(previous: WidgetSnapshot, watching: Int, triggeredThisRound: Int, atMillis: Long): WidgetSnapshot = WidgetSnapshot(
        watching = watching.coerceAtLeast(0),
        dropped = nextDropped(previous.dropped, triggeredThisRound),
        lastCheckAt = if (atMillis > 0L) atMillis else previous.lastCheckAt
    )

    /** 存储值 → 快照：负数/负时间戳是脏数据，按 0 处理（小组件不显示负计数与 1970 时间） */
    fun snapshotOf(watching: Int, dropped: Int, lastCheckAt: Long): WidgetSnapshot = WidgetSnapshot(
        watching = watching.coerceAtLeast(0),
        dropped = dropped.coerceAtLeast(0),
        lastCheckAt = lastCheckAt.coerceAtLeast(0L)
    )

    /** "HH:mm" 时钟文本；0/负值 = 从未检查 → [NEVER_TEXT]。时区/区域显式传入以便 JVM 单测 */
    fun clockText(atMillis: Long, zone: TimeZone = TimeZone.getDefault(), locale: Locale = Locale.getDefault()): String {
        if (atMillis <= 0L) return NEVER_TEXT
        val calendar = Calendar.getInstance(zone, locale)
        calendar.timeInMillis = atMillis
        return String.format(locale, "%02d:%02d", calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE))
    }
}

/** 小组件 / 磁贴展示用的三个数（各字段口径见 [WidgetStats] 的类文档） */
data class WidgetSnapshot(
    val watching: Int,
    val dropped: Int,
    val lastCheckAt: Long
) {
    companion object {
        val EMPTY = WidgetSnapshot(watching = 0, dropped = 0, lastCheckAt = 0L)
    }
}

/** SharedPreferences("watch_state") 的读写壳：逻辑全部走 [WidgetStats] 纯函数，这里只做 IO */
class WidgetStateStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(WidgetStats.PREFS_NAME, Context.MODE_PRIVATE)

    fun snapshot(): WidgetSnapshot = WidgetStats.snapshotOf(
        watching = prefs.getInt(WidgetStats.KEY_WATCHING, 0),
        dropped = prefs.getInt(WidgetStats.KEY_DROPPED, 0),
        lastCheckAt = prefs.getLong(WidgetStats.KEY_LAST_CHECK_AT, 0L)
    )

    /** 一轮收尾：读旧快照 → 纯函数合并 → 落盘；返回新快照（调用方仅用于日志/展示） */
    fun recordRound(watching: Int, triggeredThisRound: Int, atMillis: Long): WidgetSnapshot {
        val next = WidgetStats.afterRound(snapshot(), watching, triggeredThisRound, atMillis)
        prefs.edit()
            .putInt(WidgetStats.KEY_WATCHING, next.watching)
            .putInt(WidgetStats.KEY_DROPPED, next.dropped)
            .putLong(WidgetStats.KEY_LAST_CHECK_AT, next.lastCheckAt)
            .apply()
        return next
    }
}
