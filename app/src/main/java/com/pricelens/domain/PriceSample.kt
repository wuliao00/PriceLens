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
 * @param referenceOnly true = 只能作为参考展示，不是本轮现价：不发降价通知、不写历史曲线
 */
enum class PriceSource(val label: String, val referenceOnly: Boolean) {
    /** 京东 p.3.cn 批量查价的在售价（该域名 2026-09 起公网 DNS 已不可达） */
    JD_P3CN("京东在售价", false),

    /** 星罗好货历史低价榜里的 `goods_list_money`：榜单审核时点的在售价 */
    LINKSTARS_LIST("星罗好货在售价", false),

    /** 星罗好货 `real_money`：券后**历史**低价。是历史位置，不是今天的价 */
    LINKSTARS_HISTORY_LOW("星罗好货券后历史低价", true);

    /** 只有真现价才够格触发降价通知、计入"本轮查到价格" */
    val isLivePrice: Boolean get() = !referenceOnly
}

/** 带来源标记的价格样本：盯价每轮"现价"与曲线补点都以它为单位 */
data class PriceSample(val price: Double, val source: PriceSource) {
    /** 拿到了一个可用的数（0/负数 = 这一路没给价） */
    val hasPrice: Boolean get() = price > 0.0

    /** 既是一个正数、又是真现价：唯一够格参与降价判定的样本 */
    val isLive: Boolean get() = hasPrice && source.isLivePrice
}
