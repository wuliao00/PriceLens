package com.pricelens.accuracy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PriceAccuracyEngine] 行为基线与规则边界（逐条对应 task-2 的 7 条规则）。
 *
 * 纯 JVM：不依赖 Android、不读真实时钟（nowMs 注入）、不发网络。
 * 覆盖点：
 *  - 档位：HIGH / MEDIUM / LOW / UNKNOWN 及各档位边界；
 *  - 口径：页面价 / 候选价 / 到手价 / 券后价 / 参考值不混为一谈，5%~15% 记口径差异、≥15% 记冲突；
 *  - 离群：中位数 + MAD 与小样本 max/min 比值兜底，离群点名且不给 HIGH；
 *  - 时鲜：>24h 记 stale 降档、时刻未知不算新鲜；
 *  - 相关性 <0.6 剔除；0/负价/NaN/∞ 剔除；
 *  - 来源健康板归一化、不可达/旧数据来源计数；
 *  - headline ≤14 字、reasons 非空且 ≤3 条、score 0..100 的权重可解释。
 */
class PriceAccuracyTest {

    // ---------- 构造器 ----------

    private fun input(
        keyword: String = "iPhone 16",
        pagePrice: Double? = null,
        pageSourceLabel: String? = null,
        candidatePrice: Double? = null,
        candidateTitle: String? = null,
        candidateRelevance: Double? = null,
        historyCurrent: Double? = null,
        historyLowest: Double? = null,
        historyStale: Boolean = false,
        couponTotal: Double? = null,
        netPrice: Double? = null,
        extraRemotePrices: List<RemotePrice> = emptyList(),
        sourceCells: List<SourceCell> = emptyList(),
        nowMs: Long = FIXED_NOW
    ): AccuracyInput = AccuracyInput(
        keyword = keyword,
        pagePrice = pagePrice,
        pageSourceLabel = pageSourceLabel,
        candidatePrice = candidatePrice,
        candidateTitle = candidateTitle,
        candidateRelevance = candidateRelevance,
        historyCurrent = historyCurrent,
        historyLowest = historyLowest,
        historyStale = historyStale,
        couponTotal = couponTotal,
        netPrice = netPrice,
        extraRemotePrices = extraRemotePrices,
        sourceCells = sourceCells,
        nowMs = nowMs
    )

    private fun evaluate(
        keyword: String = "iPhone 16",
        pagePrice: Double? = null,
        pageSourceLabel: String? = null,
        candidatePrice: Double? = null,
        candidateTitle: String? = null,
        candidateRelevance: Double? = null,
        historyCurrent: Double? = null,
        historyLowest: Double? = null,
        historyStale: Boolean = false,
        couponTotal: Double? = null,
        netPrice: Double? = null,
        extraRemotePrices: List<RemotePrice> = emptyList(),
        sourceCells: List<SourceCell> = emptyList()
    ): AccuracySnapshot = PriceAccuracyEngine.evaluate(
        input(
            keyword = keyword,
            pagePrice = pagePrice,
            pageSourceLabel = pageSourceLabel,
            candidatePrice = candidatePrice,
            candidateTitle = candidateTitle,
            candidateRelevance = candidateRelevance,
            historyCurrent = historyCurrent,
            historyLowest = historyLowest,
            historyStale = historyStale,
            couponTotal = couponTotal,
            netPrice = netPrice,
            extraRemotePrices = extraRemotePrices,
            sourceCells = sourceCells
        )
    )

    private fun evaluateWithAges(input: AccuracyInput, ageHints: Map<String, Long?>): AccuracySnapshot =
        PriceAccuracyEngine.evaluateWithAges(input, ageHints)

    // ---------- 规则 2：档位 ----------

