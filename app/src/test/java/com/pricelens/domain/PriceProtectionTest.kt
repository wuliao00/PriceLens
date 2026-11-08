package com.pricelens.domain

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PriceProtectionPolicy] 价保规则纯函数基线（2026-11 自动追价大更新 · 买后链路）。
 *
 * 规则依据：`docs/research/2026-11-价保与防套-规则.md`（2026-11 检索），核心口径：
 *  - 京东自营按商品标识 7/15/30/90/180/365 天，**签收次日起算**（本机拿不到签收时间 →
 *    用购买日次日 00:00 保守下界）；京东第三方按下单后 168/360/720/2160 小时；
 *  - 淘宝/天猫默认 15 天（大件 30 天）、拼多多默认 7 天（大家电 30、生鲜 72 小时）、唯品会 10 天、
 *    其他平台 7 天并提示以平台为准；
 *  - 金额 = 实付 − 当前同款最低可信价（诚实简化版），必须 >0 且 ≤ 实付价，噪声门槛
 *    max(¥1, 实付×0.5%)；
 *  - 幂等：同一「降价档位」（每 ¥10 一档）只提醒一次；更高档且间隔 ≥12h 才再提醒；
 *    窗口剩余 ≤24h 时 urgent（高优先级通知）；
 *  - 无价保（窗口 0 天）/窗口外只展示价格变化，不叫价保、不提醒；
 *  - 拿不到现价 → NO_PRICE（不落 0/旧价）。
 */
class PriceProtectionTest {

    private val day = PriceProtectionPolicy.DAY_MS
    private val hour = PriceProtectionPolicy.HOUR_MS

    // ---------- 1. 平台窗口表（默认档 + 档位） ----------

    @Test
    fun `platform table carries default windows and human labels`() {
        assertEquals(7, ProtectionPlatform.JD.defaultWindowDays)
        assertEquals(7, ProtectionPlatform.JD_THIRD.defaultWindowDays)
        assertEquals(15, ProtectionPlatform.TAOBAO.defaultWindowDays)
        assertEquals(7, ProtectionPlatform.PDD.defaultWindowDays)
        assertEquals(10, ProtectionPlatform.VIP.defaultWindowDays)
        assertEquals(7, ProtectionPlatform.OTHER.defaultWindowDays)
        for (rule in ProtectionPlatform.values()) {
            assertTrue("每个平台都必须带人读标签", rule.label.isNotBlank())
            assertTrue("规则说明必须带『以…为准』而不是承诺", rule.windowNote.isNotBlank())
        }
        assertEquals(ProtectionPlatform.JD, ProtectionPlatform.of("jd"))
        assertEquals(ProtectionPlatform.JD_THIRD, ProtectionPlatform.of("京东第三方"))
        assertEquals(ProtectionPlatform.TAOBAO, ProtectionPlatform.of("tmall"))
        assertEquals(ProtectionPlatform.PDD, ProtectionPlatform.of("拼多多"))
        assertEquals(ProtectionPlatform.VIP, ProtectionPlatform.of("唯品会"))
        // 抖音等规则多变 → 归入其他平台，不硬编码
        assertEquals(ProtectionPlatform.OTHER, ProtectionPlatform.of("douyin"))
    }

    @Test
    fun `window days resolve from platform default and user override`() {
        assertEquals(7, PriceProtectionPolicy.windowDaysFor("jd"))
        assertEquals(7, PriceProtectionPolicy.windowDaysFor("jd", null))
        assertEquals(30, PriceProtectionPolicy.windowDaysFor("jd", 30))
        assertEquals(365, PriceProtectionPolicy.windowDaysFor("jd", 365))
        assertEquals("400 天越界按 365 封顶", 365, PriceProtectionPolicy.windowDaysFor("jd", 400))
        assertEquals("0 = 用户明确无价保", 0, PriceProtectionPolicy.windowDaysFor("jd", 0))
        assertEquals("15 = 天猫默认", 15, PriceProtectionPolicy.windowDaysFor("taobao"))
        assertEquals("10 = 唯品会默认", 10, PriceProtectionPolicy.windowDaysFor("vipshop"))
    }

