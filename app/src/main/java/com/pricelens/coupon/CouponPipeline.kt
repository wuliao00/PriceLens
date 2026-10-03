package com.pricelens.coupon

import com.pricelens.coupon.adapters.PostAdapter
import com.pricelens.coupon.model.AmountRole
import com.pricelens.coupon.model.CouponScope
import com.pricelens.coupon.model.CouponSlot
import com.pricelens.coupon.model.CouponState
import com.pricelens.coupon.model.ExtractSource
import com.pricelens.coupon.model.Extraction
import com.pricelens.coupon.model.ExtractorKind
import com.pricelens.coupon.model.PriceSlots
import com.pricelens.coupon.normalize.Clause
import com.pricelens.coupon.normalize.Links
import com.pricelens.coupon.rules.CouponTemplates
import com.pricelens.coupon.rules.TemplateHit
import com.pricelens.coupon.slots.CouponHints
import com.pricelens.coupon.slots.CouponVocabulary
import com.pricelens.coupon.slots.ScopeWords
import com.pricelens.coupon.slots.StateWords
import com.pricelens.coupon.slots.of

/**
 * 三入口共用的抽取管线（**"一个数字进哪个槽"在这里决定**，适配器只负责把句子喂进来）。
 *
 * 一个句子内部的处置顺序（每步都在治任务书里的一类病根）：
 *  1. 模板命中 ⇒ 拿到"这半句是券"的证据与置信（治"没有槽位定义"）；
 *  2. 扫出句子里**所有**数字：角色优先取模板声明的（模板里数字与角色一一对应），
 *     模板没覆盖的数字再由 `AmountRole.of` 按"离该数字最近的上下文词"判
 *     —— 治金额槽位混淆：`本单可减1500` 走 `减` ⇒ DISCOUNT，不会被当成降幅，
 *     而 `到手价¥92.9` 走 `到手` ⇒ FINAL，永远不会变成券面额；
 *  3. DISCOUNT / THRESHOLD ⇒ 券槽位；FINAL / LIST / DROP ⇒ 价格槽位（两个类型物理分开）；
 *  4. 一句里出现多个面额 ⇒ 拆成多张券，门槛按"左邻"绑定（治多券混并）；每张券的**范围只看自己那一段**
 *     （见 [scopeOf]：否则"店铺券满199减20，品类券满300减50"拆成两张后仍共用"店铺"这个范围）；
 *  5. 状态 / 范围按词表判（治状态词失明）；
 *  6. 判不出角色的数字**不产生任何槽位**（宁缺毋滥），低置信也**不丢弃**。
 *
 * internal：对外只有 [CouponExtractor] 三个入口，不给"绕过管线自己拼槽位"留口子。
 */
internal object CouponPipeline {

    /** 数字形态：先试千分位（4,999），再试普通小数；小数位限 1..2，免得把后面的编号吃掉 */
    private val NUMBER = Regex("\\d{1,3}(?:,\\d{3})+(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?")

    /** 到期日：只认写明年月的形态，两位月/日都允许 */
    private val FULL_DATE = Regex("(\\d{4})[年\\-/](\\d{1,2})[月\\-/](\\d{1,2})日?")

    private val CODE = Regex("(?:券码|口令|兑换码|优惠码)\\s*[:：]?\\s*([A-Za-z0-9]{4,16})")

    fun extract(
        clauses: List<Clause>,
        source: ExtractSource,
        platform: String,
        itemRef: String?,
        templates: CouponTemplates,
        ageDays: Long? = null,
        vocabulary: CouponVocabulary = CouponVocabulary.DEFAULT
    ): Extraction {
        val coupons = ArrayList<CouponSlot>()
        var finalPrice: Double? = null
        var listPrice: Double? = null
        var drop: Double? = null
        var bestConfidence = 0.0
        var stack: String? = null
        var staleSeen = false
        for (clause in clauses) {
            val outcome = consume(clause, templates, vocabulary)
            coupons.addAll(outcome.slots)
            finalPrice = finalPrice ?: outcome.price.finalPrice
            listPrice = listPrice ?: outcome.price.listPrice
            drop = drop ?: outcome.price.drop
            bestConfidence = maxOf(bestConfidence, outcome.confidence)
            stack = stack ?: clause.stackNote
            staleSeen = staleSeen || clause.stale
        }
        val nothing = coupons.isEmpty() && finalPrice == null && listPrice == null && drop == null
        val decay = decayFactor(staleSeen, ageDays)
        val confidence = if (nothing) 0.0 else (bestConfidence * source.reliability() * decay).coerceIn(0.0, 1.0)
        return Extraction(
            source = source,
            extractor = ExtractorKind.RULE,
            platform = platform,
            itemRef = itemRef,
            coupons = coupons,
            price = PriceSlots(finalPrice, listPrice, drop),
            stackNote = noteOf(stack, staleSeen || decay < 1.0),
            confidence = confidence
        )
    }