    @Test
    fun `three fresh sources agreeing reach HIGH with full score`() {
        val snapshot = evaluate(
            pagePrice = 100.0,
            pageSourceLabel = "本机京东 App 登录账号",
            candidatePrice = 100.0,
            candidateRelevance = 1.0,
            extraRemotePrices = listOf(RemotePrice("shihuo", "识货", 101.0, 1.0))
        )
        assertEquals(ConfidenceLevel.HIGH, snapshot.level)
        assertEquals(100, snapshot.score)
        assertEquals("3 源一致 · 可信", snapshot.headline)
        assertTrue(snapshot.conflicts.isEmpty())
        assertTrue(snapshot.reasons.isNotEmpty())
        assertEquals(3, snapshot.evidence.size)
    }

    @Test
    fun `two fresh agreeing sources with high relevance reach HIGH`() {
        val snapshot = evaluate(
            pagePrice = 100.0,
            pageSourceLabel = "页面",
            candidatePrice = 100.0,
            candidateRelevance = 1.0
        )
        assertEquals(ConfidenceLevel.HIGH, snapshot.level)
        assertEquals(85, snapshot.score)
        assertEquals("2 源一致 · 可信", snapshot.headline)
    }

    @Test
    fun `two agreeing sources without fresh evidence fall to LOW`() {
        val snapshot = evaluateWithAges(
            input(candidatePrice = 100.0, historyCurrent = 100.0),
            mapOf(PriceAccuracyEngine.SOURCE_CANDIDATE to PriceAccuracyEngine.STALE_AGE_MS + 1L)
        )
        assertEquals(ConfidenceLevel.LOW, snapshot.level)
        assertTrue(snapshot.evidence.first { it.sourceId == PriceAccuracyEngine.SOURCE_CANDIDATE }.stale)
    }

    @Test
    fun `single fresh page price is MEDIUM`() {
        val snapshot = evaluate(pagePrice = 100.0, pageSourceLabel = "本机京东 App 登录账号")
        assertEquals(ConfidenceLevel.MEDIUM, snapshot.level)
        assertEquals(65, snapshot.score)
        assertEquals(PriceBasis.PAGE, snapshot.basis)
        assertEquals("页面价 · 单源可信", snapshot.headline)
    }

    @Test
    fun `single fresh candidate price is MEDIUM with candidate basis`() {
        val snapshot = evaluate(candidatePrice = 100.0, candidateRelevance = 1.0)
        assertEquals(ConfidenceLevel.MEDIUM, snapshot.level)
        assertEquals(PriceBasis.UNKNOWN, snapshot.basis)
        assertEquals("候选价", snapshot.basisLabel)
        assertEquals("单源数据 · 建议页面核价", snapshot.headline)
    }

    @Test
    fun `history-only evidence is LOW with history basis`() {
        val snapshot = evaluate(historyCurrent = 100.0, historyLowest = 80.0)
        assertEquals(ConfidenceLevel.LOW, snapshot.level)
        assertEquals(PriceBasis.UNKNOWN, snapshot.basis)
        assertEquals("历史当前价", snapshot.basisLabel)
        assertEquals("仅历史参考 · 谨慎下单", snapshot.headline)
        assertEquals(45, snapshot.score)
        assertTrue(snapshot.conflicts.isEmpty())
    }

    @Test
    fun `three sources with unknown age history still reach HIGH`() {
        val snapshot = evaluate(
            pagePrice = 100.0,
            pageSourceLabel = "页面",
            candidatePrice = 100.0,
            candidateRelevance = 1.0,
            historyCurrent = 100.0
        )
        assertEquals(ConfidenceLevel.HIGH, snapshot.level)
        assertEquals("3 源一致 · 可信", snapshot.headline)
        assertTrue(snapshot.reasons.any { it.contains("时刻未知") })
    }

    @Test
    fun `no usable price yields UNKNOWN with zero score`() {
        val snapshot = evaluate()
        assertEquals(ConfidenceLevel.UNKNOWN, snapshot.level)
        assertEquals(0, snapshot.score)
        assertEquals("暂无价格 · 无法核价", snapshot.headline)
        assertEquals("暂无价格", snapshot.basisLabel)
        assertTrue(snapshot.evidence.isEmpty())
        assertTrue(snapshot.reasons.isNotEmpty())
    }

