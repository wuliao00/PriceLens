package com.pricelens.coupon.slots

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

        fun anyCase(token: String): WordRule =
            WordRule(token, Regex(Regex.escape(token), RegexOption.IGNORE_CASE))

        fun shape(pattern: String): WordRule = WordRule(pattern, Regex(pattern))
    }
}

/**
 * 找券的**词表与形状判据**（单一真相，全部可被远端规则包覆盖）。
 *
 * 为什么必须做成数据而不是散在 Kotlin 里的 when 分支：`rules/` 那套无障碍规则引擎的教训
 * （2026-10-03 京东国补改文案那次真发了一个 APK）—— 电商 App 改文案是**常态**，
 * 词表写死在代码里意味着每次改文案都要发版。本对象与 `PageVocabulary` 同构：
 * 出厂值在这里，远端 `gate`/`coupon` 块能整体替换，改判据从"发版"降级为"推规则"。
 *
 * @param couponHints 「这句话在说券」的形状词：NODE 入口拿它筛候选节点，文本入口拿它兜底。
 *   与 [amountRoles] 分开是有意的：`到手` 是金额角色词，但"到手价"三个字本身不足以说明有券。
 * @param excludedNumberWords 出现即**不采信**该句里的数字：分期/免息/首付/晒单等数字既不是券也不是价
 *   （口径对齐 `PriceNodeMatcher.PRICE_TEXT_EXCLUDE_WORDS`，那边是 private 拿不来，改时两处一起改）。
 */
data class CouponVocabulary(
    val amountRoles: Map<AmountRole, List<WordRule>>,
    val states: Map<CouponState, List<WordRule>>,
    val scopes: Map<CouponScope, List<WordRule>>,
    val couponHints: List<String>,
    val resourceHints: List<String>,
    val excludedNumberWords: List<String>
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
                    WordRule.literal("标价")
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
            couponHints = listOf("券", "领", "满", "减", "立减", "到手", "券后", "折", "红包"),
            resourceHints = listOf("coupon", "promotion", "youhui", "voucher"),
            excludedNumberWords = listOf("分期", "免息", "首付", "晒单", "评价", "京豆", "销量", "库存")
        )
    }
}
