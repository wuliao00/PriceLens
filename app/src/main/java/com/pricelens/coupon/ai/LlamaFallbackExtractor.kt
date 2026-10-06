package com.pricelens.coupon.ai

import com.pricelens.coupon.model.AmountRole
import com.pricelens.coupon.slots.CouponVocabulary
import com.pricelens.coupon.slots.followedByRatioUnit
import com.pricelens.coupon.slots.of
import com.pricelens.coupon.slots.ratioCappedAmount

/**
 * 端侧模型的兜底实现（`FallbackExtractor` 的一个真身）。
 *
 * **它的输出不许直接进结果**：模型只负责"把这段文本读成两个数字"，而那两个数字要过一遍
 * 确定性复核 —— 这不是防御性编程，是 PLB110 上抓到的实证：
 * 给「…活动售价5998元，参与官方限时补贴减499元…」，模型抽出 `discount=499` ✓
 * 但顺手把 5998 写成了 `threshold`（原文里 5998 是**售价**，不是门槛）。
 * 所以规则是：
 *  1. 模型给的数字必须能在原文里**逐字找到**（找不到 = 编的，直接丢）；
 *  2. 那个数字的角色要由**规则层的尺子**判：判成同角色 → 采信；
 *     判成别的角色（如 5998 被判价格）→ **丢掉**（尺子比模型可信）；
 *     判不出角色（原文确实有、但没有角色词）→ 采信但**降置信**，并把它记进 `conflict`，
 *     让上层显示"待核验"而不是当成确定结果。
 *
 * 它**不做**的事：判 scope/state/expiry（那些留在理解层）、合并多券（那是逐句调用的事）。
 */
class LlamaFallbackExtractor(
    private val model: ClauseModel,
    private val vocabulary: CouponVocabulary = CouponVocabulary.DEFAULT,
    private val supportedKinds: Set<ExtractionInputKind> = ExtractionInputKind.entries.toSet()
) : FallbackExtractor {

    override fun supports(input: ExtractionInputKind): Boolean = input in supportedKinds

    override fun extract(clauses: List<String>): FallbackDraft? {
        if (clauses.isEmpty()) return null
        // 分句符与 Clauses 的口径一致（；），这样模型看到的是"一句一行"而不是一锅粥
        val joined = clauses.joinToString(separator = "；")
        val raw = model.run(joined) ?: return null // 没模型/加载失败/超长都是这一条
        val draft = LlamaDraftParser.parse(raw) ?: return null

        val discount = check(draft.discount, AmountRole.DISCOUNT, joined)
        val threshold = check(draft.threshold, AmountRole.THRESHOLD, joined)
        if (discount.value == null && threshold.value == null) return null
        val conflict = ArrayList<String>()
        if (draft.discount != null && discount.value == null) conflict.add("discount:模型给了 ${amount(draft.discount)}，原文里没有可采信的锚点")
        if (draft.threshold != null && threshold.value == null) conflict.add("threshold:模型给了 ${amount(draft.threshold)}，原文里没有可采信的锚点")
        // "采信了但规则判不出角色"也要留痕：那是**降置信**的依据（verdict() 见 conflict 非空就 DOWNWEIGHT），
        // 不然一条只靠模型撑着的数字会以"平档"混过去 —— 它比"规则+模型都认"弱一档，这是事实层面的差别
        discount.note?.let { conflict.add(it) }
        threshold.note?.let { conflict.add(it) }
        // agreement=1：这是**一条**证据（模型自己），跨候选共识由 ConsensusFallbackExtractor 去攒
        return FallbackDraft(discount.value, threshold.value, agreement = 1, conflict)
    }

    /** 复核结果：采信的值 + 需要留痕的说明（[note] 非空 ⇒ 上层按"待核验"处理） */
    private data class Checked(val value: Double?, val note: String?)

    /** 见类注释的三条规则；`value = null` 表示这个槽位不采信 */
    private fun check(value: Double?, role: AmountRole, text: String): Checked {
        if (value == null) return Checked(null, null)
        val start = numberStart(text, value) ?: return Checked(null, null)
        val judged = AmountRole.of(text, start, vocabulary)
        return when (judged) {
            role -> Checked(value, null)
            // 规则判不出角色但数字确实在原文 —— 采信，但要留痕（降置信）
            null -> Checked(value, "$role:模型给的 ${amount(value)} 原文里没有角色词，按模型结果待核验")
            // 规则判成别的角色（售价/到手价/降幅…）—— 丢掉，尺子优先
            else -> Checked(null, null)
        }
    }

    /**
     * 原文里这个数值的起始下标；找不到返回 null（模型编数的最直接判据）。
     *
     * 与管线**共用同一个费率守卫** `slots.followedByRatioUnit`：紧跟 `%` 或 `折` 的数字是比率不是金额
     * （`国家补贴至高省15%`、`国庆出行好物低至5折`），模型说是券也不采信 ——
     * 提示词刚放开「补贴/省」之后，这一类特别容易变成误抽。
     * 共用而不是各写一份：2026-10-05 之前这里是同名的一份拷贝，靠注释维持"两处要一起改"。
     */
    private fun numberStart(text: String, value: Double): Int? {
        for (found in NUMBER.findAll(text)) {
            if (followedByRatioUnit(text, found.range.last + 1)) continue
            // 与管线共用**同一把**比例封顶闸：`补贴15%起减500元` 的 500 在原文里确实有，
            // 但它是"最高减到"的上限不是能领的券 —— 模型说它是券也不采信，
            // 否则规则那条路明确拒掉的形状，换个入口就漏进结果里了
            if (ratioCappedAmount(text, found.range.first)) continue
            val parsed = found.value.replace(",", "").toDoubleOrNull() ?: continue
            if (parsed == value) return found.range.first
        }
        return null
    }

    private fun amount(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

    private companion object {
        /** 与 `CouponPipeline.NUMBER` 同形（那边是 private；两处改动要一起改） */
        val NUMBER = Regex("\\d{1,3}(?:,\\d{3})+(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?")
    }
}

/**
 * "把一段文本交给模型"这一件事的最小接口（`fun interface`，测试里一个 lambda 就能替身）。
 *
 * 为什么不直接依赖 [LlamaRuntime]：那个类要真的加载 400MB 权重才能构造，
 * 拿它做单测等于"没有手机就测不了复核逻辑"——而**要测的恰恰是复核**。
 * 生产接线：`LlamaFallbackExtractor(ClauseModel { runtime.extract(it) })`。
 */
fun interface ClauseModel {
    fun run(text: String): String?
}