    @Test
    fun `jd third party windows are expressed in hours and pdd fresh uses 72 hours`() {
        assertEquals(168, PriceProtectionPolicy.windowHoursFor("jd_third"))
        assertEquals(168, PriceProtectionPolicy.windowHoursFor("jd_third", 7))
        assertEquals(360, PriceProtectionPolicy.windowHoursFor("jd_third", 15))
        assertEquals(720, PriceProtectionPolicy.windowHoursFor("jd_third", 30))
        assertEquals(2160, PriceProtectionPolicy.windowHoursFor("jd_third", 90))
        assertEquals("淘宝按天算", 0, PriceProtectionPolicy.windowHoursFor("taobao", 15))
        assertEquals("拼多多生鲜 72 小时档", 72, PriceProtectionPolicy.windowHoursFor("pdd", 3))
        assertEquals("拼多多大家电 30 天按天算", 0, PriceProtectionPolicy.windowHoursFor("pdd", 30))
    }

    @Test
    fun `jd keeps all identifiable tiers in the picker and pdd fresh shows up as hours`() {
        val jdTiers = ProtectionPlatform.windowOptionsOf("jd").map { it.days }
        assertTrue(listOf(7, 15, 30, 90, 180, 365).all { it in jdTiers })
        assertTrue("选择器里要有『无价保』", 0 in jdTiers)
        val thirdHours = ProtectionPlatform.windowOptionsOf("jd_third").map { it.hours }
        assertTrue(listOf(168, 360, 720, 2160).all { it in thirdHours })
        val pddOptions = ProtectionPlatform.windowOptionsOf("pdd")
        assertTrue(pddOptions.any { it.days == 7 })
        assertTrue(pddOptions.any { it.days == 30 })
        assertTrue(pddOptions.any { it.hours == 72 })
        val daysLabel = PriceProtectionPolicy.windowOptionLabel(ProtectionWindowOption(15, 0))
        assertEquals("15 天", daysLabel)
        assertTrue(PriceProtectionPolicy.windowOptionLabel(ProtectionWindowOption(7, 168)).contains("168 小时"))
        assertEquals("无价保", PriceProtectionPolicy.windowOptionLabel(ProtectionWindowOption(0, 0)))
    }

    // ---------- 2. 窗口边界：京东自营从"次日 00:00"起算 ----------

    @Test
    fun `jd self window starts at next day midnight and 30 day deadline is exclusive boundary`() {
        val purchased = local(2026, 11, 1, 10, 30)
        val start = PriceProtectionPolicy.windowStartAtMs("jd", purchased)
        val calendar = Calendar.getInstance().apply { timeInMillis = start }
        assertEquals(0, calendar.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, calendar.get(Calendar.MINUTE))
        assertTrue("起算点必须在购买之后", start > purchased)
        assertTrue("起算点不会晚于 24 小时", start - purchased <= day)
        // 淘宝不是自营：按下单/付款时刻起算
        assertEquals(purchased, PriceProtectionPolicy.windowStartAtMs("taobao", purchased))

        val deadline = PriceProtectionPolicy.deadlineOf(start, windowDays = 30)
        assertEquals(start + 30 * day, deadline)
        val justBefore = evaluateAt(
            purchasePrice = 1000.0,
            nowPrice = 800.0,
            purchasedAt = purchased,
            windowDays = 30,
            now = deadline - 1,
            windowStartAtMs = start
        )
        assertEquals(ProtectionWindowState.CLAIMABLE, justBefore.state)
        val justAfter = evaluateAt(1000.0, 800.0, purchased, 30, deadline + 1, windowStartAtMs = start)
        assertEquals("到点即过期", ProtectionWindowState.OUT_OF_WINDOW, justAfter.state)
        assertFalse(justAfter.shouldNotify)
    }

