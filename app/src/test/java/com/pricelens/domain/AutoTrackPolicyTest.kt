package com.pricelens.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AutoTrackPolicy] 自适应调度 + 同款匹配 + 防套买入信号纯函数基线（2026-11 自动追价）。
 *
 * 覆盖：
 *  - 间隔档位：用户档位归一（30/60/120/180/360，30 分钟硬下限、360 上限）；
 *  - 最近目标距离自适应：已达标/≤2% → 30、≤5% → 60、≤10% → 120、≤25% → ≤180；
 *  - 夜间：暂停时等到免打扰结束（夹在 [30,360] 分钟）；不暂停时夜间降频到 ≥180；
 *  - 低电量、仅 WiFi 当前非 WiFi 的跳过与重试间隔；
 *  - 同款匹配 `matchOffer`（空关键词/无价/离谱价/配件/不相关标题）；
 *  - 价格与历史参考比值 band（0.5~2.0 边界）；
 *  - 买前防套信号：无价、假降价（>10% 才报）、近历史低（≤2% 才报）、单源/低置信核价、常规观察。
 */
class AutoTrackPolicyTest {

    private fun input(
        enabled: Boolean = true,
        intervalMinutes: Int = 60,
        activeTargets: Int = 3,
        nearestDistanceRatio: Double? = null,
        nowMinuteOfDay: Int = 12 * 60,
        quietHoursEnabled: Boolean = false,
        quietPauseEnabled: Boolean = true,
        quietStartMinute: Int = 22 * 60,
        quietEndMinute: Int = 8 * 60,
        onWifi: Boolean = true,
        wifiOnly: Boolean = false,
        batteryLow: Boolean = false
    ) = AutoTrackPlanInput(
        enabled = enabled,
        intervalMinutes = intervalMinutes,
        activeTargets = activeTargets,
        nearestDistanceRatio = nearestDistanceRatio,
        nowMinuteOfDay = nowMinuteOfDay,
        quietHoursEnabled = quietHoursEnabled,
        quietPauseEnabled = quietPauseEnabled,
        quietStartMinute = quietStartMinute,
        quietEndMinute = quietEndMinute,
        onWifi = onWifi,
        wifiOnly = wifiOnly,
        batteryLow = batteryLow
    )

    // ---------- 1. 开关 / 目标数 ----------

    @Test
    fun `disabled auto track ends the chain`() {
        val decision = AutoTrackPolicy.decideNext(input(enabled = false), nowMs = 0L)
        assertFalse(decision.shouldRun)
        assertEquals("链结束用 0 表示", 0, decision.nextDelayMinutes)
        assertTrue(decision.reason.contains("关闭"))
    }

    @Test
    fun `no active target ends the chain instead of polling forever`() {
        val decision = AutoTrackPolicy.decideNext(input(activeTargets = 0), nowMs = 0L)
        assertFalse(decision.shouldRun)
        assertEquals(0, decision.nextDelayMinutes)
        assertTrue(decision.reason.contains("没有"))
    }

    // ---------- 2. 用户档位归一 ----------

    @Test
    fun `interval is normalized into the supported 30 to 360 steps`() {
        assertEquals(30, AutoTrackPolicy.normalizeInterval(15))
        assertEquals(30, AutoTrackPolicy.normalizeInterval(30))
        assertEquals(30, AutoTrackPolicy.normalizeInterval(45))
        assertEquals(60, AutoTrackPolicy.normalizeInterval(100))
        assertEquals(180, AutoTrackPolicy.normalizeInterval(200))
        assertEquals(360, AutoTrackPolicy.normalizeInterval(500))
        assertEquals(AutoTrackPolicy.MIN_INTERVAL_MINUTES, 30)
        assertEquals(AutoTrackPolicy.MAX_INTERVAL_MINUTES, 360)
        assertEquals(listOf(30, 60, 120, 180, 360), AutoTrackPolicy.INTERVAL_STEPS_MINUTES)
    }

    // ---------- 3. 最近目标距离自适应 ----------

