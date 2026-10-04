package com.pricelens.coupon.slots

import com.pricelens.accessibility.PriceNodeMatcher
import com.pricelens.coupon.model.AmountRole
import com.pricelens.coupon.model.CouponScope
import com.pricelens.coupon.model.CouponState

/**
 * 一条词规则：[token] 给人看（也供远端规则包按字面覆盖），[pattern] 给机器跑。
 *
 * 三种构造各有存在理由，别混用：
 *  - [literal]：子串匹配（`满`、`已领完`）。**大小写敏感**，这是刻意的：
 *    京东/淘宝把品牌名写成大写 `PLUS`，而商品型号里的 `Plus`（iPhone 17 Plus）是小写混排 ——
 *    一旦忽略大小写，商品名会被判成"会员专享"，那正是本文件要治的误杀。
 *  - [anyCase]：带数字锚点的记法（`88vip`、`叠plus`），型号名撞不上这种形态。
 *  - [shape]：形状判据（`限.{0,8}地区`）—— 电商文案改的就是中间那几个字，
 *    写成词表中项迟早失效；仓库既有经验（`AccessibilityLabel`）也是"新判据放形状，不堆词表"。
 */
data class WordRule(val token: String, val pattern: Regex) {

    /** 在 [window] 里找最后一次命中，返回**不含**右端点的结束下标（角色判定按"离数字最近"排序用） */
    fun lastEndIn(window: String): Int? = pattern.findAll(window).lastOrNull()?.range?.last?.plus(1)

    fun matches(text: String): Boolean = pattern.containsMatchIn(text)

    companion object {
        fun literal(token: String): WordRule = WordRule(token, Regex(Regex.escape(token)))

        fun anyCase(token: String): WordRule = WordRule(token, Regex(Regex.escape(token), RegexOption.IGNORE_CASE))

        fun shape(pattern: String): WordRule = WordRule(pattern, Regex(pattern))
    }
}

/**
 * 找券的**词表与形状判据**（包里只这一份；判定函数一律吃本对象，默认参数就是出厂值）。
 *
 * 为什么必须做成数据而不是散在 Kotlin 里的 when 分支：`rules/` 那套无障碍规则引擎的教训
 * （2026-10-03 京东国补改文案那次真发了一个 APK）—— 电商 App 改文案是**常态**，
 * 词表写死在代码里意味着每次改文案都要发版。本对象与 `PageVocabulary` 同构：出厂值在这里，
 * 每个判据（`AmountRole.of` / `StateWords.of` / `ScopeWords.of` / `CouponHints.*` /
 * `PostAdapter.classifyKind` / `NodeAdapter.clauses`）都带 vocabulary 形参，整体替换走 [copy]。
 *
 * **本版还没落地的部分（别当成已交付）**：`rules/RuleJson` 目前只解析无障碍门控的 `gate` 块，
 * 还没有 `coupon` 块 ⇒ "推一条规则就改词表"这条通道现在只到"数据结构与注入点齐了、解析器没写"。
 * 模板库那条远端入口已经有了（[com.pricelens.coupon.rules.CouponTemplates.from]）。
 *
 * @param couponHints 「这句话在说券」的形状词：NODE 入口拿它筛候选节点，文本入口拿它兜底。
 *   与 [amountRoles] 分开是有意的：`到手` 是金额角色词，但"到手价"三个字本身不足以说明有券。
 *   `省` 在列是 2026-10-04 从真机补的：京东首页卡片角标 `text="省1元"`（夹具
 *   `jd_home_20260929.xml`，bounds [900,1235][972,1269]）说的就是"用券后少花"，
 *   不收它的话这个节点既不进候选也不成句，"没有"与"被正确拒掉"就分不开了。
 * @param excludedNumberWords 出现即**不采信**该句里的数字：分期/免息/首付/晒单等数字既不是券也不是价。
 *   共享的那五个词**只有一份**：取自 `PriceNodeMatcher.PRICE_TEXT_EXCLUDE_WORDS`（那边已转 internal），
 *   这里只写本包**多出来**的增量（京豆/销量/库存）。以前两处各抄一遍，改一处就让两个入口分叉。
 * @param platformPrefixes 宿主包名前缀 → 平台 token（`jd|taobao|pdd`）。放在词表而不是写死成 Kotlin
 *   常量表的原因是 [com.pricelens.coupon.model.Extraction.platform] 是字符串而非枚举：宿主集合由远端
 *   规则包决定，接一个新宿主（例如有道/唯品会）应该是推一条规则，而不是发一次 APK。
 * @param communityTipWords 社区帖"这是爆料"的形状词（券动词 + 数字 ⇒ 才进抽取）。
 *   以前它硬编码在 `PostAdapter` 里（private），于是"社区把『爆料』改成『好价分享』"
 *   这件事仍然要**发一次 APK** —— 而本包的核心承诺就是把改判据从发版降级为推规则。
 * @param communityAskWords 社区帖"这是求助"的问句词（命中且没有爆料形态 ⇒ 判 ASK，不进抽取）。
 * @param zeroThresholdWords 「无门槛」这类**显式零门槛**的说法：命中时 threshold 写 **0.0**，不是 null。
 *   两者的区别是真的：0.0 = 文案明说了"没有门槛"，null = 文案没提门槛。
 *   评测集里 `【点击领取】¥70无门槛立减券` 标的就是 `threshold: 0.0`，
 *   而流水线第一版对这类句子的产出是 null ⇒ 券级命中直接判失败（一次口径差异吃掉一条 TP）。
 */
