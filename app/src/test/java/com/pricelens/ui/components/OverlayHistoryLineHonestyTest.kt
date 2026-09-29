package com.pricelens.ui.components

import com.pricelens.R
import com.pricelens.data.remote.ManmanbuyApi
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * F3 缺陷一：浮窗"历史最低"行的取数口径诚实性。
 *
 * 病灶：`lowestWithinDays` 里 `windowed.ifEmpty { history.points }` 把"窗口内一个点都没有、
 * 只能拿更早的曲线"这件事吞掉了，调用方拿到值就直接说"90 天最低"。
 * 修复口径：窗口选择必须进返回值（[LowPrice.scope]），UI 按口径分三条文案：
 *  ① 窗口内有点 → [HistoryLine.WINDOW_HIGH]/[HistoryLine.WINDOW_NEAR_LOW]（"90 天最低"）；
 *  ② 窗口内没点、只有更早的历史 → [HistoryLine.OLDER_HIGH]/[HistoryLine.OLDER_NEAR_LOW]（说破"90 天窗口外"）；
 *  ③ 没有可用点 → [HistoryLine.NONE]（整行不出）。
 *
 * 纯函数断言，不碰 Compose（本项目单测不写 UI 测试）。
 */
class OverlayHistoryLineHonestyTest {

    /** 固定"今天"：2026-03-01，90 天窗口 cutoff = 2025-12-01 */
    private val today = LocalDate.of(2026, 3, 1)
    private val days = 90

    private fun history(vararg pairs: Pair<String, Double>) = ManmanbuyApi.History(
        current = pairs.maxOf { it.second },
        lowest = pairs.minOf { it.second },
        highest = pairs.maxOf { it.second },
        points = pairs.map { (d, p) -> ManmanbuyApi.PricePoint(d, p) }
    )

    // ---------- ① 窗口内有点：允许说 90 天最低 ----------

    @Test
    fun `window point wins over older cheaper point`() {
        val h = history(
            // 2025-01-10 是窗口外的更低价，不许污染 90 天窗口的结论
            "2025-01-10" to 50.0,
            "2026-01-05" to 100.0,
            "2026-02-20" to 120.0
        )
        val low = lowestWithinDays(h, days, today)!!
        assertEquals(100.0, low.price, 1e-9)
        assertEquals(LowPriceScope.WITHIN_DAYS, low.scope)
        assertEquals(HistoryLine.WINDOW_HIGH, historyLineFor(low, 150.0))
        assertEquals(R.string.ovl_history_high, historyLineStringRes(HistoryLine.WINDOW_HIGH))
    }

    @Test
    fun `point exactly on the cutoff date is inside the window`() {
        val low = lowestWithinDays(history("2025-12-01" to 88.0), days, today)!!
        assertEquals(LowPriceScope.WITHIN_DAYS, low.scope)
    }

    @Test
    fun `current price at or below window low uses near low copy`() {
        val low = lowestWithinDays(history("2026-02-01" to 100.0), days, today)!!
        assertEquals(HistoryLine.WINDOW_NEAR_LOW, historyLineFor(low, 100.0))
        assertEquals(R.string.ovl_history_near_low, historyLineStringRes(HistoryLine.WINDOW_NEAR_LOW))
    }

    // ---------- ② 窗口内没点：只能说更早的历史最低 ----------

    @Test
    fun `curve older than the window must not claim the 90 day low`() {
        val h = history("2025-02-01" to 60.0, "2025-06-01" to 55.0)
        val low = lowestWithinDays(h, days, today)
        assertEquals("更早曲线的最低价仍要给出", 55.0, low!!.price, 1e-9)
        assertEquals(
            "窗口内一个点都没有，口径必须标成 OLDER_ONLY，否则 UI 会说谎",
            LowPriceScope.OLDER_ONLY,
            low.scope
        )
        assertEquals(HistoryLine.OLDER_HIGH, historyLineFor(low, 90.0))
        assertEquals(HistoryLine.OLDER_NEAR_LOW, historyLineFor(low, 50.0))
    }

    @Test
    fun `only today single point is in window not older only`() {
        // 反例守卫：曲线只有今天一个点 → 这是货真价实的窗口内数据，不许被降级成"窗口外"
        val low = lowestWithinDays(history(today.toString() to 70.0), days, today)!!
        assertEquals(LowPriceScope.WITHIN_DAYS, low.scope)
    }

    @Test
    fun `older only scope never maps to a window-day string`() {
        for (line in listOf(HistoryLine.OLDER_HIGH, HistoryLine.OLDER_NEAR_LOW)) {
            val res = historyLineStringRes(line)
            assertNotEquals("窗口外口径绝不能复用\"90 天最低\"文案", R.string.ovl_history_high, res)
            assertNotEquals("窗口外口径绝不能复用\"90 天最低\"文案", R.string.ovl_history_near_low, res)
        }
        assertNotEquals(R.string.ovl_history_high, historyLineStringRes(HistoryLine.OLDER_HIGH))
        assertEquals(R.string.ovl_history_high_older, historyLineStringRes(HistoryLine.OLDER_HIGH))
        assertEquals(R.string.ovl_history_near_low_older, historyLineStringRes(HistoryLine.OLDER_NEAR_LOW))
    }

    // ---------- ③ 没有可用点：整行不出 ----------

    @Test
    fun `no history means no history line`() {
        assertNull(lowestWithinDays(null, days, today))
        assertNull(lowestWithinDays(ManmanbuyApi.History(0.0, 0.0, 0.0, emptyList()), days, today))
        assertEquals(HistoryLine.NONE, historyLineFor(null, 100.0))
    }

    @Test
    fun `non positive prices are not a usable low`() {
        val low = lowestWithinDays(history("2026-02-01" to 0.0), days, today)
        assertNull(low)
        assertEquals(HistoryLine.NONE, historyLineFor(low, 100.0))
        // 防御：即使拿到 0/负值，文案选择也只能是"不出这一行"（除零保护）
        assertEquals(HistoryLine.NONE, historyLineFor(LowPrice(0.0, LowPriceScope.WITHIN_DAYS), 100.0))
        assertEquals(HistoryLine.NONE, historyLineFor(LowPrice(-3.0, LowPriceScope.OLDER_ONLY), 100.0))
    }
}