    @Test
    fun `jd third party 168 hour window boundary is exact`() {
        val purchased = local(2026, 11, 1, 9, 0)
        val deadline = PriceProtectionPolicy.deadlineOf(
            purchased,
            PriceProtectionPolicy.windowDaysFor("jd_third", 7),
            PriceProtectionPolicy.windowHoursFor("jd_third", 7)
        )
        assertEquals(purchased + 168 * hour, deadline)
        val inside = evaluateAt(1000.0, 800.0, purchased, 7, deadline - 1, windowHours = 168)
        assertEquals(ProtectionWindowState.CLAIMABLE, inside.state)
        val outside = evaluateAt(1000.0, 800.0, purchased, 7, deadline + 1, windowHours = 168)
        assertEquals(ProtectionWindowState.OUT_OF_WINDOW, outside.state)
    }

    @Test
    fun `pdd fresh 72 hour window and large appliance 30 day window`() {
        val purchased = local(2026, 11, 1, 9, 0)
        val freshDeadline = PriceProtectionPolicy.deadlineOf(purchased, windowDays = 3, windowHours = 72)
        assertEquals(purchased + 72 * hour, freshDeadline)
        assertEquals(
            ProtectionWindowState.CLAIMABLE,
            evaluateAt(100.0, 80.0, purchased, 3, purchased + 71 * hour, windowHours = 72).state
        )
        assertEquals(
            ProtectionWindowState.OUT_OF_WINDOW,
            evaluateAt(100.0, 80.0, purchased, 3, purchased + 73 * hour, windowHours = 72).state
        )
        val bigApplianceDeadline = PriceProtectionPolicy.deadlineOf(purchased, windowDays = 30)
        assertEquals(purchased + 30 * day, bigApplianceDeadline)
    }

    // ---------- 3. 金额口径（诚实简化版） ----------

    @Test
    fun `claimable amount is purchase minus current lowest trusted price and never exceeds purchase price`() {
        val status = evaluateAt(1000.0, 800.0, local(2026, 11, 1, 10, 0), 15, local(2026, 11, 5, 10, 0))
        assertEquals(ProtectionWindowState.CLAIMABLE, status.state)
        assertEquals(200.0, status.claimableYuan, 0.001)
        assertEquals(20.0, status.dropPercent, 0.001)
        assertTrue(status.note.contains("名义差额"))
        assertTrue("必须提示以平台审核为准", status.note.contains("以平台审核为准") || status.note.contains("为准"))

        // 现价极低也不能申请超过实付价
        val clamped = evaluateAt(1000.0, 0.4, local(2026, 11, 1, 10, 0), 15, local(2026, 11, 5, 10, 0))
        assertTrue("可申请金额必须 ≤ 实付价", clamped.claimableYuan <= 1000.0)
        assertEquals(999.6, clamped.claimableYuan, 0.001)
    }

    @Test
    fun `noise threshold is max of one yuan and half a percent of paid price`() {
        assertEquals(1.0, PriceProtectionPolicy.minClaimYuan(100.0), 0.001)
        assertEquals(5.0, PriceProtectionPolicy.minClaimYuan(1000.0), 0.001)
        // 差 0.5 元 < 1 元门槛 → 不提醒
        val tiny = evaluateAt(500.0, 499.5, local(2026, 11, 1, 10, 0), 15, local(2026, 11, 5, 10, 0))
        assertEquals(ProtectionWindowState.NOT_DROPPED, tiny.state)
        assertFalse(tiny.shouldNotify)
        // 买 1 万差 55 元 > 50 元门槛 → 可申请
        val meaningful = evaluateAt(10_000.0, 9_945.0, local(2026, 11, 1, 10, 0), 15, local(2026, 11, 5, 10, 0))
        assertEquals(ProtectionWindowState.CLAIMABLE, meaningful.state)
    }

