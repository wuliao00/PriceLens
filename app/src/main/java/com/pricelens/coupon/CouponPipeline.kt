package com.pricelens.coupon

import com.pricelens.coupon.adapters.PostAdapter
import com.pricelens.coupon.ai.FallbackDraft
import com.pricelens.coupon.ai.FallbackExtractor
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
import com.pricelens.coupon.slots.followedByRatioUnit
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
        vocabulary: CouponVocabulary = CouponVocabulary.DEFAULT,
        fallback: List<FallbackExtractor> = emptyList()
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
        // 兜底：**只在这一句什么都没抽到时**才唤起（规则先行、AI 兜底）。
        // 顺序不是优化而是纪律：模型每次要跑几十秒，而且在"规则已经读出来"的地方它只会带来分歧。
        // 唤醒条件（用户方案里的"规则先行、AI 兜底"）：规则**什么都没抽到**，
        // 或者只给出"形状词级"的低置信结果（CONTEXT_ONLY_CONFIDENCE）——
        // 后者才是模型真正有信息增量的地方：有券的形状、但模板一个都没套上。
        // 模板命中（≥0.75）一律不唤起：那里模型没有增量，只有几十秒的等待。
        val ruleConfidenceLow = bestConfidence <= CouponTemplates.CONTEXT_ONLY_CONFIDENCE
        if ((coupons.isEmpty() || ruleConfidenceLow) && fallback.isNotEmpty() && clauses.isNotEmpty()) {
            val kind = source.toInputKind()
            val clauseTexts = clauses.map { it.text }
            for (extractor in fallback) {
                if (!extractor.supports(kind)) continue
                val draft = extractor.extract(clauseTexts) ?: continue
                val slot = draft.toSlot(clauses, vocabulary) ?: continue
                // **模型只做加法，不做减法**（这条是 A/B 第一轮教出来的）：
                // 最初的策略是"低置信时用模型结果替换规则猜测"，结果 cm-faxian-01 上
                // 规则抽对了两张（499/500，角色词给的 0.5 置信），模型只回了 1 张 ⇒ 替换把对的那张也吃掉了，
                // 子集 F1 从 0.750 掉到 0.696。规则已经给出来的槽位，模型再准也**不动它**。
                val duplicate = coupons.any { it.discount == slot.discount && it.threshold == slot.threshold }
                if (!duplicate) {
                    coupons.add(slot)
                    bestConfidence = maxOf(bestConfidence, MODEL_ONLY_CONFIDENCE)
                }
                break // 一条证据就够：再多也是同一来源，"跨候选共识"在 ConsensusFallbackExtractor 那边攒
            }
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
                // 紧跟**费率单位**（`%` `折`）的是比率不是金额：`参与立减15%` 的 15 不是 15 元，
                // `国庆出行好物低至5折` 的 5 也不是到手价。
                // 评测集里前者被抽成过一张 ¥15 的券（误抽），而误抽比漏抽更贵 ——
                // 它会带着正确的 scope/state 一起出现，看起来像真券。
                if (followedByRatioUnit(clause.text, found.range.last + 1)) continue
                val value = found.value.replace(",", "").toDoubleOrNull() ?: continue
                // 角色的优先级不是风格问题：**价格词是比模板形状更强的信号**。
                // 尺子在数字左侧认出 售价/原价/划线/到手/券后/领后/降/跌 这类词时，那个数字就是价格，
                // 模板（含 subsidy-tail 这种按形状认领的）不许把它改成券面额 ——
                // 2026-10-05 全量评测抓到 `活动售价3499元,参与补贴…` 里的 3499 变成了券（同时 list 槽空掉），
                // 而这条正是 PLB110 上"模型把 5998 写成门槛"的同一个坑，只不过这次是我自己踩的。
                val judged = AmountRole.of(clause.text, start, vocabulary)
                val role = if (judged != null && judged.isPriceRole()) judged else declared[start]?.role ?: judged
                when (role) {
                    AmountRole.DISCOUNT, AmountRole.THRESHOLD -> discounts.add(AmountValue(value, start, found.range.last + 1, role))
                    AmountRole.FINAL, AmountRole.LIST, AmountRole.DROP -> prices.add(AmountValue(value, start, found.range.last + 1, role))
                    null -> Unit
                }
            }
        }
        val slots = slotsOf(discounts, clause, state, clauseScope, expiry, code, url, vocabulary, hits, hitSpanEndOf(hits))
        return ClauseOutcome(slots, priceOf(prices), confidenceOf(hits, slots))
    }

    /**
     * 每个被模板声明的数字，它所属**整段命中**的右端点（不含）。
     *
     * 证据段原本只截到"这张券最后一个数字"，那对 `满199减50` 是对的（角色词都在数字前面），
     * 但 `17元外卖餐补` 这类**角色词在数字后面**的形状就会被截成"17"——
     * 展示层写着"这句里读出来的：17"，用户既看不出抽对了也看不出抽错了（2026-10-05 接 #61 时撞出来的）。
     * 所以段要延伸到模板实际吃下的那一段原文末尾。
     */
    private fun hitSpanEndOf(hits: List<TemplateHit>): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        for (hit in hits) {
            val end = hit.start + hit.text.length
            for (amount in hit.amounts) {
                val previous = out[amount.start]
                if (previous == null || end > previous) out[amount.start] = end
            }
        }
        return out
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
        hits: List<TemplateHit>,
        hitEnds: Map<Int, Int>
    ): List<CouponSlot> {
        val out = ArrayList<CouponSlot>()
        var pending: AmountValue? = null
        var consumedEnd = 0
        for (value in values.sortedBy { it.start }) {
            if (value.role == AmountRole.THRESHOLD) {
                pending?.let {
                    val segment = segmentOf(clause, consumedEnd, it.end)
                    val scope = scopeOf(segment, clauseScope, vocabulary)
                    val threshold = thresholdValue(it.value, clause.text, vocabulary)
                    out.add(slotOf(null, threshold, clause, segment, state, scope, expiry, code, url))
                }
                pending = value
                continue
            }
            // 段尾三个来源取最右：本数字末尾、上一张券留下的门槛末尾、模板实际吃下的那段原文末尾
            val stop = maxOf(value.end, pending?.end ?: value.end, hitEnds[value.start] ?: value.end)
            val segment = segmentOf(clause, consumedEnd, stop)
            val scope = scopeOf(segment, clauseScope, vocabulary)
            val threshold = thresholdValue(pending?.value, clause.text, vocabulary)
            out.add(slotOf(value.value, threshold, clause, segment, state, scope, expiry, code, url))
            consumedEnd = stop
            pending = null
        }
        val evidence = hits.isNotEmpty() || CouponHints.looksLikeCouponText(clause.text, vocabulary)
        pending?.let {
            if (evidence) {
                val segment = segmentOf(clause, consumedEnd, it.end)
                val scope = scopeOf(segment, clauseScope, vocabulary)
                out.add(slotOf(null, thresholdValue(it.value, clause.text, vocabulary), clause, segment, state, scope, expiry, code, url))
            }
        }
        // 「有券但没金额」：整句**一个数字都没有**、又出现券名证据词 ⇒ 产一张全 null 的券。
        // 三个条件缺一不可，每一条都被真样本钉着：
        //  · 要产：底栏按钮「领券」(jd-instock-02)、首页角标「试用专享券」(jd-home-01)，
        //    页面确有可领券、只是文案没写面额 —— 评测集标的就是"全 null 的券"，不产就是漏检；
        //  · 「无数字」这条挡住：`下单返9折券`(jd-guobu-08)——有数字却没角色词，
        //    产出来的"9折券"是一张金额不明的券，而折扣活动不是券；
        //  · 用 emptyCouponWords（只有 券/领）而不是 couponHints（含 满/到手/折/省）挡住：
        //    「满赠」(jd-pl-04) 无数字但也不是券。**没有金额就没有可核验的东西**，
        //    所以这张券的置信只能是 CONTEXT_ONLY（0.5），展示层落在"待核验"档、逐槽显示"未识别"。
        if (out.isEmpty() && values.isEmpty() && !NUMBER.containsMatchIn(clause.text) &&
            vocabulary.emptyCouponWords.any { clause.text.contains(it) }
        ) {
            out.add(slotOf(null, null, clause, clause.text.trim(), state, clauseScope, expiry, code, url))
        }
        return out
    }

    /**
     * 门槛：显式写了「无门槛」的句子，门槛是 **0.0** 而不是 null。
     *
     * 两者不是一回事，评测集也是这么标的：`0.0` = 文案明说了"没有门槛"，
     * `null` = 文案没提门槛。展示层要靠它把"没读出门槛"与"不用凑单"分开说
     * —— 前者是我们的缺口，后者是用户可以直接下单。
     *
     * 判据用**整句**而不是"这张券那一段"：因为「无门槛」的常见位置在金额**后面**
     * （`【点击领取】¥70无门槛立减券`、`50元无门槛券`），而段只覆盖到本张券的最后一个数字，
     * 拿段判会永远看不见它 —— 第一版就是这么写的，测试里那条断言直接 NPE。
     */
    private fun thresholdValue(explicit: Double?, clauseText: String, vocabulary: CouponVocabulary): Double? =
        explicit ?: if (vocabulary.zeroThresholdWords.any { clauseText.contains(it) }) 0.0 else null

    /**
     * 这个角色是不是**价格**（到手/标价/降幅）。
     * 它是"模板不许把这个数字改成券"的判据 —— 价格词写在数字左边就是硬证据，
     * 而模板只看见形状（见 consume 里那段 2026-10-05 的说明）。
     */
    private fun AmountRole.isPriceRole(): Boolean = this == AmountRole.FINAL || this == AmountRole.LIST || this == AmountRole.DROP

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

    /**
     * 只有模型参与时那份券的基准置信。
     * 取 0.6：低于任何模板（0.8~0.95）、高于纯上下文兜底（0.5）——
     * "模型读出来的"比"只有形状词"可信，但比"模板与模型都认"弱，这是事实层面的排序。
     */
    internal const val MODEL_ONLY_CONFIDENCE = 0.6

    /**
     * 把兜底草稿变成一张券槽位。
     *
     * 能填的只有两个金额：scope/state 由**原文那句**判（判不出就是 UNKNOWN），expiry/code/url 一律 null ——
     * 兜底层不仲裁那三个槽位（见接缝契约），拿它的猜测当状态比留 UNKNOWN 更糟。
     * `sourceText` 取**含这张券数字的那一句**，与规则路径的证据句同口径。
     */
    private fun FallbackDraft.toSlot(clauses: List<Clause>, vocabulary: CouponVocabulary): CouponSlot? {
        val anchorValue = discount ?: threshold ?: return null
        val literal = amountLiteral(anchorValue)
        val evidence = clauses.firstOrNull { it.text.contains(literal) } ?: clauses.first()
        return CouponSlot(
            discount = discount,
            threshold = threshold,
            scope = ScopeWords.of(evidence.text, evidence.ancestors, vocabulary),
            state = StateWords.of(evidence.text, evidence.ancestors, vocabulary),
            expiry = null,
            code = null,
            url = null,
            sourceText = evidence.text,
            nodePath = evidence.nodePath
        )
    }

    /** 金额在文本里的常见写法（整数不带小数点；与 NUMBER 的两种形态对应） */
    private fun amountLiteral(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
}

/** 来源可靠度：剪贴板是用户自己复制的原文，节点树是二手呈现，社区帖是别人写的 */
internal fun ExtractSource.reliability(): Double = when (this) {
    ExtractSource.CLIPBOARD -> 1.0
    ExtractSource.PAGE_NODE -> 0.95
    ExtractSource.COMMUNITY -> 0.8
}
