package com.pricelens.coupon

import com.pricelens.coupon.ai.ExtractionInputKind
import com.pricelens.coupon.ai.FallbackDraft
import com.pricelens.coupon.ai.FallbackExtractor
import com.pricelens.coupon.model.CouponState
import com.pricelens.coupon.model.ExtractSource
import com.pricelens.coupon.normalize.Clause
import com.pricelens.coupon.rules.CouponTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 兜底接缝在**管线里的位置**：规则先行、AI 兜底。
 *
 * 这一条不是优化而是纪律，两个方向都要钉死：
 *  - 规则抽不到 → 唤起兜底（否则兜底层永远不被调用，等于没接）；
 *  - 规则抽到了 → **一次都不许调模型**（模型几十秒一次；在它没有信息增量的地方调用，
 *    只会拖慢并引入分歧）。
 */
class CouponPipelineFallbackTest {

    /** 一个"只会说 17 元"的假兜底：用来观察它**被调了几次**，以及它的产出进了哪里 */
    private class CountingFallback : FallbackExtractor {
        var calls = 0
        override fun supports(input: ExtractionInputKind): Boolean = true
        override fun extract(clauses: List<String>): FallbackDraft? {
            calls += 1
            return FallbackDraft(discount = 17.0, threshold = null, agreement = 1, conflict = emptyList())
        }
    }

    private fun extract(text: String, fallback: List<FallbackExtractor>) = CouponPipeline.extract(
        clauses = listOf(Clause(text = text)),
        source = ExtractSource.COMMUNITY,
        platform = "unknown",
        itemRef = null,
        templates = CouponTemplates.builtin(),
        fallback = fallback
    )

    @Test
    fun `规则没抽到才唤起兜底并且产出带证据句与未知状态`() {
        val fallback = CountingFallback()
        // 「17元外卖餐补」：没有模板命中、也没有角色词 ⇒ 规则层一张券都产不出
        val extraction = extract("17元外卖餐补", listOf(fallback))
        assertEquals(1, fallback.calls)
        assertEquals(1, extraction.coupons.size)
        val slot = extraction.coupons[0]
        assertEquals(17.0, slot.discount!!, 0.0)
        // 兜底层不仲裁状态/范围 ⇒ 必须是 UNKNOWN，而不是拿猜测顶上
        assertEquals(CouponState.UNKNOWN, slot.state)
        // 证据句按"含这张券数字的那一句"取，与规则路径同口径
        assertEquals("17元外卖餐补", slot.sourceText)
        // 只有模型参与 ⇒ 基准 0.6 × 社区帖可靠度 0.8 = 0.48（低置信档，但**不丢弃**）
        assertEquals(0.48, extraction.confidence, 0.0001)
    }

    @Test
    fun `规则抽到了就一次都不调模型`() {
        val fallback = CountingFallback()
        val extraction = extract("满199减50", listOf(fallback))
        assertEquals(0, fallback.calls)
        assertEquals(1, extraction.coupons.size)
        assertEquals(50.0, extraction.coupons[0].discount!!, 0.0)
    }

    @Test
    fun `低置信时模型只做加法不动规则已经给出的券`() {
        // 这条是 A/B 第一轮教出来的：最初的策略是"低置信就用模型结果替换规则猜测"，
        // 结果 cm-faxian-01 上规则抽对两张（角色词给的 0.5 置信）、模型只回 1 张 ⇒ 替换把对的那张也吃了。
        // 「参与官方限时补贴减499元」只命中角色词（没有模板）⇒ 置信 0.5 = 低置信 ⇒ 会唤起兜底
        val fallback = CountingFallback()
        val extraction = extract("参与官方限时补贴减499元", listOf(fallback))
        assertEquals(1, fallback.calls)
        // 规则那张还在（499），模型的 17 也进来了 ⇒ 两张，而不是被替换成一张
        val values = extraction.coupons.map { it.discount }
        assertTrue("规则那张不许被模型替换掉，实际=$values", values.contains(499.0))
        assertTrue("模型的新数字应当能补进来，实际=$values", values.contains(17.0))
        assertEquals(2, extraction.coupons.size)
    }

    @Test
    fun `没有兜底时行为与改造前完全一致`() {
        val extraction = extract("17元外卖餐补", emptyList())
        assertTrue(extraction.coupons.isEmpty())
        assertEquals(0.0, extraction.confidence, 0.0)
    }

    @Test
    fun `兜底返回 null 等于没参与不改动结果`() {
        val silent = object : FallbackExtractor {
            override fun supports(input: ExtractionInputKind) = true
            override fun extract(clauses: List<String>): FallbackDraft? = null
        }
        val extraction = extract("17元外卖餐补", listOf(silent))
        assertTrue(extraction.coupons.isEmpty())
    }
}
