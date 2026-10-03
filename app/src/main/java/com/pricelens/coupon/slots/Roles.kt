package com.pricelens.coupon.slots

import com.pricelens.coupon.model.AmountRole
import com.pricelens.coupon.model.CouponScope
import com.pricelens.coupon.model.CouponState

/**
 * 角色 / 状态 / 范围三个判定（**这一层就是"金额槽位混淆"的治疗位置**）。
 *
 * 三条共同纪律：
 *  1. 只看**形状**（词在句中的位置、词与数字的相对距离），不看数值大小 ——
 *     "1500 太大了不可能是券面额"这种推理一旦写进来，抽取就变成了拟合；
 *  2. 判不出返回 null（角色）或 UNKNOWN（状态/范围），**绝不默认成 DISCOUNT**；
 *  3. 词表全部来自 [CouponVocabulary]，默认参数可整体换成远端规则包下发的版本。
 */

/**
 * 判 [numberStart] 处那个数字的角色：**只看数字之前的上下文**，取"离数字最近"的词。
 *
 * 为什么是"之前 + 最近"而不是"整句里有没有减/满"：`满4999减300` 一句里同时有 THRESHOLD 和
 * DISCOUNT 两个信号，整句判法只能给一个标签，两个数字必有一个被判错。
 * 按"结束下标最大"取词，同下标取**长词**（`无门槛` 压 `门槛`，见词表注释）。
 *
 * @param numberStart 数字在 [clause] 中的起始下标（模板命中时就是捕获组的 range.first）
 * @return 判不出返回 null —— 调用方（`CouponPipeline`）据此**不产生**券槽位
 */
fun AmountRole.Companion.of(clause: String, numberStart: Int, vocabulary: CouponVocabulary = CouponVocabulary.DEFAULT): AmountRole? {
    if (clause.isEmpty() || numberStart <= 0) return null
    val window = clause.take(numberStart.coerceAtMost(clause.length))
    var best: AmountRole? = null
    var bestEnd = -1
    var bestLength = -1
    for ((role, rules) in vocabulary.amountRoles) {
        for (rule in rules) {
            val end = rule.lastEndIn(window) ?: continue
            val length = rule.token.length
            if (end > bestEnd || (end == bestEnd && length > bestLength)) {
                best = role
                bestEnd = end
                bestLength = length
            }
        }
    }
    return best
}

/**
 * 券状态判定：先判整句，判不出再判祖先/同层文案（[ancestors]）。
 *
 * [ancestors] 的口径由 `NodeAdapter` 决定：祖先链文案 **+ 同一行的兄弟按钮文案**
 * （真机券卡片的"领取"按钮常是券文案节点的兄弟节点而不是祖先，所以两处都要看）。
 * 分先后是为了让**券自己那一行**的说法赢过外层容器营销语：
 * 外层写"会员专享"而券行写"立即领"时，取外层会凭空加一道会员门槛。
 */
object StateWords {

    fun of(clause: String, ancestors: List<String>, vocabulary: CouponVocabulary = CouponVocabulary.DEFAULT): CouponState {
        val inClause = CouponVocabulary.STATE_PRIORITY.firstOrNull { state ->
            vocabulary.states[state].orEmpty().any { it.matches(clause) }
        }
        if (inClause != null) return inClause
        val joined = ancestors.joinToString(" ")
        if (joined.isEmpty()) return CouponState.UNKNOWN
        return CouponVocabulary.STATE_PRIORITY.firstOrNull { state ->
            vocabulary.states[state].orEmpty().any { it.matches(joined) }
        } ?: CouponState.UNKNOWN
    }
}

/** 券范围判定：词表与优先级同 [StateWords]，判不出返回 UNKNOWN（不许默认成 SHOP） */
object ScopeWords {

    fun of(clause: String, ancestors: List<String>, vocabulary: CouponVocabulary = CouponVocabulary.DEFAULT): CouponScope {
        val inClause = CouponVocabulary.SCOPE_PRIORITY.firstOrNull { scope ->
            vocabulary.scopes[scope].orEmpty().any { it.matches(clause) }
        }
        if (inClause != null) return inClause
        val joined = ancestors.joinToString(" ")
        if (joined.isEmpty()) return CouponScope.UNKNOWN
        return CouponVocabulary.SCOPE_PRIORITY.firstOrNull { scope ->
            vocabulary.scopes[scope].orEmpty().any { it.matches(joined) }
        } ?: CouponScope.UNKNOWN
    }
}

/** 「这句话在说券」的形状判定（NODE 入口筛候选节点用；不要求句子完整，只要沾到形状词） */
object CouponHints {

    fun looksLikeCouponText(text: String, vocabulary: CouponVocabulary = CouponVocabulary.DEFAULT): Boolean =
        vocabulary.couponHints.any { text.contains(it) }

    /** resource-id 语义（`coupon`/`promotion` 等）：真机 ID 多是混淆短名，这一路只在带语义 ID 时加分 */
    fun looksLikeCouponResource(resourceName: String?, vocabulary: CouponVocabulary = CouponVocabulary.DEFAULT): Boolean {
        val name = resourceName?.lowercase() ?: return false
        return vocabulary.resourceHints.any { name.contains(it) }
    }

    /**
     * 这句话里的数字**能不能采信**：`12期免息` `晒单返50元红包` `最高返659京豆` 里的数字
     * 既不是券面额也不是价格（真机京东商详页三种都有，见夹具 jd_detail_*_plb110_20261003.xml）。
     * 口径与 `PriceNodeMatcher.isPriceExcludedText` 一致并按真机补齐（京豆/销量/库存）。
     */
    fun numbersUsable(text: String, vocabulary: CouponVocabulary = CouponVocabulary.DEFAULT): Boolean =
        vocabulary.excludedNumberWords.none { text.contains(it) }
}