    // ---------- 4. 无价保 / 无价 / 窗口外 ----------

    @Test
    fun `no protection window still shows the price change but never calls it claimable`() {
        val status = evaluateAt(1000.0, 700.0, local(2026, 11, 1, 10, 0), windowDays = 0, now = local(2026, 11, 3, 10, 0))
        assertEquals(ProtectionWindowState.NO_PROTECTION, status.state)
        assertFalse(status.claimable)
        assertEquals(0.0, status.claimableYuan, 0.001)
        assertEquals("无价保平台仍要展示价格变化", 300.0, status.dropYuan, 0.001)
        assertFalse("无价保不叫可申请", status.shouldNotify)
        assertTrue(status.note.contains("仅展示价格变化"))
    }

    @Test
    fun `missing current price is reported as unknown instead of zero or stale`() {
        for (missing in listOf<Double?>(null, 0.0, -1.0, Double.NaN)) {
            val status = evaluateAt(1000.0, missing, local(2026, 11, 1, 10, 0), 15, local(2026, 11, 5, 10, 0))
            assertEquals("现价缺失必须报 NO_PRICE：$missing", ProtectionWindowState.NO_PRICE, status.state)
            assertFalse(status.claimable)
            assertEquals(0.0, status.claimableYuan, 0.001)
            assertEquals(0.0, status.dropYuan, 0.001)
            assertFalse(status.shouldNotify)
            assertTrue(status.note.contains("暂取不到现价"))
        }
        // 窗口外 + 没有现价：优先报"已过价保期"
        val expiredNoPrice = evaluateAt(
            1000.0,
            null,
            local(2026, 11, 1, 10, 0),
            7,
            local(2026, 11, 20, 10, 0)
        )
        assertEquals(ProtectionWindowState.OUT_OF_WINDOW, expiredNoPrice.state)
    }

    @Test
    fun `expired window never notifies even when price dropped a lot`() {
        val status = evaluateAt(1000.0, 500.0, local(2026, 10, 1, 10, 0), 15, local(2026, 11, 5, 10, 0))
        assertEquals(ProtectionWindowState.OUT_OF_WINDOW, status.state)
        assertFalse(status.claimable)
        assertFalse(status.shouldNotify)
        assertTrue("窗口外仍展示价格变化", status.dropYuan > 0.0)
    }

    // ---------- 5. 幂等 / 节流 / 紧急通知 ----------

    @Test
    fun `first claimable round notifies and same tier is not repeated`() {
        val purchased = local(2026, 11, 1, 10, 0)
        val now = local(2026, 11, 5, 10, 0)
        val first = evaluateAt(1000.0, 800.0, purchased, 15, now)
        assertTrue(first.shouldNotify)
        assertFalse(first.urgent)

        // 同一档位（200 → 205 仍属 20 档）且已提醒过 → 不再提醒
        val repeat = PriceProtectionPolicy.evaluate(
            purchasePrice = 1000.0,
            nowPrice = 795.0,
            purchasedAtMs = purchased,
            windowDays = 15,
            nowMs = now + 13 * hour,
            alreadyNotified = true,
            lastNotifiedYuan = 200.0,
            lastNotifiedAtMs = now
        )
        assertFalse("同一降价档位只提醒一次", repeat.shouldNotify)
        assertTrue(repeat.claimable)
    }

