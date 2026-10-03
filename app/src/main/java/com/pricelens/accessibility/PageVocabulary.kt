package com.pricelens.accessibility

/**
 * 商详门控用的**文案词表**（单一真相）。
 *
 * 为什么要单独成类：2026-10-03 京东国补商品把底栏「立即购买」换成「领取补贴购买」，
 * 而 resource-id 没变（`feature:id/b34`）—— 词表写在 Kotlin 里意味着**每次电商 App 改文案
 * 都要发一次 APK**（那次确实发了 2.8.0.1）。把词表做成数据，远端规则包就能带一份
 * `gate` 块覆盖它（见 [com.pricelens.rules.RuleJson] 的 gate 解析），改文案从"发版"降级为"推规则"。
 *
 * 语义口径与原实现逐字一致：每个词都是**子串匹配**（`text.contains(word)`），
 * 所以词表里收的一定是完整按钮文案，不是短子串——理由写在 [DEFAULT] 的注释里。
 *
 * 本文件不 import 任何 android.* 类，纯 JVM 可测。
 */
data class PageVocabulary(
    /** 底栏"购买动作"总集合（含加入购物车：列表卡片也可能有，所以它只当"这页有购物动作"的必要信号） */
    val buyAction: List<String>,
    /** 商详底栏**专属**的立购/预约动作：首页信息流没有这些完整文案（见 [DEFAULT] 的红线说明） */
    val buyNow: List<String>,
    /**
     * 商详分区标记（"商品详情"tab 等）；列表卡片的"查看详情"不算，所以**不收裸「详情」「推荐」**。
     *
     * 2026-09-29 真机实测：新版京东商详首屏顶部 tab 是「商品 / 大家评 / 详情 / 推荐」，
     * 裸「详情」会同时命中列表页的「查看详情」按钮；而首屏根本没有本词表任何字样，
     * 所以门控不能指望这一关，兜底走 [buyNow]（见 [com.pricelens.accessibility.isProductPage]）。
     */
    val detailSection: List<String>,
    /** 购物车/确认订单页特征：任一命中即一票否决 */
    val checkout: List<String>,
    /** PDD 商详底栏成对动作（单独购买 + 发起拼单）；列表卡片只有"去拼单"，不成对 */
    val pddSingleBuy: List<String>,
    val pddGroupBuy: List<String>
) {

    companion object {
        /**
         * 商详底栏专属"立购/预约"动作（列表卡片只有"加入购物车"图标，不含这些词）。
         *
         * 「立即预约」是 2026-09-29 真机第二例缺陷的补丁：预约/抢购型商品（茅台飞天需预约）
         * 底栏只有「立即预约」+「等待抢购」，没有「立即购买」，旧词表把它当非商详 → 浮窗不弹。
         * **只能收完整的按钮文案**：同一台真机的京东首页信息流里有「**抢先预约**」
         * （iQOO 16 福袋卡片 content-desc）和「**等待抢购**」（秒杀位），
         * 收「预约」「抢购」这类短子串会把首页判成商详。守门用例见
         * `RealDumpGatingTest."home feed reservation wording never reads as a bottom-bar buy action"`。
         */
        private val BUY_NOW_WORDS = listOf(
            "立即购买", "领券购买", "马上抢", "现在购买", "单独购买", "立即预约",
            // 真机 2026-10-03 11:45：京东**国补商品**的完整商详页底栏把「立即购买」换成了
            // 「领取补贴购买」（同一商品的迷你沉浸页仍是「立即购买」），resource-id 仍是
            // feature:id/b34 —— 文案变了 ID 没变，词表必须收它，否则这一页判不成商详。
            // 收的是**完整底栏文案**而不是「补贴购买」这类短子串（同「立即预约」的红线，见上）。
            "领取补贴购买"
        )

        /**
         * 出厂词表 = 规则包没带 `gate` 块时的默认值（淘宝/拼多多至今没有规则包，走这里）。
         *
         * `rules/jd.json` 的 `gate` 必须与本值**逐项相等**，`BuiltinRuleTest`/`GateVocabularyTest`
         * 里有一条钉子会比对它们：两份真相可以存在，但不许**悄悄漂移**。
         *
         * 声明顺序有讲究：本值引用了上面的 [BUY_NOW_WORDS]，两者同在一个伴生对象里按声明顺序
         * 初始化，把 [BUY_NOW_WORDS] 挪到本值之后会让 DEFAULT 读到一个未初始化的字段。
         */
        val DEFAULT = PageVocabulary(
            buyAction = listOf(
                "加入购物车", "立即购买", "领券购买", "马上抢", "现在购买", "单独购买", "立即预约"
            ),
            buyNow = BUY_NOW_WORDS,
            detailSection = listOf("商品详情", "宝贝详情", "图文详情", "商品评价", "宝贝评价"),
            checkout = listOf("去结算", "提交订单", "立即支付", "合计"),
            pddSingleBuy = listOf("单独购买"),
            pddGroupBuy = listOf("发起拼单", "立即拼单")
        )
    }
}
