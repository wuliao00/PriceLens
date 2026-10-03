package com.pricelens.coupon.ai

/**
 * 一个候选：同一句文案在**某个入口 × 某套模板**下解出的一对金额槽位。
 *
 * 由找券流水线（任务书 A 的 rules/adapters）产出后喂给兜底层；本版不实现产出方，
 * 只在 [ConsensusFallbackExtractor] 的消费侧定义最小中立结构 —— 等 model 落地后
 * 由控制面把 `CouponSlot` 映射成它（只映射 discount/threshold 两个字段，
 * scope/state/expiry/url 留在 Extraction 里，兜底层不参与那些槽位的仲裁）。
 */
data class FallbackCandidate(val source: ExtractionInputKind, val templateId: String, val discount: Double?, val threshold: Double?)

/**
 * 跨候选共识的结果。四个字段是最小中立面：`com.pricelens.coupon.model.CouponSlot`
 * 落地后由控制面把这两个金额合并回那张券的槽位，其余槽位不动。
 *
 *  - [discount] / [threshold]：共识值。判不出（该槽位没有任何候选填过）就是 null，**不默认成 0**。
 *  - [agreement]：认可这份草稿的**去重来源数**（`source` + `templateId` 组合的唯一数），
 *    取两个槽位里的较小值。用"去重来源数"而不是"候选条数"是刻意的：同一套模板命中 5 次
 *    不叫共识，只叫重复——重复不该升置信（见 [ConsensusVerdict.FLAT] 那条用例）。
 *  - [conflict]：冲突标记，形如 `discount:12|15`（升序、`|` 分隔的**全部**不同取值）。
 *    空 = 没有任何槽位存在分歧。冲突不丢弃结果，只降置信并留下证据（上层展示"待核验"）。
 */
data class FallbackDraft(val discount: Double?, val threshold: Double?, val agreement: Int, val conflict: List<String>)

/**
 * 共识强度档位：上层据此调 confidence（乘/不乘/降），具体系数由流水线侧定，兜底层不定。
 * 之所以不直接返回 Double，是因为"升多少"要跟 `ExtractorKind`、来源可靠度一起算，
 * 那些量都在任务书 A 里；这里只给三档判定，避免两处各写一份打分公式。
 */
enum class ConsensusVerdict {
    /** 无冲突且至少两个不同来源认可：升置信 */
    UPLIFT,

    /** 只有孤证或重复来源：不动置信 */
    FLAT,

    /** 槽位有分歧：降置信，并把 [FallbackDraft.conflict] 透出到展示层 */
    DOWNWEIGHT
}