    @Test
    fun `a lower tier needs both a meaningful new amount and twelve hours since last reminder`() {
        val purchased = local(2026, 11, 1, 10, 0)
        val lastAt = local(2026, 11, 5, 10, 0)
        // 200 → 260：档位从 20 升到 26，且已过 13h → 允许再提醒（多次降价可多次申请）
        val higher = PriceProtectionPolicy.evaluate(
            purchasePrice = 1000.0,
            nowPrice = 740.0,
            purchasedAtMs = purchased,
            windowDays = 15,
            nowMs = lastAt + 13 * hour,
            alreadyNotified = true,
            lastNotifiedYuan = 200.0,
            lastNotifiedAtMs = lastAt
        )
        assertTrue("更高档位且 ≥12h 应再提醒", higher.shouldNotify)
        // 同一更高档但只过了 1 小时 → 节流
        val tooSoon = PriceProtectionPolicy.evaluate(
            purchasePrice = 1000.0,
            nowPrice = 740.0,
            purchasedAtMs = purchased,
            windowDays = 15,
            nowMs = lastAt + hour,
            alreadyNotified = true,
            lastNotifiedYuan = 200.0,
            lastNotifiedAtMs = lastAt
        )
        assertFalse("两次提醒至少间隔 12 小时", tooSoon.shouldNotify)
    }

    @Test
    fun `remaining 24 hours or less marks the reminder as urgent`() {
        val purchased = local(2026, 11, 1, 10, 0)
        val start = PriceProtectionPolicy.windowStartAtMs("taobao", purchased)
        val deadline = PriceProtectionPolicy.deadlineOf(start, windowDays = 15)
        val urgent = evaluateAt(1000.0, 800.0, purchased, 15, deadline - 2 * hour)
        assertTrue("最后一天应为高优先级", urgent.urgent)
        assertTrue(urgent.shouldNotify)
        val notUrgent = evaluateAt(1000.0, 800.0, purchased, 15, deadline - 2 * day)
        assertFalse(notUrgent.urgent)
    }

    // ---------- 6. 其它边界 ----------

    @Test
    fun `tier calculation and deadline helpers are deterministic`() {
        assertEquals(0, PriceProtectionPolicy.tierOf(0.0))
        assertEquals(0, PriceProtectionPolicy.tierOf(9.99))
        assertEquals(1, PriceProtectionPolicy.tierOf(10.0))
        assertEquals(2, PriceProtectionPolicy.tierOf(25.0))
        assertEquals(0L, PriceProtectionPolicy.deadlineOf(startAtMs = 0L, windowDays = 7))
        assertEquals(0L, PriceProtectionPolicy.deadlineOf(startAtMs = 100L, windowDays = 0))
        assertEquals(100L + 7 * day, PriceProtectionPolicy.deadlineOf(100L, windowDays = 7))
        assertEquals(100L + 72 * hour, PriceProtectionPolicy.deadlineOf(100L, windowDays = 3, windowHours = 72))
        assertNotNull(PriceProtectionPolicy.roundYuan(12.345))
        assertEquals(12.35, PriceProtectionPolicy.roundYuan(12.345), 0.001)
        assertEquals(0, PriceProtectionPolicy.remainingDays(remainingMs = 0L, windowDays = 7))
        assertEquals(1, PriceProtectionPolicy.remainingDays(remainingMs = 1L, windowDays = 7))
        assertEquals(3, PriceProtectionPolicy.remainingDays(remainingMs = 49 * hour, windowDays = 7))
    }

    // ---------- 辅助 ----------

    /** 默认按"淘宝/按天"口径评估（窗口起算点 = 购买时刻，除非显式传 windowStartAtMs） */
    private fun evaluateAt(
        purchasePrice: Double,
        nowPrice: Double?,
        purchasedAt: Long,
        windowDays: Int,
        now: Long,
        windowHours: Int = 0,
        windowStartAtMs: Long = 0L
    ): ProtectionStatus = PriceProtectionPolicy.evaluate(
        purchasePrice = purchasePrice,
        nowPrice = nowPrice,
        purchasedAtMs = purchasedAt,
        windowDays = windowDays,
        nowMs = now,
        alreadyNotified = false,
        windowHours = windowHours,
        windowStartAtMs = windowStartAtMs
    )

    private fun local(year: Int, month: Int, dayOfMonth: Int, hour: Int, minute: Int): Long = Calendar.getInstance().apply {
        set(year, month - 1, dayOfMonth, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