    /** 一句 → 该句的券槽位 + 价格槽位 + 句级置信。单独可测：不经过任何适配器 */
    internal fun consume(clause: Clause, templates: CouponTemplates, vocabulary: CouponVocabulary): ClauseOutcome {
        val hits = templates.match(clause.text)
        val declared = hits.flatMap { it.amounts }.associateBy { it.start }
        val state = stateOf(hits, clause, vocabulary)
        val clauseScope = ScopeWords.of(clause.text, clause.ancestors, vocabulary)
        val expiry = expiryOf(clause.text)
        val code = CODE.find(clause.text)?.groupValues?.get(1)
        val url = Links.decode(clause.text).firstOrNull()?.value
        val discounts = ArrayList<AmountValue>()
        val prices = ArrayList<AmountValue>()
        if (CouponHints.numbersUsable(clause.text, vocabulary)) {
            for (found in NUMBER.findAll(clause.text)) {
                val start = found.range.first
                val value = found.value.replace(",", "").toDoubleOrNull() ?: continue
                when (val role = declared[start]?.role ?: AmountRole.of(clause.text, start, vocabulary)) {
                    AmountRole.DISCOUNT, AmountRole.THRESHOLD -> discounts.add(AmountValue(value, start, found.range.last + 1, role))
                    AmountRole.FINAL, AmountRole.LIST, AmountRole.DROP -> prices.add(AmountValue(value, start, found.range.last + 1, role))
                    null -> Unit
                }
            }
        }
        val slots = slotsOf(discounts, clause, state, clauseScope, expiry, code, url, vocabulary, hits)
        return ClauseOutcome(slots, priceOf(prices), confidenceOf(hits, slots))
    }

    /**
     * 券槽位配对：按句内出现顺序扫，THRESHOLD 暂存，遇到下一个 DISCOUNT 成一张券。
     * `满199减50 满299减80` ⇒ 两张；收尾只剩门槛（`满299可用` 没写面额）⇒
     * 只有句子里**有券证据**（模板命中或券形状词）才出一张 discount=null 的券。
     */
    private fun slotsOf(
        values: List<AmountValue>,
        clause: Clause,
        state: CouponState,
        clauseScope: CouponScope,
        expiry: String?,
        code: String?,
        url: String?,
        vocabulary: CouponVocabulary,
        hits: List<TemplateHit>
    ): List<CouponSlot> {
        val out = ArrayList<CouponSlot>()
        var pending: AmountValue? = null
        var consumedEnd = 0
        for (value in values.sortedBy { it.start }) {
            if (value.role == AmountRole.THRESHOLD) {
                pending?.let {
                    val segment = segmentOf(clause, consumedEnd, it.end)
                    val scope = scopeOf(segment, clauseScope, vocabulary)
                    out.add(slotOf(null, it.value, clause, segment, state, scope, expiry, code, url))
                }
                pending = value
                continue
            }
            val stop = maxOf(value.end, pending?.end ?: value.end)
            val segment = segmentOf(clause, consumedEnd, stop)
            val scope = scopeOf(segment, clauseScope, vocabulary)
            out.add(slotOf(value.value, pending?.value, clause, segment, state, scope, expiry, code, url))
            consumedEnd = stop
            pending = null
        }
        val evidence = hits.isNotEmpty() || CouponHints.looksLikeCouponText(clause.text, vocabulary)
        pending?.let {
            if (evidence) {
                val segment = segmentOf(clause, consumedEnd, it.end)
                val scope = scopeOf(segment, clauseScope, vocabulary)
                out.add(slotOf(null, it.value, clause, segment, state, scope, expiry, code, url))
            }
        }
        return out
    }

    /** 这张券**自己那一段**原文（上一张券结束处 → 本张券最后一个数字） */
    private fun segmentOf(clause: Clause, from: Int, to: Int): String {
        val length = clause.text.length
        val start = from.coerceIn(0, length)
        val stop = to.coerceIn(start, length)
        val segment = clause.text.substring(start, stop).trim().trimStart('，', ',', '、', '；', ';')
        // 段为空（两张券的数字紧挨着）时退回整句：宁可给长一点的证据，也不给空串，
        // 因为空串在展示层与错例导出里都会被读成"这句没内容"而不是"这段没内容"。
        // 剥掉段首的标点：它是**上一张券留下的分隔符**，不属于这张券，留在证据句里
        // 会让展示层出现"，品类券满300减50"这种半截句（用户会以为抽错了句子）。
        return if (segment.isEmpty()) clause.text else segment
    }