    @Test
    fun `far target keeps the user interval`() {
        val decision = AutoTrackPolicy.decideNext(input(intervalMinutes = 180, nearestDistanceRatio = 0.40), nowMs = 0L)
        assertTrue(decision.shouldRun)
        assertEquals(180, decision.nextDelayMinutes)
    }

    @Test
    fun `target already reached or within 2 percent polls every 30 minutes`() {
        assertEquals(30, AutoTrackPolicy.decideNext(input(nearestDistanceRatio = -0.05), 0L).nextDelayMinutes)
        assertEquals(30, AutoTrackPolicy.decideNext(input(nearestDistanceRatio = 0.02), 0L).nextDelayMinutes)
        assertEquals(30, AutoTrackPolicy.decideNext(input(nearestDistanceRatio = 0.0), 0L).nextDelayMinutes)
    }

    @Test
    fun `target within 5 percent polls every 60 minutes`() {
        assertEquals(60, AutoTrackPolicy.decideNext(input(nearestDistanceRatio = 0.021), 0L).nextDelayMinutes)
        assertEquals(60, AutoTrackPolicy.decideNext(input(nearestDistanceRatio = 0.05), 0L).nextDelayMinutes)
    }

    @Test
    fun `target within 10 percent polls every 120 minutes`() {
        assertEquals(120, AutoTrackPolicy.decideNext(input(nearestDistanceRatio = 0.051), 0L).nextDelayMinutes)
        assertEquals(120, AutoTrackPolicy.decideNext(input(nearestDistanceRatio = 0.10), 0L).nextDelayMinutes)
    }

    @Test
    fun `target within 25 percent caps the interval at 180 minutes`() {
        val decision = AutoTrackPolicy.decideNext(input(intervalMinutes = 360, nearestDistanceRatio = 0.20), 0L)
        assertTrue(decision.shouldRun)
        assertEquals(180, decision.nextDelayMinutes)
    }

    @Test
    fun `unknown distance falls back to the user interval instead of pretending zero`() {
        val decision = AutoTrackPolicy.decideNext(input(intervalMinutes = 120, nearestDistanceRatio = null), 0L)
        assertEquals(120, decision.nextDelayMinutes)
        assertTrue(decision.reason.contains("暂无可算"))
    }

    // ---------- 4. 夜间 ----------

    @Test
    fun `quiet hours with pause on skip the round until the window ends`() {
        // 23:00 → 次日 08:00，还要等 9 小时；等待值被夹到 360 分钟（不排一个跨越半天以上的任务）
        val lateNight = AutoTrackPolicy.decideNext(
            input(nowMinuteOfDay = 23 * 60, quietHoursEnabled = true, quietPauseEnabled = true),
            0L
        )
        assertFalse(lateNight.shouldRun)
        assertEquals(360, lateNight.nextDelayMinutes)
        assertTrue(lateNight.reason.contains("夜间暂停"))

        // 07:30 距 08:00 还有 30 分钟 → 正好 30 分钟后重试
        val nearWake = AutoTrackPolicy.decideNext(
            input(nowMinuteOfDay = 7 * 60 + 30, quietHoursEnabled = true, quietPauseEnabled = true),
            0L
        )
        assertFalse(nearWake.shouldRun)
        assertEquals(30, nearWake.nextDelayMinutes)
    }

    @Test
    fun `quiet hours without pause only slow the cadence down`() {
        val decision = AutoTrackPolicy.decideNext(
            input(intervalMinutes = 60, nowMinuteOfDay = 23 * 60, quietHoursEnabled = true, quietPauseEnabled = false),
            0L
        )
        assertTrue("夜间不暂停时仍然巡检", decision.shouldRun)
        assertEquals("夜间至少 180 分钟一档", 180, decision.nextDelayMinutes)
        assertTrue(decision.reason.contains("降频"))
    }