    @Test
    fun `all sources blocked yields UNKNOWN and reports unreachable count`() {
        val snapshot = evaluate(
            sourceCells = listOf(
                SourceCell("smzdm", "什么值得买", SourceState.BLOCKED, "反爬拦截"),
                SourceCell("shihuo", "识货", SourceState.BLOCKED, "反爬拦截"),
                SourceCell("bilibili", "B站", SourceState.BLOCKED, "反爬拦截")
            )
        )
        assertEquals(ConfidenceLevel.UNKNOWN, snapshot.level)
        assertTrue(snapshot.reasons.any { it.contains("3 个来源不可达") })
        assertEquals(3, snapshot.sourceBoard.size)
    }

    // ---------- 规则 1/2：口径与一致 / 冲突 ----------

    @Test
    fun `five percent gap between page and candidate is a visible basis difference`() {
        val snapshot = evaluate(
            pagePrice = 5999.0,
            pageSourceLabel = "页面",
            candidatePrice = 5699.0,
            candidateTitle = "iPhone 16",
            candidateRelevance = 1.0
        )
        assertNotEquals(ConfidenceLevel.HIGH, snapshot.level)
        assertEquals(1, snapshot.conflicts.size)
        val detail = snapshot.conflicts.first().detail
        assertEquals("口径差异", snapshot.conflicts.first().label)
        assertTrue(detail.contains("¥5,999"))
        assertTrue(detail.contains("¥5,699"))
        assertTrue(detail.contains("相差 5%"))
        assertTrue(detail.contains("可能是不同规格/券后"))
        assertEquals(detail, snapshot.reasons.first())
    }

    @Test
    fun `difference within five percent is consistent`() {
        val snapshot = evaluate(
            pagePrice = 100.0,
            pageSourceLabel = "页面",
            candidatePrice = 104.0,
            candidateRelevance = 1.0
        )
        assertTrue(snapshot.conflicts.isEmpty())
        assertEquals(ConfidenceLevel.HIGH, snapshot.level)
    }

    @Test
    fun `difference above fifteen percent is a hard conflict and caps confidence`() {
        val snapshot = evaluate(
            pagePrice = 100.0,
            pageSourceLabel = "页面",
            candidatePrice = 130.0,
            candidateRelevance = 1.0
        )
        assertEquals(ConfidenceLevel.LOW, snapshot.level)
        assertEquals("口径冲突 · 请核对规格", snapshot.headline)
        assertEquals("价格冲突", snapshot.conflicts.first().label)
        assertTrue(snapshot.conflicts.first().detail.contains("相差 23%"))
        assertTrue(snapshot.reasons.first().contains("不是同款"))
    }

    @Test
    fun `net price equal to coupon-after is deduplicated and basis is NET`() {
        val snapshot = evaluate(candidatePrice = 100.0, candidateRelevance = 1.0, couponTotal = 10.0, netPrice = 90.0)
        assertEquals(PriceBasis.NET, snapshot.basis)
        assertEquals("到手价", snapshot.basisLabel)
        assertTrue(snapshot.conflicts.isEmpty())
        assertTrue(snapshot.evidence.none { it.basis == PriceBasis.COUPON_AFTER })
        assertTrue(snapshot.evidence.any { it.basis == PriceBasis.NET && it.price == 90.0 })
    }

    @Test
    fun `coupon-after evidence appears when net price is missing`() {
        val snapshot = evaluate(candidatePrice = 100.0, candidateRelevance = 1.0, couponTotal = 20.0)
        assertEquals(PriceBasis.COUPON_AFTER, snapshot.basis)
        assertEquals("券后价", snapshot.basisLabel)
        assertTrue(snapshot.evidence.any { it.basis == PriceBasis.COUPON_AFTER && it.price == 80.0 })
    }

    @Test
    fun `coupon basis mismatch is reported separately from price conflicts`() {
        val snapshot = evaluate(candidatePrice = 100.0, candidateRelevance = 1.0, couponTotal = 10.0, netPrice = 50.0)
        assertTrue(snapshot.conflicts.any { it.label == "券口径" })
        assertTrue(snapshot.conflicts.any { it.label == "价格冲突" })
    }

