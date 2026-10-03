package com.pricelens.coupon.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ConsensusFallbackExtractor] 与 [FallbackExtractor] 接缝的基线。
 *
 * 兜底层今天能落地的只有"跨候选共识"这一半，它的全部价值都在这几条判据上：
 *  - 只有**不同来源**给出同一对金额才升置信（同模板重复命中不算共识）；
 *  - 槽位有分歧时保留全部取值作为冲突标记并降置信，而不是二选一硬猜；
 *  - 没有候选 / 候选里两个槽位都是空 ⇒ 返回 null，让上层原样沿用规则结果。
 * 每条都同时断言"该放行的真放行"（positive control），不只测拒绝分支。
 */
class ConsensusFallbackExtractorTest {

    private val clipboard = ExtractionInputKind.CLIPBOARD
    private val page = ExtractionInputKind.PAGE_CLAUSES
    private val clauses = listOf("满199减50", "领券后到手149")

    private fun cand(
        source: ExtractionInputKind = clipboard,
        templateId: String = "full_reduce",
        discount: Double? = null,
        threshold: Double? = null
    ): FallbackCandidate = FallbackCandidate(source, templateId, discount, threshold)

    private fun extractor(vararg candidates: FallbackCandidate): ConsensusFallbackExtractor {
        return ConsensusFallbackExtractor(SlotCandidateSource { candidates.toList() })
    }

    // ---------- 接缝契约：没参与时必须返回 null ----------

    @Test
    fun `empty clause list never consults the candidate source`() {
        // 契约第 2 条：null 的含义是"我没参与"。空输入连来源都不该问，
        // 否则上层在"没有分句"时也会白跑一趟候选收集。
        val extractor = ConsensusFallbackExtractor(SlotCandidateSource { error("空输入不该来取候选") })
        assertNull(extractor.extract(emptyList()))
    }

    @Test
    fun `no candidates means no participation, not a failure`() {
        val draft = extractor().extract(clauses)
        assertNull(draft)
    }

    @Test
    fun `candidates carrying no amounts collapse to null instead of an empty draft`() {
        // 正例对照：下面那条填了面额的就不是 null
        assertNull(extractor(cand(), cand(source = page)).extract(clauses))
        val withAmount = extractor(cand(discount = 50.0)).extract(clauses)
        assertEquals(50.0, withAmount!!.discount, 0.0)
    }

    @Test
    fun `supports honors the declared kinds and refuses the rest`() {
        val clipboardOnly = ConsensusFallbackExtractor(SlotCandidateSource { emptyList() }, setOf(ExtractionInputKind.CLIPBOARD))
        assertTrue(clipboardOnly.supports(clipboard))
        assertFalse(clipboardOnly.supports(page))
        // 默认构造覆盖全部三种入口：新增入口时这条会红，提醒兜底层表态
        assertTrue(extractor().supports(ExtractionInputKind.COMMUNITY_POST))
    }

    // ---------- 升置信：跨来源一致 ----------

    @Test
    fun `two different templates agreeing on both slots uplifts with no conflict`() {
        val draft = extractor(
            cand(templateId = "full_reduce", discount = 50.0, threshold = 199.0),
            cand(templateId = "coupon_channel", discount = 50.0, threshold = 199.0)
        ).extract(clauses)!!
        assertEquals(50.0, draft.discount!!, 0.0)
        assertEquals(199.0, draft.threshold!!, 0.0)
        assertEquals(2, draft.agreement)
        assertTrue(draft.conflict.isEmpty())
        assertEquals(ConsensusVerdict.UPLIFT, draft.verdict())
    }

    @Test
    fun `cross entry agreement counts the page and clipboard paths as two sources`() {
        val draft = extractor(
            cand(source = clipboard, templateId = "full_reduce", discount = 50.0, threshold = 199.0),
            cand(source = page, templateId = "full_reduce", discount = 50.0, threshold = 199.0)
        ).extract(clauses)!!
        // 同一套模板、不同入口：这正是"三个入口都错"要治的场景，够格升
        assertEquals(2, draft.agreement)
        assertEquals(ConsensusVerdict.UPLIFT, draft.verdict())
    }