    @Test
    fun `quiet window boundaries are half open and midnight crossing`() {
        assertFalse(AutoTrackPolicy.isQuietHours(21 * 60 + 59, 22 * 60, 8 * 60))
        assertTrue(AutoTrackPolicy.isQuietHours(22 * 60, 22 * 60, 8 * 60))
        assertTrue(AutoTrackPolicy.isQuietHours(7 * 60 + 59, 22 * 60, 8 * 60))
        assertFalse(AutoTrackPolicy.isQuietHours(8 * 60, 22 * 60, 8 * 60))
        // start == end 视为未设置（不能静默变成全天暂停）
        assertFalse(AutoTrackPolicy.isQuietHours(12 * 60, 9 * 60, 9 * 60))
        // 配置未开启时不看时段
        val decision = AutoTrackPolicy.decideNext(
            input(nowMinuteOfDay = 23 * 60, quietHoursEnabled = false, quietPauseEnabled = true),
            0L
        )
        assertTrue(decision.shouldRun)
    }

    // ---------- 5. 低电量 / 仅 WiFi ----------

    @Test
    fun `low battery slows the cadence to at least 180 minutes`() {
        val decision = AutoTrackPolicy.decideNext(input(intervalMinutes = 60, batteryLow = true), 0L)
        assertFalse(decision.shouldRun)
        assertEquals(180, decision.nextDelayMinutes)
        assertTrue(decision.reason.contains("电量"))
    }

    @Test
    fun `wifi only with mobile network retries in 30 minutes`() {
        val decision = AutoTrackPolicy.decideNext(input(wifiOnly = true, onWifi = false), 0L)
        assertFalse(decision.shouldRun)
        assertEquals(30, decision.nextDelayMinutes)
        assertTrue(decision.reason.contains("WiFi"))
        // 已在 WiFi 上就正常跑
        assertTrue(AutoTrackPolicy.decideNext(input(wifiOnly = true, onWifi = true), 0L).shouldRun)
    }

    // ---------- 6. 严格同款匹配 ----------

    @Test
    fun `matchOffer accepts a relevant title with a sane positive price`() {
        val match = AutoTrackPolicy.matchOffer("iPhone 15 128GB", "Apple iPhone 15 128GB 黑色 手机", 4999.0)
        assertTrue(match.accepted)
        assertTrue(match.relevance > 0.0)
    }

    @Test
    fun `matchOffer rejects empty keyword blank title and non positive prices`() {
        assertFalse(AutoTrackPolicy.matchOffer("", "iPhone 15", 4999.0).accepted)
        assertFalse(AutoTrackPolicy.matchOffer("iPhone 15", "  ", 4999.0).accepted)
        assertFalse(AutoTrackPolicy.matchOffer("iPhone 15", "iPhone 15 手机", 0.0).accepted)
        assertFalse(AutoTrackPolicy.matchOffer("iPhone 15", "iPhone 15 手机", -1.0).accepted)
        assertFalse(AutoTrackPolicy.matchOffer("iPhone 15", "iPhone 15 手机", Double.NaN).accepted)
        // 解析错误的离谱价格（把"88VIP"拼成 88 万之类）也要挡下
        assertFalse(AutoTrackPolicy.matchOffer("iPhone 15", "iPhone 15 手机", 2_000_000.0).accepted)
    }

    @Test
    fun `matchOffer rejects accessories and unrelated products`() {
        assertFalse(
            "配件不是同款",
            AutoTrackPolicy.matchOffer("iPhone 15 128GB", "适用 iPhone 15 手机壳 保护套", 19.9).accepted
        )
        assertFalse(
            "别的品牌/型号不是同款",
            AutoTrackPolicy.matchOffer("华为 Mate 80", "小米 15 Ultra 16GB+512GB", 5999.0).accepted
        )
        assertFalse(
            "图书导购不是商品",
            AutoTrackPolicy.matchOffer("iPhone 15", "iPhone 15 使用指南 手册", 39.0).accepted
        )
    }