    @Test
    fun `reference lowest price is not compared as current price`() {
        val snapshot = evaluate(
            pagePrice = 100.0,
            pageSourceLabel = "页面",
            historyCurrent = 100.0,
            historyLowest = 50.0
        )
        assertEquals(ConfidenceLevel.MEDIUM, snapshot.level)
        assertTrue(snapshot.conflicts.isEmpty())
        assertTrue(snapshot.evidence.any { it.basis == PriceBasis.REFERENCE && it.price == 50.0 })
        assertTrue(snapshot.evidence.any { it.basis == PriceBasis.PAGE })
    }

    // ---------- 规则 3：离群 ----------

    @Test
    fun `outlier is named and prevents HIGH`() {
        val snapshot = evaluate(
            candidatePrice = 100.0,
            candidateTitle = "iPhone 16",
            candidateRelevance = 1.0,
            extraRemotePrices = listOf(
                RemotePrice("dangdang", "当当", 102.0, 1.0),
                RemotePrice("shihuo", "识货", 500.0, 1.0)
            )
        )
        assertEquals(ConfidenceLevel.MEDIUM, snapshot.level)
        assertNotEquals(ConfidenceLevel.HIGH, snapshot.level)
        val text = snapshot.reasons.joinToString(" / ")
        assertTrue(text.contains("离群"))
        assertTrue(text.contains("¥500"))
        assertTrue(text.contains("中位"))
    }

    // ---------- 规则 2/3：无效价与相关性剔除 ----------

    @Test
    fun `zero negative NaN and infinite prices are dropped`() {
        val snapshot = evaluate(
            pagePrice = 0.0,
            candidatePrice = -5.0,
            historyCurrent = Double.NaN,
            historyLowest = -1.0,
            netPrice = Double.POSITIVE_INFINITY,
            extraRemotePrices = listOf(RemotePrice("shihuo", "识货", 0.0, 1.0))
        )
        assertEquals(ConfidenceLevel.UNKNOWN, snapshot.level)
        assertEquals(0, snapshot.score)
        assertTrue(snapshot.evidence.isEmpty())
    }

    @Test
    fun `low relevance evidence is dropped and reported`() {
        val snapshot = evaluate(
            candidatePrice = 100.0,
            candidateRelevance = 1.0,
            extraRemotePrices = listOf(RemotePrice("smzdm", "什么值得买", 200.0, 0.5))
        )
        assertEquals(ConfidenceLevel.MEDIUM, snapshot.level)
        assertEquals(1, snapshot.evidence.size)
        assertTrue(snapshot.evidence.none { it.price == 200.0 })
        assertTrue(snapshot.reasons.any { it.contains("相关度低于 0.6") })
    }

    @Test
    fun `relevance lowers the relevance component by expected points`() {
        val trusted = evaluate(candidatePrice = 100.0, candidateRelevance = 1.0)
        val lessTrusted = evaluate(candidatePrice = 100.0, candidateRelevance = 0.7)
        assertEquals(65, trusted.score)
        assertEquals(59, lessTrusted.score)
        assertEquals(6, trusted.score - lessTrusted.score)
    }

    // ---------- 规则 4：时鲜度 ----------

    @Test
    fun `unknown data time is not treated as fresh`() {
        val default = evaluate(candidatePrice = 100.0, candidateRelevance = 1.0, historyCurrent = 100.0)
        assertEquals(ConfidenceLevel.MEDIUM, default.level)
        assertTrue(default.reasons.any { it.contains("时刻未知") })

        val historyFresh = evaluateWithAges(
            input(candidatePrice = 100.0, candidateRelevance = 1.0, historyCurrent = 100.0),
            mapOf(PriceAccuracyEngine.SOURCE_HISTORY to 0L)
        )
        assertEquals(ConfidenceLevel.HIGH, historyFresh.level)
    }

