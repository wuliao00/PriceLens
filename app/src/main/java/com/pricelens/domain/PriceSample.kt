package com.pricelens.domain

/**
 * 一个价格的来源（F1 修复引入，2026-09-29）。
 *
 * 背景：星罗好货开放平台的接口名就是 `jd_historyLowPriceRank`（**历史低价榜**），
 * 它给的 `real_money` 是"券后历史低价"、`goods_list_money` 才是"在售价"。
 * 5c11a46 的实现把前者当成现价，塞进与京东在售价同一个**无标记** `Map<String, Double>`，
 * 于是：
 *  - 降价通知拿"历史低价"和 targetPrice 比 → 价格没降也会通知「当前 ¥X ≤ 目标 ¥Y」；
 *  - 同一个值被写成"今天的曲线点"落进历史表 → 曲线最低点恒 ≤ 它 →
 *    `judgePrice` 几乎恒判「≈ 历史低价 / 可入」。
 * 价格从此必须自带来源，能不能触发通知、能不能进曲线由 [PriceSampling] 按来源判定，
 * 不再由"它是不是一个正数"判定。
 *
 * 2026-09-30 起这个枚举**同时服务两件事**（刻意复用同一套来源模型，不是巧合）：
 *  1. 「本轮现价」的成色 —— [referenceOnly] 决定它能不能触发降价通知；
 *  2. 「曲线点的出处」 —— 写进 `price_history.source` 的就是 [name]，
 *     盯价页脚注据此说明这条线是本机盯价自采的还是慢慢买给的。
 * 所以新增成员时两件事都要想清楚：**给曲线用的来源 [referenceOnly] 必须是 false**，
 * 否则等于把 F1 那个「星罗券后历史低价冒充现价」的 bug 放回来
 * （资格判定见 [PriceSampling.curveWorthy]，不许绕过）。
 *
 * @param referenceOnly true = 只能作为参考展示，不是本轮现价：不发降价通知、不写历史曲线
 */
enum class PriceSource(val label: String, val referenceOnly: Boolean) {
    /** 京东 p.3.cn 批量查价的在售价（该域名 2026-09 起公网 DNS 已不可达） */
    JD_P3CN("京东在售价", false),

    /** 星罗好货历史低价榜里的 `goods_list_money`：榜单审核时点的在售价 */
    LINKSTARS_LIST("星罗好货在售价", false),

    /** 星罗好货 `real_money`：券后**历史**低价。是历史位置，不是今天的价 */
    LINKSTARS_HISTORY_LOW("星罗好货券后历史低价", true),

    /** 慢慢买历史曲线给的每日点（用户自填 Cookie 时拿到的外部历史） */
    MANMANBUY("慢慢买", false),

    /** 本机盯价轮次自采的每日点：没有慢慢买 Cookie 时曲线靠它自己长出来 */
    SELF_WATCH("本机盯价自采", false);

    /** 只有真现价才够格触发降价通知、计入"本轮查到价格" */
    val isLivePrice: Boolean get() = !referenceOnly

    companion object {
        /**
         * 「来源未记录」哨兵：是 `price_history.source` 里存的字符串，**不是**本枚举成员。
         *
         * v2 迁移前的既有行是「慢慢买 + 星罗合并结果」缓存下来的，事后已无法区分出处 ——
         * 给它们编一个出处（比如写成 MANMANBUY）就是造假，所以一律标成这个诚实的哨兵。
         */
        const val UNRECORDED_NAME = "UNRECORDED"

        /** [UNRECORDED_NAME] 的人读标签（盯价页脚注用） */
        const val UNRECORDED_LABEL = "来源未记录"

        /** `price_history.source` → 枚举；[UNRECORDED_NAME] 与认不出的值都返回 null（按「来源未记录」处理） */
        fun fromName(name: String?): PriceSource? = values().firstOrNull { it.name == name }

        /** `price_history.source` → 人读标签：认不出的来源照实说「来源未记录」，不猜成慢慢买 */
        fun labelOf(name: String?): String = fromName(name)?.label ?: UNRECORDED_LABEL
    }
}

/** 带来源标记的价格样本：盯价每轮"现价"与曲线补点都以它为单位 */
data class PriceSample(val price: Double, val source: PriceSource) {
    /** 拿到了一个可用的数（0/负数 = 这一路没给价） */
    val hasPrice: Boolean get() = price > 0.0

    /** 既是一个正数、又是真现价：唯一够格参与降价判定的样本 */
    val isLive: Boolean get() = hasPrice && source.isLivePrice
}
