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
 * 判 [numberStart] 处那个数字的角色：**只看数字之前、离它最近的那个词**，
 * 并且**要求那个词与数字之间只隔着"不构成新语义"的字符**。
 *
 * 为什么必须加"只隔着连接符"这一条（2026-10-04 由测试自己撞出来）：
 * 只按"结束下标最大"取词、不限量距离时，句子里**任何**一个早先出现的角色词会把后面所有数字全认领走 ——
 * `2026-10-08到期，店铺券满199减20 券码：ABCD1234` 里的 1234 因为 20 多字之前有个 `减`
 * 被判成 DISCOUNT，凭空多出一张"¥1234 的券"。这不是理论风险：真机券文案里"券码/口令/编号"跟在
 * 金额后面是常见排版，而多出一张券比少抽一张券更难被发现（它会带着正确的 scope/state 一起出现）。
 *
 * 为什么是**字符类**而不是"距离 ≤ N 个字"：距离阈值是照样本凑出来的数（"为什么不是 3？"答不上来），
 * 而"词与数字之间允许出现什么"是可以逐字列清的 —— 空白、货币符号、`了/至/到/价` 这类连接字。
 * `券码：ABCD` 里有 `券`、`码`、`：`、字母，任何一条都不在该类里 ⇒ 直接判不出。
 *
 * 其余判据不变：
 *  - `满4999减300` 一句里同时有 THRESHOLD 与 DISCOUNT 两个信号，整句判法只能给一个标签，
 *    两个数字必有一个被判错 ⇒ 按"离每个数字最近"各自判。
 *  - 同下标取**长词**（`无门槛` 压 `门槛`，见词表注释）。
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
    if (best == null) return null
    return if (ROLE_NUMBER_GLUE.matches(clause.substring(bestEnd, numberStart))) best else null
}

/**
 * 角色词与它修饰的数字之间允许出现的字符（**空白、货币符号、连接字**）。
 *
 * 这里是字符类而不是词表，所以刻意不进 `CouponVocabulary`：远端规则包该能改"哪些词算面额"，
 * 但不该能改"词与数字之间能隔什么" —— 后者是本判据不认领远处数字的唯一保证。
 */
private val ROLE_NUMBER_GLUE = Regex("[\\s¥￥了至到价]*")

/**
 * 数字后面紧跟**费率单位**（`%` `％` `折`，允许隔一个空格）⇒ 这个数说的是比率，不是金额。
 *
 * 真样本一共三类，全部来自采过的夹具（2026-10-05 数过：整份评测集里 `数字+折` 只有 3 处，
 * 期望产出都是"不产券也不产价格"）：`参与立减15%`、`国庆出行好物低至5折`（京东搜索页节点，
 * 评测集 jd-search-02）、`下单返9折券`（京东国补页三棵树里都有）。
 *
 * 为什么这条闸在**角色判定之前**、对三种角色一视同仁：`15%` 不能变成 ¥15 的券，
 * `低至5折` 也不能变成"到手 ¥5" —— 前者是 2026-10-04 第一次评测抓出来的误抽，
 * 后者是 2026-10-05 把 `低至` 补进 FINAL 词表之后**才出现**的风险（词表说"这数是到手价"，
 * 而它连着「折」）。两处调用方（管线 `consume` 与模型输出的确定性复核）**共用这一个函数**，
 * 因为"两边口径必须一致"这件事靠注释维持过一次，注释不管用。
 */
internal fun followedByRatioUnit(text: String, from: Int): Boolean {
    var i = from
    while (i < text.length && text[i] == ' ') i++
    return i < text.length && (text[i] == '%' || text[i] == '％' || text[i] == '折')
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
     * 既不是券面额也不是价格（真机京东商详页三种都有，见夹具 jd_detail_plb110_20261003.xml）。
     * 词表与 `PriceNodeMatcher.isPriceExcludedText` **共用同一份**（`PRICE_TEXT_EXCLUDE_WORDS`，
     * 已转 internal），本包只声明增量：京豆/销量/库存 —— 见 [CouponVocabulary.excludedNumberWords]。
     */
    fun numbersUsable(text: String, vocabulary: CouponVocabulary = CouponVocabulary.DEFAULT): Boolean =
        vocabulary.excludedNumberWords.none { text.contains(it) }
}