    @Test
    fun `age over twenty four hours marks evidence stale and downgrades`() {
        val snapshot = evaluateWithAges(
            input(candidatePrice = 100.0, candidateRelevance = 1.0),
            mapOf(PriceAccuracyEngine.SOURCE_CANDIDATE to PriceAccuracyEngine.STALE_AGE_MS + 1L)
        )
        assertEquals(ConfidenceLevel.LOW, snapshot.level)
        val candidate = snapshot.evidence.first { it.sourceId == PriceAccuracyEngine.SOURCE_CANDIDATE }
        assertTrue(candidate.stale)
        assertTrue(snapshot.reasons.any { it.contains("小时") })
    }

    @Test
    fun `history stale flag marks evidence stale and downgrades`() {
        val snapshot = evaluate(historyCurrent = 100.0, historyLowest = 80.0, historyStale = true)
        assertEquals(ConfidenceLevel.LOW, snapshot.level)
        assertTrue(snapshot.evidence.all { it.stale })
        assertTrue(snapshot.reasons.any { it.contains("旧数据") })
    }

    // ---------- 规则 5：来源健康板 ----------

    @Test
    fun `source board is normalized and deduplicated`() {
        val snapshot = evaluate(
            sourceCells = listOf(
                SourceCell("x", " 标签 ", SourceState.BLOCKED, " 反爬 "),
                SourceCell("x", "重复条目", SourceState.OK, null),
                SourceCell(" y ", "", SourceState.STALE, "   ")
            )
        )
        assertEquals(2, snapshot.sourceBoard.size)
        assertEquals("x", snapshot.sourceBoard[0].sourceId)
        assertEquals("标签", snapshot.sourceBoard[0].label)
        assertEquals("反爬", snapshot.sourceBoard[0].note)
        assertEquals("y", snapshot.sourceBoard[1].sourceId)
        assertEquals("y", snapshot.sourceBoard[1].label)
        assertTrue("空备注应归一成 null", snapshot.sourceBoard[1].note == null)
    }

    @Test
    fun `unreachable and stale sources are counted in reasons`() {
        val snapshot = evaluate(
            pagePrice = 100.0,
            pageSourceLabel = "页面",
            sourceCells = listOf(
                SourceCell("smzdm", "什么值得买", SourceState.BLOCKED, "反爬拦截"),
                SourceCell("shihuo", "识货", SourceState.DEGRADED, "网络失败"),
                SourceCell("mmb", "慢慢买", SourceState.STALE, "旧数据"),
                SourceCell("dd", "当当", SourceState.OK, null)
            )
        )
        val text = snapshot.reasons.joinToString(" / ")
        assertTrue(text.contains("2 个来源不可达"))
        assertTrue(text.contains("1 个来源返回旧数据"))
    }

    // ---------- 规则 6：headline / reasons ----------

    @Test
    fun `headline is capped at fourteen characters for every level`() {
        val snapshots = listOf(
            evaluate(
                pagePrice = 100.0,
                pageSourceLabel = "页面",
                candidatePrice = 100.0,
                candidateRelevance = 1.0,
                extraRemotePrices = listOf(RemotePrice("shihuo", "识货", 101.0, 1.0))
            ),
            evaluate(pagePrice = 100.0, pageSourceLabel = "页面", candidatePrice = 100.0, candidateRelevance = 1.0),
            evaluate(pagePrice = 100.0, pageSourceLabel = "本机京东 App 登录账号"),
            evaluate(candidatePrice = 100.0, candidateRelevance = 1.0),
            evaluate(historyCurrent = 100.0, historyLowest = 80.0),
            evaluate(historyCurrent = 100.0, historyLowest = 80.0, historyStale = true),
            evaluate(pagePrice = 100.0, pageSourceLabel = "页面", candidatePrice = 130.0, candidateRelevance = 1.0),
            evaluate(pagePrice = 100.0, pageSourceLabel = "页面", candidatePrice = 106.0, candidateRelevance = 1.0),
            evaluate()
        )
        for (snapshot in snapshots) {
            assertTrue("headline 不能为空", snapshot.headline.isNotBlank())
            assertTrue("headline 超过 14 字: ${snapshot.headline}", snapshot.headline.length <= 14)
            assertTrue("reasons 不能为空", snapshot.reasons.isNotEmpty())
            assertTrue("reasons 超过 3 条: ${snapshot.reasons}", snapshot.reasons.size <= 3)
            assertTrue("reasons 不能有空串", snapshot.reasons.all { it.isNotBlank() })
        }
    }