    @Test
    fun `price ratio band is inclusive and unknown reference does not block`() {
        assertTrue(AutoTrackPolicy.isPriceRatioReasonable(500.0, 1000.0)) // 0.5 下界
        assertTrue(AutoTrackPolicy.isPriceRatioReasonable(1000.0, 1000.0))
        assertTrue(AutoTrackPolicy.isPriceRatioReasonable(2000.0, 1000.0)) // 2.0 上界
        assertFalse(AutoTrackPolicy.isPriceRatioReasonable(499.0, 1000.0))
        assertFalse(AutoTrackPolicy.isPriceRatioReasonable(2001.0, 1000.0))
        assertTrue("没有参考价时不比较比值，但仍要求价格为正", AutoTrackPolicy.isPriceRatioReasonable(4999.0, null))
        assertFalse(AutoTrackPolicy.isPriceRatioReasonable(0.0, 1000.0))
    }

    // ---------- 7. 买前防套：买入信号 ----------

    @Test
    fun `no price asks the user to verify the page instead of guessing`() {
        val signal = AutoTrackPolicy.buySignal(nowPrice = null, historyLow = 4999.0, liveSourceCount = 3)
        assertEquals(BuySignalKind.VERIFY_PAGE, signal.kind)
        assertTrue(signal.detail.contains("核价"))
    }

    @Test
    fun `near history low triggers at the 2 percent boundary`() {
        val boundary = AutoTrackPolicy.buySignal(
            nowPrice = 102.0,
            historyLow = 100.0,
            liveSourceCount = 3
        )
        assertEquals(BuySignalKind.NEAR_HISTORY_LOW, boundary.kind)
        val above = AutoTrackPolicy.buySignal(nowPrice = 103.0, historyLow = 100.0, liveSourceCount = 3)
        assertEquals(BuySignalKind.WATCH, above.kind)
    }

    @Test
    fun `fake drop warns only when the 7 day series rose and then fell more than 10 percent`() {
        // 先涨（100 → 130）后降（130 → 110，回落 15.4% > 10%）
        val fake = AutoTrackPolicy.buySignal(
            nowPrice = 110.0,
            historyLow = 90.0,
            recentPrices = listOf(100.0, 105.0, 130.0, 110.0),
            liveSourceCount = 3
        )
        assertEquals(BuySignalKind.FAKE_DROP, fake.kind)
        assertTrue(fake.label.contains("先涨后降"))

        // 正好 10% 不报（阈值是"严格大于 10%"）：峰值 100、现价 90、之前有更低的 80
        val edge = AutoTrackPolicy.buySignal(
            nowPrice = 90.0,
            historyLow = 80.0,
            recentPrices = listOf(80.0, 100.0, 90.0),
            liveSourceCount = 3
        )
        assertFalse("正好 10% 不算假降价", edge.kind == BuySignalKind.FAKE_DROP)

        // 一路下跌（没有先涨）也不报
        val falling = AutoTrackPolicy.buySignal(
            nowPrice = 110.0,
            historyLow = 100.0,
            recentPrices = listOf(130.0, 125.0, 120.0, 110.0),
            liveSourceCount = 3
        )
        assertFalse(falling.kind == BuySignalKind.FAKE_DROP)
    }

    @Test
    fun `single source or low confidence asks for page verification`() {
        val single = AutoTrackPolicy.buySignal(
            nowPrice = 4999.0,
            historyLow = 4800.0,
            recentPrices = emptyList(),
            liveSourceCount = 1
        )
        assertEquals(BuySignalKind.VERIFY_PAGE, single.kind)
        val lowConfidence = AutoTrackPolicy.buySignal(
            nowPrice = 4999.0,
            historyLow = 4800.0,
            liveSourceCount = 3,
            lowConfidence = true
        )
        assertEquals(BuySignalKind.VERIFY_PAGE, lowConfidence.kind)
    }

    @Test
    fun `normal price yields a plain watch signal`() {
        val signal = AutoTrackPolicy.buySignal(
            nowPrice = 5600.0,
            historyLow = 4800.0,
            recentPrices = listOf(5500.0, 5600.0, 5700.0),
            liveSourceCount = 3
        )
        assertEquals(BuySignalKind.WATCH, signal.kind)
        assertTrue(signal.detail.isNotEmpty())
    }
}
