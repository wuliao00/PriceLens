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
 * 判 [numberEnd] 处那个数字的角色：**看数字右侧、紧贴它的那个价词**。
 *
 * 左邻尺子 [of] 结构性看不见"价词写在数字后面"那一族形状，于是到手价槽在评测里一直是空的
 * （`[价格槽对照]` final 一致 53 / 比对 58）。四条原文全部来自已采夹具，不是造的：
 *  - `¥92.9，到手价`（商详价格行 desc，评测集 jd-pl-03）
 *  - `¥11499，国补领后价划线价¥12999`（京东国补页，jd-guobu-06；同句 12999 由 [of] 认成 LIST）
 *  - `人民币1579.90 入会到手价`（京东首页卡片，jd-home-04）
 *  - `0.99元（需用券）` / `0.9元（需用券）`（社区价格标签，cm-youhui-07 / cm-faxian-06）
 *
 * 两条把这条尺子关在笼子里的纪律：
 *  1. **只认价词**（FINAL/LIST/DROP）。券面额与门槛的"词在数后"是反的：`500元无门槛券` 里
 *     「无门槛」也跟在数字后面，但它说的是这张券，不是价格。这里返回 null 而不是 DISCOUNT，
 *     券槽一位都不动 ⇒ 券级 P/R/F1 不可能因这条而变化（误抽比漏抽贵，§9.9 那条账）。
 *  2. **间隙逐字列清**（[TAIL_GAP]），不用距离阈值 —— 理由与 [ROLE_NUMBER_GLUE] 相同：
 *     "为什么允许隔 3 个字而不是 4 个"答不上来，而"词与数字之间能出现什么"可以列全。
 *     `近995天新低` 的「天」、`存储容量：256GB` 的「G」、`下单1件，实付低至…` 的「件」
 *     都不在列 ⇒ 判不出。
 *
 * 这条**不代替**调用方的次序：`满1000减100，到手价5499元` 里的 100 右侧确实紧贴「到手」，
 * 尾判会给它 FINAL。挡住它的是 `CouponPipeline.consume` 的那句"左邻尺子与模板先走，
 * 只有它们都没给出角色才回落尾判"，钉子见 `CouponPipelineTest`。
 *
 * @param numberEnd 数字在 [clause] 中的右端点（不含），即 `MatchResult.range.last + 1`
 * @return 判不出返回 null
 */
internal fun AmountRole.Companion.tailOf(
    clause: String,
    numberEnd: Int,
    vocabulary: CouponVocabulary = CouponVocabulary.DEFAULT
): AmountRole? {
    if (numberEnd < 0 || numberEnd >= clause.length) return null
    val window = clause.substring(numberEnd)
    var best: AmountRole? = null
    var bestStart = Int.MAX_VALUE
    var bestLength = -1
    for ((role, rules) in vocabulary.amountRoles) {
        // 尾判的取值域只有价格，券的两个角色在这里不可达
        if (!role.isPriceRole()) continue
        for (rule in rules) {
            val match = rule.pattern.find(window) ?: continue
            val start = match.range.first
            if (!TAIL_GAP.matches(window.substring(0, start))) continue
            if (start < bestStart || (start == bestStart && rule.token.length > bestLength)) {
                best = role
                bestStart = start
                bestLength = rule.token.length
            }
        }
    }
    return best
}

/**
 * 这个角色是不是**价格**（到手/标价/降幅）。
 *
 * 它是"模板不许把这个数字改成券"的判据 —— 价格词写在数字左边就是硬证据，
 * 而模板只看见形状（见 `CouponPipeline.consume` 里那段 2026-10-05 的说明）。
 * 尾判 [tailOf] 的取值域也是这一把尺子，两处不许各写一份。
 */
internal fun AmountRole.isPriceRole(): Boolean = this == AmountRole.FINAL || this == AmountRole.LIST || this == AmountRole.DROP