    @Test
    fun `reasons are capped at three`() {
        val snapshot = evaluate(
            pagePrice = 100.0,
            pageSourceLabel = "页面",
            candidatePrice = 130.0,
            candidateRelevance = 1.0,
            extraRemotePrices = listOf(RemotePrice("smzdm", "什么值得买", 200.0, 0.3)),
            sourceCells = listOf(
                SourceCell("a", "来源A", SourceState.BLOCKED, "反爬"),
                SourceCell("b", "来源B", SourceState.BLOCKED, "反爬"),
                SourceCell("c", "来源C", SourceState.DEGRADED, "网络失败"),
                SourceCell("d", "来源D", SourceState.STALE, "旧数据"),
                SourceCell("e", "来源E", SourceState.STALE, "旧数据")
            )
        )
        assertTrue(snapshot.reasons.isNotEmpty())
        assertTrue(snapshot.reasons.size <= 3)
    }

    // ---------- 规则 7：score 权重 ----------

    @Test
    fun `score stays within bounds and conflict lowers it`() {
        val agreeing = evaluate(
            pagePrice = 100.0,
            pageSourceLabel = "页面",
            candidatePrice = 100.0,
            candidateRelevance = 1.0
        )
        val conflicting = evaluate(
            pagePrice = 100.0,
            pageSourceLabel = "页面",
            candidatePrice = 200.0,
            candidateRelevance = 1.0
        )
        assertTrue(conflicting.score < agreeing.score)
        val scenarios = listOf(
            evaluate(),
            evaluate(candidatePrice = 100.0),
            evaluate(historyCurrent = 100.0, historyLowest = 80.0),
            agreeing,
            conflicting
        )
        for (snapshot in scenarios) {
            assertTrue("score 越界: ${snapshot.score}", snapshot.score in 0..100)
        }
    }

    // ---------- 契约守卫 ----------

    @Test
    fun `contract enums keep exact names and order`() {
        assertEquals(
            listOf("HIGH", "MEDIUM", "LOW", "UNKNOWN"),
            ConfidenceLevel.values().map { it.name }
        )
        assertEquals(
            listOf("PAGE", "COUPON_AFTER", "NET", "REFERENCE", "UNKNOWN"),
            PriceBasis.values().map { it.name }
        )
        assertEquals(
            listOf("OK", "DEGRADED", "BLOCKED", "STALE", "UNKNOWN"),
            SourceState.values().map { it.name }
        )
        assertEquals(
            listOf("WRONG_PRICE", "WRONG_PRODUCT", "STALE", "WRONG_BASIS"),
            AccuracyIssueKind.values().map { it.name }
        )
    }

    @Test
    fun `generated time and evidence fields are preserved`() {
        val snapshot = evaluate(
            pagePrice = 100.0,
            pageSourceLabel = "本机京东 App 登录账号",
            candidatePrice = 100.0,
            candidateRelevance = 1.0
        )
        assertEquals(FIXED_NOW, snapshot.generatedAtMs)
        val page = snapshot.evidence.first { it.sourceId == PriceAccuracyEngine.SOURCE_PAGE }
        assertEquals(PriceBasis.PAGE, page.basis)
        assertEquals(100.0, page.price ?: 0.0, 0.0)
        assertEquals("本机京东 App 登录账号", page.label)
        assertEquals(1.0, page.relevance, 0.0)
        assertFalse(page.stale)
        assertEquals(0L, page.ageMs)
    }

    private companion object {
        /** 固定注入时刻，保证快照可重放（引擎内部不读时钟） */
        const val FIXED_NOW: Long = 1_780_000_000_000L
    }
}
