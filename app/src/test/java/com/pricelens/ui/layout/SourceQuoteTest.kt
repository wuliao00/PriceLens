package com.pricelens.ui.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 概览「各源报价」单行列表的排序/分组规则（2026-10-02 密度大改）。
 *
 * 这三条规则决定首屏"能不能一眼看出该看哪一行"，所以必须是可测的纯函数，
 * 而不是散在 LazyColumn 里的 `sortedBy` + 一段注释：
 *  1. 有价的在前，够不着的沉底但**保留**（失败不是没发生）；
 *  2. 同状态按通道优先级（本机账号所见 > 主候选 > 历史 > 券后 > 外站补充源）；
 *  3. 同通道按价格升序，无价排最后；再同价按输入稳定序。
 */
class SourceQuoteTest {

    private fun yuan(value: Double) = "¥${value.toInt()}"

    private fun input(
        kind: QuoteKind,
        label: String,
        price: Double?,
        state: QuoteState = QuoteState.PRICED,
        stamp: String? = null,
        note: String? = null
    ) = QuoteInput(kind, label, price, state, stamp, note)

    private fun List<SourceQuote>.labels() = map { it.kind }

    @Test
    fun `未查询的源不占版面`() {
        val rows = SourceQuotes.of(
            listOf(
                input(QuoteKind.ACCOUNT, "本机实时价", 99.0),
                QuoteInput(QuoteKind.SHIHUO, "识货报价", null, QuoteState.IDLE)
            ),
            ::yuan
        )
        assertEquals(1, rows.size)
        assertEquals(QuoteKind.ACCOUNT, rows.first().kind)
    }

    @Test
    fun `有价在前失败与反爬沉底`() {
        val rows = SourceQuotes.of(
            listOf(
                input(QuoteKind.POST, "值得买爆料", null, QuoteState.BLOCKED),
                input(QuoteKind.HISTORY, "慢慢买历史", null, QuoteState.FAILED),
                input(QuoteKind.MAIN, "商品现价", null, QuoteState.NO_RESULT),
                input(QuoteKind.SHIHUO, "识货报价", 88.0),
                input(QuoteKind.COUPON, "券后到手", null, QuoteState.LOADING)
            ),
            ::yuan
        )
        assertEquals(
            listOf(QuoteState.PRICED, QuoteState.LOADING, QuoteState.NO_RESULT, QuoteState.FAILED, QuoteState.BLOCKED),
            rows.map { it.state }
        )
    }

    @Test
    fun `同状态按通道优先级排序`() {
        val rows = SourceQuotes.of(
            listOf(
                input(QuoteKind.POST, "值得买", 90.0),
                input(QuoteKind.MAIN, "主候选", 95.0),
                input(QuoteKind.SHIHUO, "识货", 91.0),
                input(QuoteKind.ACCOUNT, "本机", 99.0),
                input(QuoteKind.HISTORY, "慢慢买", 92.0),
                input(QuoteKind.COUPON, "券后", 93.0)
            ),
            ::yuan
        )
        assertEquals(
            listOf(
                QuoteKind.ACCOUNT,
                QuoteKind.MAIN,
                QuoteKind.HISTORY,
                QuoteKind.COUPON,
                QuoteKind.SHIHUO,
                QuoteKind.POST
            ),
            rows.labels()
        )
    }

    @Test
    fun `同通道按价格升序`() {
        val rows = SourceQuotes.of(
            listOf(
                input(QuoteKind.POST, "爆料乙", 199.0),
                input(QuoteKind.POST, "爆料甲", 89.0),
                input(QuoteKind.POST, "爆料丙", 120.0)
            ),
            ::yuan
        )
        assertEquals(listOf("爆料甲", "爆料丙", "爆料乙"), rows.map { it.label })
    }

    /** 防御：同状态同通道里"有价"在前，价缺失的排后面（不指望调用方一定给全） */
    @Test
    fun `缺价排最后`() {
        val rows = SourceQuotes.of(
            listOf(
                input(QuoteKind.POST, "爆料乙", 199.0),
                input(QuoteKind.POST, "爆料甲", null),
                input(QuoteKind.POST, "爆料丙", 89.0)
            ),
            ::yuan
        )
        assertEquals(listOf("爆料丙", "爆料乙", "爆料甲"), rows.map { it.label })
    }

    /** 长中文源名必须收敛到版面预算内（最坏负载：6 个 14sp 汉字 = 84dp < 可用 90.86dp） */
    @Test
    fun `源名按版面预算截断`() {
        val rows = SourceQuotes.of(listOf(input(QuoteKind.ACCOUNT, "本机京东账号实时价", 99.0)), ::yuan)
        assertEquals(6, TextTruncate.units(rows.first().label))
        assertEquals("本机京东账…", rows.first().label)
    }

    @Test
    fun `价格文案用注入的格式化器`() {
        val rows = SourceQuotes.of(listOf(input(QuoteKind.MAIN, "商品现价", 12999.0)), ::yuan)
        assertEquals("¥12999", rows.first().priceText)
    }

    /** 时间戳缺失就如实缺省：不编"刚刚"，也不摆一个空占位 */
    @Test
    fun `时间戳空串归一为 null`() {
        val rows = SourceQuotes.of(
            listOf(
                input(QuoteKind.HISTORY, "慢慢买历史", 99.0, stamp = "  "),
                input(QuoteKind.MAIN, "商品现价", 98.0, stamp = "2026-10-01")
            ),
            ::yuan
        )
        assertNull(rows.first { it.kind == QuoteKind.HISTORY }.stampText)
        assertEquals("2026-10-01", rows.first { it.kind == QuoteKind.MAIN }.stampText)
    }

    @Test
    fun `分两段且有价段在前`() {
        val rows = SourceQuotes.of(
            listOf(
                input(QuoteKind.ACCOUNT, "本机实时价", 99.0),
                input(QuoteKind.POST, "值得买爆料", null, QuoteState.BLOCKED),
                input(QuoteKind.MAIN, "商品现价", 98.0, stamp = "3 小时前")
            ),
            ::yuan
        )
        val groups = SourceQuotes.sections(rows)
        assertEquals(listOf(QuoteSection.PRICED, QuoteSection.DIAGNOSTIC), groups.map { it.section })
        assertEquals(2, groups.first().rows.size)
        assertEquals(listOf(QuoteKind.POST), groups[1].rows.map { it.kind })
    }

    /** 空段不返回：不给列表留一条只有分隔线的段 */
    @Test
    fun `空段不返回`() {
        val groups = SourceQuotes.sections(SourceQuotes.of(listOf(input(QuoteKind.MAIN, "商品现价", 98.0)), ::yuan))
        assertEquals(1, groups.size)
        assertEquals(QuoteSection.PRICED, groups.first().section)
        assertTrue(groups.none { it.rows.isEmpty() })
    }

    /** 同状态同通道同价：保持输入顺序（稳定排序），否则每次重组行会跳 */
    @Test
    fun `同键保持输入顺序`() {
        val rows = SourceQuotes.of(
            listOf(
                input(QuoteKind.SHIHUO, "识货乙", 99.0),
                input(QuoteKind.SHIHUO, "识货甲", 99.0),
                input(QuoteKind.SHIHUO, "识货丙", 99.0)
            ),
            ::yuan
        )
        assertEquals(listOf("识货乙", "识货甲", "识货丙"), rows.map { it.label })
    }
}
