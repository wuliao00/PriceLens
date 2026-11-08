package com.pricelens.accuracy

import com.pricelens.domain.AutoTrackPolicy
import com.pricelens.util.QueryRelevance

/**
 * 「这条搜索结果是不是同一件商品」的准确性守卫（纯函数、无 Android 依赖，JVM 可直接单测）。
 *
 * 为什么要第二道闸：[QueryRelevance] 解决的是"关键词 vs 标题"的宽泛匹配，但对**同品牌垃圾**
 * 与**价格差一个量级的引流条目**不敏感。真机第一轮实测（2026-11，识货社区列表 + 什么是值得买
 * 爆料）出现两条都过了关键词相关性、却完全没有资格当"同款现价"的样本：
 *  - 「稳健医疗 Apple 苹果 iPhone … ¥13.4」——同品牌词但卖的是医疗用品；
 *  - 「资源机 Apple/苹果 iPhone … ¥4,737」——关键词全中，但货是资源机/非新机；
 *  - 「什么值得买 爆料价 ¥2,898」与候选 ¥5,999 相差 52%——爆料价是发布时的引流价，不是现价。
 *
 * 这些条目被当成"第二来源"喂进准确性引擎后，会制造**假冲突**与**假离群**（引擎把真价格
 * 标成离群），最终把可信度打到「低置信」。本守卫在**输入侧**把它们挡掉：
 *
 *  1. [QueryRelevance.isRelevant] 必须通过（品牌/配件/图书等既有规则）；
 *  2. 标题不得命中 [negativeTokens]（资源机/官换机/翻新/二手/医疗/图书/配件/适用…）；
 *  3. 双方都有有效价格时，价格/参照价比值必须落在 [MIN_PRICE_RATIO]..[MAX_PRICE_RATIO]
 *     （与 [AutoTrackPolicy.MIN_PRICE_RATIO] / [AutoTrackPolicy.MAX_PRICE_RATIO] 同一口径，
 *     直接复用其常量与 [AutoTrackPolicy.isPriceRatioReasonable]，两处阈值不会漂移）；
 *     参照价缺失时只做文本判定（调用方若拿不到参照价，不要把它当"现价第二来源"用）。
 *
 * 消费方：`SearchViewModel.buildAccuracyRemotePrices`（准确性证据）与 UI 社区/识货列表过滤。
 * 判定集中在这一个入口，避免各页面各写一套"看起来像同款"。
 */
object RelevanceGuard {

    /** 可接受比值下界（与 [AutoTrackPolicy.MIN_PRICE_RATIO] 同值，直接引用防漂移） */
    const val MIN_PRICE_RATIO: Double = AutoTrackPolicy.MIN_PRICE_RATIO

    /** 可接受比值上界（与 [AutoTrackPolicy.MAX_PRICE_RATIO] 同值，直接引用防漂移） */
    const val MAX_PRICE_RATIO: Double = AutoTrackPolicy.MAX_PRICE_RATIO

    /**
     * 否定词清单：「标题里出现这些词，就不是我们要比价的那件商品」。
     *
     * 分类维护（新增时保持分组注释，便于 review）：
     *  - 非全新/非零售整机（资源机、官换机、翻新、二手、拆机、模型机…）；
     *  - 配件/耗材（壳/膜/线/充电器/充电宝…；与 QueryRelevance 的配件表互补）；
     *  - 兼容/适配类第三方商品（"适用/兼容/适配 xxx"）；
     *  - 其它品类（医疗/图书/纸书——同品牌关键词最容易混进来的两类）。
     */
    private val NEGATIVE_TOKENS: List<String> = listOf(
        // 非全新 / 非零售整机 / 服务
        "资源机", "官换机", "官翻", "翻新", "翻新机", "二手", "准新", "99新", "95新",
        "拆机", "组装机", "机模", "模型机", "演示机", "样机", "碎屏", "屏幕总成", "主板", "后盖",
        "租赁", "维修", "修理",
        // 配件 / 耗材
        "配件", "保护壳", "手机壳", "保护套", "贴膜", "钢化膜", "镜头膜", "数据线", "充电线",
        "充电器", "充电头", "适配器", "转接头", "充电宝", "移动电源",
        // 兼容 / 适配类第三方商品
        "适用", "适用于", "兼容", "适配",
        // 其它品类（同品牌/同关键词但完全不是同一件商品）
        "医疗", "医用", "护理", "图书", "纸书", "书籍", "说明书", "手册", "指南"
    )

    /** 否定词清单（只读；UI 若要在设置页展示"为什么这条被过滤"可直接读） */
    fun negativeTokens(): List<String> = NEGATIVE_TOKENS

    /**
     * 判定一条搜索结果是否**可能**是同一件商品（宁缺毋滥：拿不准就不当同款证据）。
     *
     * @param keyword 搜索关键词（空白 → false）
     * @param title 结果标题（空白 → false）
     * @param price 该条价格（null = 没有价格，仅做文本判定；<=0/NaN/∞ → false）
     * @param referencePrice 可比参照价（候选价/历史当前价等）；null 或非法 = 问不到参照价，
     *                       此时**跳过比值校验**（只做文本判定）——注意调用方拿不到参照价时
     *                       不应把它当"现价第二来源"喂给准确性引擎
     * @return 同款可能为 true；任一守卫不过为 false
     */
    fun isLikelySameProduct(keyword: String, title: String, price: Double?, referencePrice: Double?): Boolean {
        if (keyword.isBlank() || title.isBlank()) return false
        if (!QueryRelevance.isRelevant(keyword, title)) return false
        if (containsNegativeToken(title)) return false
        if (price != null) {
            // 有价格就必须是合法价格：0/负/NaN/∞ 是解析错误，不是"另一件商品"
            if (!price.isFinite() || price <= 0.0) return false
            val reference = referencePrice?.takeIf { it.isFinite() && it > 0.0 }
            if (reference != null && !AutoTrackPolicy.isPriceRatioReasonable(price, reference)) return false
        }
        return true
    }

    /** 标题是否命中否定词（公开给调用方做"为什么被过滤"的解释文案；匹配大小写不敏感） */
    fun containsNegativeToken(title: String): Boolean {
        val text = title.lowercase()
        return NEGATIVE_TOKENS.any { text.contains(it) }
    }
}
