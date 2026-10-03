package com.pricelens.coupon

import com.pricelens.coupon.ai.ExtractionInputKind
import com.pricelens.coupon.ai.FallbackCandidate
import com.pricelens.coupon.ai.SlotCandidateSource
import com.pricelens.coupon.model.AmountRole
import com.pricelens.coupon.model.CouponSlot
import com.pricelens.coupon.model.ExtractSource
import com.pricelens.coupon.rules.CouponTemplates

/**
 * 理解层 ↔ 兜底层 的**唯一一处**接线。
 *
 * 两边刻意各自定义了中立结构（`ExtractSource` vs `ExtractionInputKind`、
 * `CouponSlot` vs `FallbackCandidate`），就是为了不让他们互相绑死：
 * 兜底层换运行时（规则共识 → NER → 端侧 LLM）不该动理解层，反之也一样。
 * ⇒ **映射只许出现在这个文件里**；别处再出现一个 `when (source)` 就说明有人绕过门面自己拼槽位了。
 */

/**
 * 入口类型映射。
 *
 * 名字对不上是刻意的：`ExtractSource` 回答"这条 Extraction 从哪来"，
 * `ExtractionInputKind` 回答"兜底器愿不愿意处理这一类输入"。
 * 这里做**穷尽 when**（没有 else），所以以后理解层加第四个入口时这一行会编译失败，
 * 逼着加的人替兜底层表态，而不是静默地让新入口走不到兜底。
 */
internal fun ExtractSource.toInputKind(): ExtractionInputKind = when (this) {
    ExtractSource.CLIPBOARD -> ExtractionInputKind.CLIPBOARD
    ExtractSource.COMMUNITY -> ExtractionInputKind.COMMUNITY_POST
    ExtractSource.PAGE_NODE -> ExtractionInputKind.PAGE_CLAUSES
}

/**
 * 券槽位 → 共识层候选。**只搬两个金额**。
 *
 * scope / state / expiry / url 留在 `CouponSlot` 里：兜底层只仲裁"面额与门槛到底是多少"，
 * 让它顺手改状态词判定等于把两类判据混进一个置信系数（D 侧 FallbackDraft 的注释同此）。
 */
internal fun CouponSlot.toCandidate(source: ExtractionInputKind, templateId: String): FallbackCandidate {
    return FallbackCandidate(source, templateId, discount, threshold)
}

/**
 * 真实候选来源：同一句文案在**每套模板**下解出的一对金额。
 *
 * 为什么"再跑一次模板匹配"是可接受的：D 侧把 `SlotCandidateSource` 的入参定成
 * `List<String>`（纯文本），就是为了换运行时时不用理解层的类型。代价说清楚 ——
 * `CouponTemplates.match` 会 `recordHit`，所以**命中计数变成"匹配执行次数"而不是"券数"**，
 * 兜底层每跑一次就给那些模板各加一次。要看"这句真的抽出了几张券"，读 `Extraction.coupons`，
 * 别读计数。
 *
 * 只声明了金额角色的模板才产出候选：`state-*` 那几条（已抢完 / 立即领 / 限地区…）
 * `amounts` 是空表，产出的候选两个槽位都是 null，会被共识层当成"我没参与"——
 * 与其让它产出一个空候选再被丢掉，不如在这里就不产出（少一个"看起来参与过"的假信号）。
 */
class TemplateSlotCandidateSource(private val templates: CouponTemplates, private val kind: ExtractionInputKind) : SlotCandidateSource {

    override fun candidates(clauses: List<String>): List<FallbackCandidate> = clauses.flatMap { text ->
        templates.match(text).mapNotNull { hit ->
            val discount = hit.amounts.firstOrNull { it.role == AmountRole.DISCOUNT }?.value
            val threshold = hit.amounts.firstOrNull { it.role == AmountRole.THRESHOLD }?.value
            val empty = discount == null && threshold == null
            if (empty) null else FallbackCandidate(kind, hit.templateId, discount, threshold)
        }
    }
}