    /**
     * 这张券的范围：只看**它自己那一段**（上一张券之后到本张券的最后一个数字），段里判得出就用段的。
     *
     * 为什么不能整句判：`店铺券满199减20，品类券满300减50，平台券满500减100` 已经被数字配对拆成
     * 三张独立的券了，但按整句判范围时第一个词（店铺）会染到后两张 —— 范围错的券和没拆开的券
     * 一样会误导"能不能叠"。段里判不出（范围词写在数字后面，如 `满199减20的店铺券`）才退回整句，
     * 保证"拆多张"不会把单张券的范围从 SHOP 判成 UNKNOWN（正/反例见 CouponPipelineTest）。
     */
    private fun scopeOf(segment: String, clauseScope: CouponScope, vocabulary: CouponVocabulary): CouponScope {
        if (segment.isBlank()) return clauseScope
        val local = ScopeWords.of(segment, emptyList(), vocabulary)
        return if (local == CouponScope.UNKNOWN) clauseScope else local
    }

    private fun slotOf(
        discount: Double?,
        threshold: Double?,
        clause: Clause,
        segment: String,
        state: CouponState,
        scope: CouponScope,
        expiry: String?,
        code: String?,
        url: String?
    ): CouponSlot = CouponSlot(discount, threshold, scope, state, expiry, code, url, segment, clause.nodePath)

    /** 状态：模板命中的状态词优先（有 id 可追溯），没有再按词表判整句 + 上下文 */
    private fun stateOf(hits: List<TemplateHit>, clause: Clause, vocabulary: CouponVocabulary): CouponState {
        val fromHits = CouponVocabulary.STATE_PRIORITY.firstOrNull { state -> hits.any { it.state == state } }
        return fromHits ?: StateWords.of(clause.text, clause.ancestors, vocabulary)
    }

    private fun priceOf(values: List<AmountValue>): PriceSlots {
        fun first(role: AmountRole): Double? = values.firstOrNull { it.role == role }?.value
        return PriceSlots(first(AmountRole.FINAL), first(AmountRole.LIST), first(AmountRole.DROP))
    }

    /** 句级置信 = 命中模板里最高的那条；没模板却出了券 ⇒ 兜底值；什么都没有 ⇒ 0 */
    private fun confidenceOf(hits: List<TemplateHit>, slots: List<CouponSlot>): Double {
        val best = hits.maxOfOrNull { it.confidence }
        if (best != null) return best
        return if (slots.isNotEmpty()) CouponTemplates.CONTEXT_ONLY_CONFIDENCE else 0.0
    }

    /** 只认写明年月的日期；`10月8日` 不补年份 —— 补 2026 还是 2027 都是编造 */
    internal fun expiryOf(text: String): String? {
        val found = FULL_DATE.find(text) ?: return null
        val (year, month, day) = found.destructured
        val numericYear = year.toIntOrNull() ?: return null
        val numericMonth = month.toIntOrNull() ?: return null
        val numericDay = day.toIntOrNull() ?: return null
        if (numericMonth !in 1..12 || numericDay !in 1..31) return null
        return "%04d-%02d-%02d".format(numericYear, numericMonth, numericDay)
    }

    /** 时效衰减：句内日期判定为历史，或调用方告知帖子年龄超过阈值 ⇒ 乘 0.5 */
    private fun decayFactor(stale: Boolean, ageDays: Long?): Double {
        if (stale) return 0.5
        val days = ageDays ?: return 1.0
        return if (days > PostAdapter.STALE_AFTER_DAYS) 0.5 else 1.0
    }

    /**
     * `历史信息` 记进 stackNote：V1 的 [Extraction] 没有别的自由文本字段，
     * 而"这条是三个月前的爆料"必须让展示层看得见 —— 用现有字段带出去，不偷偷丢弃。
     */
    private fun noteOf(stack: String?, stale: Boolean): String? {
        if (!stale) return stack
        return listOfNotNull("历史信息", stack).joinToString("；")
    }

    /** 一句的处理结果（三个字段分别进 [Extraction.coupons] / [Extraction.price] / 置信） */
    internal data class ClauseOutcome(val slots: List<CouponSlot>, val price: PriceSlots, val confidence: Double)

    /** 句内一个带角色的数字（[end] 是"数字之后第一个下标"，配对时用它切出每张券自己的那段） */
    internal data class AmountValue(val value: Double, val start: Int, val end: Int, val role: AmountRole)
}

/** 来源可靠度：剪贴板是用户自己复制的原文，节点树是二手呈现，社区帖是别人写的 */
internal fun ExtractSource.reliability(): Double = when (this) {
    ExtractSource.CLIPBOARD -> 1.0
    ExtractSource.PAGE_NODE -> 0.95
    ExtractSource.COMMUNITY -> 0.8
}