/**
 * 数字左边紧贴的是**比例上限**结构（`15%起减500元` / `8.5折起减200元`）⇒ 那个数是"最高减到多少"的封顶，
 * 不是一张能领的券。
 *
 * 为什么「起」这一个字就是分野，而且这条只写「起」不写「%」：golden 里同一族原文有两种命运，
 * 全部来自 smzdm 夹具 ——
 *  - `国家补贴15%减500元`（评测集 cm-faxian-01/02）**要产券**：那是活动明写的减免额；
 *  - `补贴15%起减500元`（cm-faxian-04 与它的 clipboard 副本 clip-copy-faxian-04）**不许产券**：
 *    「15%起」说的是比例，后面的 500 是这条比例的封顶。把它当券展示，用户点进去发现"要凑到封顶才减"，
 *    而它是评测里仅剩的两个 FP（合计 P 0.9474）—— 误抽比漏抽贵，这条就是清它的。
 *
 * 判据 anchored 在数字左边缘（`$`）：只允许"费率 + 至多一枚最高/至多 + 起 + 至多一个减/立减"这么长一段，
 * 所以远处出现过的 `15%` 牵连不到近处的数字（`满1000减100` 那种正常券句一位不动）。
 */
private val RATIO_CAP_BEFORE = Regex("\\d+(?:\\.\\d+)?\\s*[%％折]\\s*(?:最高|至多)?起(?:立减|减|返|抵)?\\s*$")

internal fun ratioCappedAmount(clause: String, numberStart: Int): Boolean =
    numberStart > 0 && RATIO_CAP_BEFORE.containsMatchIn(clause.take(numberStart))

/**
 * 价词与它右边的数字之间允许出现的东西：**分隔符**，外加至多一枚**有真样本出处的**营销修饰词。
 *
 * 修饰词只有两枚，各有各的来处：「国补」= `¥11499，国补领后价`（京东国补页 jd-guobu-06），
 * 「入会」= `人民币1579.90 入会到手价`（京东首页卡片 jd-home-04）。
 * 这张表只随真样本增长 —— 「政府补贴」「平台补贴」这些看起来同族的写法，
 * 在没有采到原文之前不进这里，因为每加一枚就是给"认领远处数字"多开一个口子。
 */
private val TAIL_GAP = Regex("[\\s，,、。·；;元¥￥()（）]*(?:国补|入会)?")

/** 角标计数词与它那个数字之间允许出现的东西：只有空白与货币符号（见 `CouponHints.numberIsCounterMark`） */
private val COUNTER_GLUE = Regex("[\\s¥￥]*")

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
     * 既不是券面额也不是价格（真机京东商详页三种都有，见夹具 jd_detail_plb110_20260929.xml）。
     * 词表与 `PriceNodeMatcher.isPriceExcludedText` **共用同一份**（`PRICE_TEXT_EXCLUDE_WORDS`，
     * 已转 internal），本包只声明增量 —— 见 [CouponVocabulary.excludedNumberWords]。
     *
     * 这里是**整句**作废。角标计数那一类（[numberIsCounterMark]，`销量3万+`）不在这里，
     * 因为京东首页把「标题 + 价格 + 销量」拼进**同一条 content-desc**，整句作废会连真价一起吃掉
     * （评测集 jd-home-04 要的正是那句里的 1579.9）。
     */
    fun numbersUsable(text: String, vocabulary: CouponVocabulary = CouponVocabulary.DEFAULT): Boolean =
        vocabulary.excludedNumberWords.none { text.contains(it) }

    /**
     * 这个数字**本身**是不是贴着某个角标计数词写的（`销量3万+` 的 3）—— 只有它作废，同句别的数字照判。
     *
     * 间隙只允许空白与货币符号：`销量 3万` 也算贴着的，而 `销量最高的 3 款` 中间隔着字 ⇒ 判不出，
     * 那种句子里 3 是排名不是量，本来也不该进价格槽。
     */
    fun numberIsCounterMark(text: String, numberStart: Int, vocabulary: CouponVocabulary = CouponVocabulary.DEFAULT): Boolean =
        vocabulary.counterNumberWords.any { word ->
            val at = text.lastIndexOf(word, numberStart - 1)
            at >= 0 && COUNTER_GLUE.matches(text.substring(at + word.length, numberStart))
        }
}
