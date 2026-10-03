package com.pricelens.coupon.ai

/**
 * 候选来源：兜底层要的是"同一句文案在多个入口/多套模板下的若干候选槽位"，
 * 而这些候选由找券流水线产出。本版流水线不在本工作树（任务书 A），所以这里只留注入点：
 * 接线时传一个 `CouponExtractor` 的适配（`candidates(clauses) = 三条规则路径的 discount/threshold`），
 * 测试里传固定表。接口只有一个纯字符串入参、一个纯数据出参，不逼实现方拿 Context。
 */
fun interface SlotCandidateSource {
    fun candidates(clauses: List<String>): List<FallbackCandidate>
}

/**
 * 确定性实现：**不依赖任何模型**，只做跨候选共识。它是"兜底"这一层今天就能落地的那半。
 *
 * 治的是什么：单条规则命中就直出，等于把"某套模板赌对了"当成"这张券存在"。
 * 同一段文案往往在剪贴板入口和页面入口各命中一次、且用的是不同模板 ——
 * 只有两边（或多种模板）都给出一样的面额与门槛，才够格升置信；
 * 给得不一样的，保留冲突标记降置信，让上层显示"待核验"而不是二选一硬猜。
 *
 * 没有模型也能跑，所以它随时可以在 `FallbackExtractor` 列表里排第一；
 * 未来模型实现排在它后面，`supports` 决定谁参与。
 */
class ConsensusFallbackExtractor(
    private val candidateSource: SlotCandidateSource,
    private val supportedKinds: Set<ExtractionInputKind> = ExtractionInputKind.entries.toSet()
) : FallbackExtractor {

    override fun supports(input: ExtractionInputKind): Boolean = input in supportedKinds

    /** 空输入一律 null（没内容可共识）；没有任何候选填过金额也返回 null（= 我没参与） */
    override fun extract(clauses: List<String>): FallbackDraft? {
        if (clauses.isEmpty()) return null
        return consensus(candidateSource.candidates(clauses))
    }

    companion object {

        /**
         * 共识本体（纯函数，判据都写在这里）：
         *  1. 每个槽位独立仲裁：取出现次数最多的值；**并列时取较小值** ——
         *     券面额高估的代价是用户按错的价去下单（承诺了不存在的优惠），
         *     低估只是少展示一点，两者不对称，所以偏向小的那个。
         *  2. 槽位支持度 = 给出该值的**去重来源数**（`source` + `templateId` 唯一）。
         *  3. `agreement` 取两个槽位支持度的较小值：面额 5 个来源一致、门槛只有 1 个来源
         *     给出过的草稿，不能算成 6 个共识。
         *  4. 任何槽位出现过 2 个以上不同取值 ⇒ 记冲突标记，全部取值都留在标记里（不丢证据）。
         *  5. 两个槽位都没人填 ⇒ null（宁缺毋滥，绝不返回"全 null 的草稿"让上层以为是结果）。
         */
        fun consensus(candidates: List<FallbackCandidate>): FallbackDraft? {
            if (candidates.isEmpty()) return null
            val discount = agree(candidates.mapNotNull { it.discount })
            val threshold = agree(candidates.mapNotNull { it.threshold })
            if (discount.value == null && threshold.value == null) return null
            val discountSupport = sourceCount(candidates.filter { it.discount == discount.value })
            val thresholdSupport = sourceCount(candidates.filter { it.threshold == threshold.value })
            val agreement = when {
                discount.value == null -> thresholdSupport
                threshold.value == null -> discountSupport
                else -> minOf(discountSupport, thresholdSupport)
            }
            val conflict = buildList {
                if (discount.seen.size > 1) add(marker("discount", discount.seen))
                if (threshold.seen.size > 1) add(marker("threshold", threshold.seen))
            }
            return FallbackDraft(discount.value, threshold.value, agreement, conflict)
        }

        /** 单个槽位的共识：value = 众数（并列取较小），seen = 升序去重后的全部取值 */
        private fun agree(values: List<Double>): Agreed {
            if (values.isEmpty()) return Agreed(null, emptyList())
            val counts = LinkedHashMap<Double, Int>()
            values.forEach { counts[it] = (counts[it] ?: 0) + 1 }
            val top = counts.values.max()
            val chosen = counts.filterValues { it == top }.keys.min()
            return Agreed(chosen, counts.keys.sorted())
        }

        /** 这批候选里 `source` + `templateId` 组合的唯一数（同一套模板重复命中不叠加） */
        private fun sourceCount(candidates: List<FallbackCandidate>): Int = candidates.map { it.source to it.templateId }.distinct().size

        /** 金额显示：12.0 写 12、12.5 写 12.5，避免标记里出现 `12.0|15.0` 这种噪音 */
        private fun amount(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

        private fun marker(slot: String, seen: List<Double>): String = "$slot:" + seen.joinToString("|") { amount(it) }

        /** [agree] 的中间结果 */
        private data class Agreed(val value: Double?, val seen: List<Double>)
    }
}

/**
 * 共识档位：无冲突 + 至少两个不同来源 → 升；孤证或重复来源 → 平；有槽位分歧 → 降。
 *
 * 2 这个下限的依据：`agreement == 1` 时这份草稿只有一条证据链，与"规则直出"没有信息增量，
 * 升置信就是在给自己盖章；而 ≥2 个**不同**来源给出同一对金额，才是流水线里
 * "同一类错误不再错三遍"的那个交叉验证信号。
 */
fun FallbackDraft.verdict(): ConsensusVerdict = when {
    conflict.isNotEmpty() -> ConsensusVerdict.DOWNWEIGHT
    agreement >= 2 -> ConsensusVerdict.UPLIFT
    else -> ConsensusVerdict.FLAT
}