data class CouponVocabulary(
    val amountRoles: Map<AmountRole, List<WordRule>>,
    val states: Map<CouponState, List<WordRule>>,
    val scopes: Map<CouponScope, List<WordRule>>,
    val couponHints: List<String>,
    val resourceHints: List<String>,
    val excludedNumberWords: List<String>,
    val platformPrefixes: List<Pair<String, String>>,
    val communityTipWords: List<String>,
    val communityAskWords: List<String>,
    val zeroThresholdWords: List<String>
) {

    companion object {

        /** 状态判定优先级：强信号（售罄/过期）先判，弱信号（`立即领`）垫后 */
        val STATE_PRIORITY = listOf(
            CouponState.SOLD_OUT,
            CouponState.EXPIRED,
            CouponState.CLAIMED,
            CouponState.MEMBER_ONLY,
            CouponState.REGION_LIMITED,
            CouponState.CLAIMABLE
        )

        /** 范围判定优先级：门槛越硬的越先判（会员券的"要会员"比"哪个店"更能决定用户能不能用） */
        val SCOPE_PRIORITY = listOf(
            CouponScope.MEMBER,
            CouponScope.SHOP,
            CouponScope.CATEGORY,
            CouponScope.PLATFORM,
            CouponScope.ITEM
        )

        /** 出厂词表 = 远端规则包没带 coupon 块时的默认值 */
        val DEFAULT = CouponVocabulary(
            amountRoles = mapOf(
                AmountRole.DISCOUNT to listOf(
                    WordRule.literal("减"),
                    WordRule.literal("立减"),
                    WordRule.literal("券面"),
                    // 「省」：真机国补页的券行写的是「PLUS会员等，本单含支付省¥134.99」——
                    // 这是"能省多少"的正字法，不是降幅（降/跌/便宜 才是 DROP）。
                    // 评测集里三条 `支付省¥X` 全靠它才抽得出来。
                    WordRule.literal("省"),
                    // 「无门槛」在 DISCOUNT 与 THRESHOLD 两边都登记（THRESHOLD 侧是 `门槛` 这个词）。
                    // 判"离数字最近的词"时两者**结束下标相同**，规则取长词 ⇒ `无门槛50元券` 的 50 判成
                    // DISCOUNT（这是对的：无门槛券的 50 就是面额）；若只登记 `门槛`，50 会被判成门槛，
                    // 展示成"满50可用"—— 一张不存在的券。钉子见 AmountRoleTest。
                    WordRule.literal("无门槛")
                ),
                AmountRole.THRESHOLD to listOf(
                    WordRule.literal("满"),
                    WordRule.literal("门槛"),
                    WordRule.shape("满\\s*[\\d.]+\\s*(?:元)?可用")
                ),
                AmountRole.FINAL to listOf(
                    WordRule.literal("到手"),
                    WordRule.literal("券后"),
                    WordRule.literal("领后"),
                    WordRule.literal("实付")
                ),
                AmountRole.LIST to listOf(
                    WordRule.literal("原价"),
                    WordRule.literal("划线"),
                    WordRule.literal("日常价"),
                    WordRule.literal("标价"),
                    // 「售价」：真机社区帖里 `目前活动售价5998元` 就靠它把 5998 钉成**价格**。
                    // 不登记它的后果，端侧模型那次真机输出已经演示过：模型把 5998 写成了门槛，
                    // 而复核层当时判不出它的角色、只能降置信收下 ⇒ 一张"满5998减499"的不存在券。
                    WordRule.literal("售价")
                ),
                AmountRole.DROP to listOf(
                    WordRule.literal("降"),
                    WordRule.literal("跌"),
                    WordRule.literal("便宜"),
                    WordRule.literal("比原价")
                )
            ),
            states = mapOf(
                CouponState.SOLD_OUT to listOf(
                    WordRule.literal("已抢完"),
                    WordRule.literal("已领完"),
                    WordRule.literal("抢光"),
                    WordRule.literal("已加光")
                ),
                CouponState.EXPIRED to listOf(
                    WordRule.literal("已过期"),
                    WordRule.literal("过期"),
                    WordRule.literal("失效")
                ),
                CouponState.CLAIMED to listOf(
                    WordRule.literal("已领"),
                    WordRule.literal("去使用")
                ),
                CouponState.MEMBER_ONLY to listOf(
                    WordRule.literal("会员专享"),
                    WordRule.literal("会员券"),
                    WordRule.literal("PLUS"),
                    WordRule.anyCase("88vip"),
                    WordRule.anyCase("叠plus")
                ),
                CouponState.REGION_LIMITED to listOf(
                    WordRule.shape("限.{0,8}地区"),
                    WordRule.literal("部分地区"),
                    // 真机 2026-10-03 商详券弹层原文是「当前地区可领，本单可减1500元」
                    // （夹具 jd_detail_guobu_popup_plb110_20261003.xml）——任务书词表只写了
                    // `限.*地区`/`部分地区`，这一形态会漏判成 UNKNOWN，故按实测文案补。
                    WordRule.literal("当前地区"),
                    WordRule.literal("本地区"),
                    WordRule.literal("该地区")
                ),
                CouponState.CLAIMABLE to listOf(
                    WordRule.literal("领取"),
                    WordRule.literal("立即领")
                )
            ),
            scopes = mapOf(
                CouponScope.MEMBER to listOf(
                    WordRule.literal("会员专享"),
                    WordRule.literal("会员券"),
                    WordRule.literal("PLUS"),
                    WordRule.anyCase("88vip"),
                    WordRule.anyCase("叠plus")
                ),
                CouponScope.SHOP to listOf(
                    WordRule.literal("店铺券"),
                    WordRule.literal("商家券"),
                    WordRule.literal("店铺满减"),
                    WordRule.literal("本店"),
                    WordRule.literal("店内")
                ),
                CouponScope.CATEGORY to listOf(
                    WordRule.literal("品类券"),
                    WordRule.literal("限品类"),
                    WordRule.literal("品类满减"),
                    WordRule.literal("会场券")
                ),
                CouponScope.PLATFORM to listOf(
                    WordRule.literal("平台券"),
                    WordRule.literal("跨店"),
                    WordRule.literal("全平台"),
                    WordRule.literal("东券"),
                    WordRule.literal("京券")
                ),
                CouponScope.ITEM to listOf(
                    WordRule.literal("本商品"),
                    WordRule.literal("此商品"),
                    WordRule.literal("指定商品"),
                    WordRule.literal("单品券"),
                    WordRule.literal("商品券")
                )
            ),
            couponHints = listOf("券", "领", "满", "减", "立减", "到手", "券后", "折", "红包", "省"),
            resourceHints = listOf("coupon", "promotion", "youhui", "voucher"),
            // 共享的五个词只有一份：取自 accessibility 那份（PriceNodeMatcher 第 235 行，已转 internal），
            // 这里只写本包按真机补齐的**增量**。
            excludedNumberWords = PriceNodeMatcher.PRICE_TEXT_EXCLUDE_WORDS + listOf("京豆", "销量", "库存"),
            platformPrefixes = listOf(
                "com.jingdong" to "jd",
                "com.taobao" to "taobao",
                "com.tmall" to "taobao",
                "com.xunmeng" to "pdd",
                "com.yangkeduo" to "pdd"
            ),
            // 原 PostAdapter.TIP_WORDS：命中且句里有数字 ⇒ 爆料帖，才进抽取
            communityTipWords = listOf("到手", "券后", "领", "满减", "立减", "无门槛", "叠", "凑单", "红包", "补贴", "实付", "售价", "活动价"),
            // 原 PostAdapter.ASK_WORDS：没有爆料形态时按问句处理（`？` 规整后是 `?`，两条都留是历史形态）
            communityAskWords = listOf("怎么", "如何", "能不能", "可以吗", "求推荐", "有没有", "求助", "请问", "？", "?"),
            zeroThresholdWords = listOf("无门槛")
        )
    }
}