    // ---------- 不升：同模板重复命中 ----------

    @Test
    fun `five hits of the same template are repetition not consensus`() {
        val repeated = List(5) { cand(discount = 50.0, threshold = 199.0) }
        val draft = extractor(*repeated.toTypedArray()).extract(clauses)!!
        assertEquals(1, draft.agreement)
        assertTrue(draft.conflict.isEmpty())
        assertEquals(ConsensusVerdict.FLAT, draft.verdict())
    }

    @Test
    fun `agreement takes the weaker of the two slots`() {
        // 面额三个来源一致，门槛只有一个来源给过：不能报成三个共识
        val draft = extractor(
            cand(templateId = "t1", discount = 50.0, threshold = 199.0),
            cand(templateId = "t2", discount = 50.0),
            cand(templateId = "t3", discount = 50.0, threshold = 199.0)
        ).extract(clauses)!!
        assertEquals(50.0, draft.discount!!, 0.0)
        assertEquals(199.0, draft.threshold!!, 0.0)
        assertEquals(2, draft.agreement)
        assertEquals(ConsensusVerdict.UPLIFT, draft.verdict())
    }

    // ---------- 降置信：槽位分歧保留证据 ----------

    @Test
    fun `conflicting discounts are both kept in the marker and downweight the draft`() {
        val draft = extractor(
            cand(templateId = "t1", discount = 15.0, threshold = 199.0),
            cand(templateId = "t2", discount = 12.0, threshold = 199.0)
        ).extract(clauses)!!
        assertEquals(listOf("discount:12|15"), draft.conflict)
        assertEquals(ConsensusVerdict.DOWNWEIGHT, draft.verdict())
        // 门槛一致 ⇒ 不该出现在冲突标记里
        assertFalse(draft.conflict.any { it.startsWith("threshold") })
    }

    @Test
    fun `majority wins but the minority value stays visible`() {
        val draft = extractor(
            cand(templateId = "t1", discount = 15.0),
            cand(templateId = "t2", discount = 15.0),
            cand(templateId = "t3", discount = 12.0)
        ).extract(clauses)!!
        assertEquals(15.0, draft.discount!!, 0.0)
        assertEquals(2, draft.agreement)
        assertEquals(listOf("discount:12|15"), draft.conflict)
    }

    @Test
    fun `a tie resolves to the smaller amount because overpromising costs more`() {
        val draft = extractor(
            cand(templateId = "t1", discount = 15.0, threshold = 199.0),
            cand(templateId = "t2", discount = 12.0, threshold = 99.0)
        ).extract(clauses)!!
        assertEquals(12.0, draft.discount!!, 0.0)
        assertEquals(99.0, draft.threshold!!, 0.0)
        assertEquals(listOf("discount:12|15", "threshold:99|199"), draft.conflict)
        assertEquals(1, draft.agreement)
    }

    @Test
    fun `markers print whole amounts without a trailing decimal point`() {
        val draft = extractor(
            cand(templateId = "t1", discount = 12.0),
            cand(templateId = "t2", discount = 12.5)
        ).extract(clauses)!!
        assertEquals(listOf("discount:12|12.5"), draft.conflict)
    }

    @Test
    fun `a single populated slot is still a usable draft`() {
        val draft = extractor(
            cand(templateId = "t1", discount = 50.0),
            cand(templateId = "t2", discount = 50.0)
        ).extract(clauses)!!
        assertEquals(50.0, draft.discount!!, 0.0)
        assertNull(draft.threshold)
        assertTrue(draft.conflict.isEmpty())
        assertEquals(ConsensusVerdict.UPLIFT, draft.verdict())
    }

    @Test
    fun `consensus is callable without an extractor for the pipeline to reuse`() {
        val draft = ConsensusFallbackExtractor.consensus(
            listOf(cand(templateId = "t1", threshold = 99.0), cand(templateId = "t2", threshold = 99.0))
        )
        assertEquals(99.0, draft!!.threshold!!, 0.0)
        assertNull(draft.discount)
    }
}
