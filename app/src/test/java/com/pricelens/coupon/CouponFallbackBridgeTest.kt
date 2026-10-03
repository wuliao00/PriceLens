package com.pricelens.coupon

import com.pricelens.coupon.ai.ConsensusFallbackExtractor
import com.pricelens.coupon.ai.ConsensusVerdict
import com.pricelens.coupon.ai.ExtractionInputKind
import com.pricelens.coupon.ai.FallbackDraft
import com.pricelens.coupon.ai.verdict
import com.pricelens.coupon.model.CouponScope
import com.pricelens.coupon.model.CouponSlot
import com.pricelens.coupon.model.CouponState
import com.pricelens.coupon.model.ExtractSource
import com.pricelens.coupon.rules.CouponTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 理解层与兜底层之间那一处映射（`CouponFallbackBridge`）的钉子。
 *
 * 这一层最容易出的问题不是算错，而是**接线悄悄断掉**：入口类型映射漏一个分支、
 * 状态模板被当成"参与过共识的候选"、或者映射时把 scope/state 一起搬过去让兜底层有权改它们。
 * 所以每条都配正/负对照，而不是只测"能跑通"。
 */
class CouponFallbackBridgeTest {

    /** 每个用例造一份**新的**模板库：`match()` 会累计命中计数，共用 BUILTIN 会让用例互相污染 */
    private fun templates(): CouponTemplates = CouponTemplates.builtin()

    @Test
    fun `三个入口各自映射到兜底层的输入类型并且互不混用`() {
        val mapped = listOf(
            ExtractSource.CLIPBOARD to ExtractSource.CLIPBOARD.toInputKind(),
            ExtractSource.COMMUNITY to ExtractSource.COMMUNITY.toInputKind(),
            ExtractSource.PAGE_NODE to ExtractSource.PAGE_NODE.toInputKind()
        )
        assertEquals(
            listOf(
                ExtractionInputKind.CLIPBOARD,
                ExtractionInputKind.COMMUNITY_POST,
                ExtractionInputKind.PAGE_CLAUSES
            ),
            mapped.map { it.second }
        )
        // 反例：三档不许塌成同一个值（塌了就等于兜底层分不清是谁在问它）
        assertEquals(3, mapped.map { it.second }.distinct().size)
    }

    @Test
    fun `候选来源按模板产出两个金额槽位`() {
        val source = TemplateSlotCandidateSource(templates(), ExtractionInputKind.PAGE_CLAUSES)
        val candidates = source.candidates(listOf("满199减50"))
        assertEquals(1, candidates.size)
        val candidate = candidates[0]
        assertEquals("man-jian-pair", candidate.templateId)
        assertEquals(50.0, candidate.discount!!, 0.0)
        assertEquals(199.0, candidate.threshold!!, 0.0)
        assertEquals(ExtractionInputKind.PAGE_CLAUSES, candidate.source)
    }

    @Test
    fun `只说状态的模板不许产出候选`() {
        // "已抢完" 命中 state-sold-out，但那套模板没有金额槽位。
        // 若这里产出候选，共识层就会看到一个"两个槽位都空"的假参与者。
        val source = TemplateSlotCandidateSource(templates(), ExtractionInputKind.CLIPBOARD)
        val stateOnly = source.candidates(listOf("已抢完"))
        assertTrue("状态模板产出了候选：$stateOnly", stateOnly.isEmpty())
        // 正例对照：同一份来源喂真券文案是有产出的（证明上面那条空不是"来源整个坏了"）
        assertEquals(1, source.candidates(listOf("已抢完", "满199减50")).size)
    }

    @Test
    fun `多句文案的候选按句累加`() {
        val source = TemplateSlotCandidateSource(templates(), ExtractionInputKind.COMMUNITY_POST)
        val candidates = source.candidates(listOf("满199减50", "满299减80"))
        assertEquals(2, candidates.size)
        assertEquals(listOf(50.0, 80.0), candidates.map { it.discount })
        assertEquals(listOf(199.0, 299.0), candidates.map { it.threshold })
    }

    @Test
    fun `券槽位映射只搬两个金额其余留在原地`() {
        val slot = CouponSlot(
            discount = 50.0,
            threshold = 199.0,
            scope = CouponScope.SHOP,
            state = CouponState.SOLD_OUT,
            expiry = "2026-10-08",
            code = "ABCD1234",
            url = null,
            sourceText = "满199减50",
            nodePath = listOf(0, 2)
        )
        val candidate = slot.toCandidate(ExtractionInputKind.PAGE_CLAUSES, "man-jian-pair")
        assertEquals(50.0, candidate.discount!!, 0.0)
        assertEquals(199.0, candidate.threshold!!, 0.0)
        // 反例：兜底层的候选结构里根本没有 scope/state/expiry/code 这四个字段，
        // 所以"不许越权"这件事是由类型保证的 —— 这里断言的是映射没有把它们塞进 templateId 之类的位置
        assertEquals("man-jian-pair", candidate.templateId)
        assertEquals(ExtractionInputKind.PAGE_CLAUSES, candidate.source)
    }

    @Test
    fun `端到端-孤证只到 FLAT 而不会自己升档`() {
        val extractor = ConsensusFallbackExtractor(
            TemplateSlotCandidateSource(templates(), ExtractionInputKind.PAGE_CLAUSES)
        )
        val draft = extractor.clausesDraft("满199减50")
        // 一句话只命中一套模板 ⇒ 去重来源数 1 ⇒ 平（孤证不盖章，这是 D 侧契约第 2 条的形状）
        assertEquals(1, draft.agreement)
        assertEquals(ConsensusVerdict.FLAT, draft.verdict())
        assertEquals(50.0, draft.discount!!, 0.0)
        assertEquals(199.0, draft.threshold!!, 0.0)
    }

    @Test
    fun `端到端-两套模板只在一个槽位一致时按较弱的那个算`() {
        val extractor = ConsensusFallbackExtractor(
            TemplateSlotCandidateSource(templates(), ExtractionInputKind.CLIPBOARD)
        )
        // "满199减50" 走 man-jian-pair（两槽都有），"¥50无门槛券" 走 currency-quan（只有面额）
        // ⇒ 面额两个来源一致、门槛只有一个来源给过 ⇒ agreement 取较小值 1，不许报成 2
        val draft = extractor.clausesDraft("满199减50", "¥50无门槛券")
        assertEquals(50.0, draft.discount!!, 0.0)
        assertEquals(199.0, draft.threshold!!, 0.0)
        // 门槛只有一个来源给过，但它**没有第二个取值** ⇒ 不算冲突，只是支持度低
        assertTrue("不该有冲突标记，实际=${draft.conflict}", draft.conflict.isEmpty())
        assertEquals(1, draft.agreement)
        assertEquals(ConsensusVerdict.FLAT, draft.verdict())
    }
}

/** 测试侧小助手：把"喂分句 → 出草稿"写成一行，避免每个用例都重复构造 extractor */
private fun ConsensusFallbackExtractor.clausesDraft(vararg clauses: String): FallbackDraft {
    val draft = extract(clauses.toList())
    // null 的含义是"我没参与"（D 侧契约第 2 条）。用例前提不成立时要**响**，
    // 不许让下面的断言去跟一个 null 比 —— 那会把"没参与"读成"结果不对"。
    return draft ?: error("兜底层返回 null（没参与），本用例的输入前提不成立")
}
