package com.pricelens.domain

import com.pricelens.util.QueryRelevance
import kotlin.math.abs
import kotlin.math.ln

/**
 * 跨源商品候选打分（2026-09-28 新增）。
 *
 * 取代旧的产品候选选择方式："各源过滤后取第一条带价的"（当当优先）。
 * 实况里第一条往往是错的（同一关键词跨源差异极大）：
 *  - `mate 80` → ¥8840.95「【政府补贴至高15%】 MATE 80 麒麟9020… 官方」（渠道价）
 *  - `x8s`     → ¥108.8 「【预售 按需印刷】Vintage Plaid 1 Scrapbook Paper Pad」（当当纸品）
 *  - `iPhone 15` → ¥4248 「Apple iPhone15/14/13/12/苹果 15/14/13/12 全网通激活无使用」（二手混卖）
 *  - `oppo x8s` → 相关性过滤被误杀后**无候选**
 * 改为对所有过滤后条目跨源打分取 argmax 后（同一批实况回放，见
 * `app/src/test/resources/fixtures/live_candidate_pools.json` 与 CandidateRankingTest）：
 *  - `mate 80` → ¥4079.15 华为 Mate 80 手机 12GB+512GB 雪域白
 *  - `x8s`     → ¥2860.00 国家补贴：OPPO Find X8s+ 5G手机 12GB+512GB
 *  - `iPhone 15` → ¥7561.01 Apple iPhone 15 5G手机 512GB 粉色（新机）
 *  - `oppo x8s` → ¥2860.00（同上）
 *
 * 打分是**纯函数**（无副作用、无网络、不依赖数据源类型），故两端可逐条对齐：
 * Android 端本文件，桌面端 `desktop/src/main/utils/ranking.js`。
 *
 * 权重来源：2026-09-28 对 7 个真实关键词 × 当当/值得买实况池子的回放调参
 * （PriceLens-probe/out/rule_variants2.txt 段 D、out/a1_rank_final.txt），
 * 只有"品类词"是决定性项，其余是同分项里的次序修正项。
 */
object CandidateRanking {

    /** 品类词命中：整机/主商品几乎必带品类词，配件书刊不一定 → 最强正向信号 */
    internal const val WEIGHT_CATEGORY = 2.0

    /** 规格完整（内存+存储组合，或屏幕尺寸+容量）：官方整目标题的典型特征 */
    internal const val WEIGHT_SPEC = 1.0

    /** 国补 / PLUS 会员：官方自营渠道信号（轻微加分，避免把渠道价捧成候选） */
    internal const val WEIGHT_SUBSIDY = 0.5

    /** 二手信号（激活无使用 / 二手 / 官翻 / 95新…）：跟踪历史价时是错误标的，重罚 */
    internal const val WEIGHT_SECONDHAND = -2.5

    /** 多机型混卖（标题里 ≥3 个型号数字用 / 、 , 串联）：一链接多机型，历史价无意义 */
    internal const val WEIGHT_MULTI_MODEL = -1.5

    /** 价格带偏离系数：-1.2 * |ln(p / 中位价)|（跨源价格差一个量级时把离群值压下去） */
    internal const val WEIGHT_PRICE_BAND = -1.2

    /** query 未含后缀而标题含 pro/max/ultra/plus：只降权，**不过滤**（列表里仍要出现） */
    internal const val WEIGHT_SERIES_SUFFIX = -0.8

    /** query 命中品牌但标题缺该品牌任何写法：第三方疑似混卖 */
    internal const val WEIGHT_BRAND_MISSING = -1.0

    /** 品类词（归一化后做子串；配件词在相关性阶段已被剔除） */
    private val CATEGORY_WORDS = listOf("手机", "平板", "笔记本", "手表", "耳机", "相机", "电视")

    /** 二手/官翻信号（实况样例："全网通激活无使用"、"95新"） */
    private val SECONDHAND = Regex("二手|激活|官换|官翻|翻新|准新|备用机|9[589]新|9\\.[589]新")

    /** 多机型混卖：三个及以上型号数字用 / 、 , 串联（"iPhone15/14/13/12"） */
    private val MULTI_MODEL = Regex("\\d{2,4}\\s*(?:/|、|,)\\s*\\d{2,4}\\s*(?:/|、|,)\\s*\\d{1,4}")

    /** 国补 / PLUS 渠道信号 */
    private val SUBSIDY = Regex("国家补贴|政府补贴|国补|政府补助|plus会员")

    /** 内存+存储组合（"12GB+512GB" / "12+256GB" / "16GB+1TB"） */
    private val MEMORY_STORAGE_COMBO = Regex("\\d{1,2}\\s*gb\\s*\\+?\\s*\\d{1,3}\\s*(?:gb|tb)|\\d{1,2}\\+\\d{2,4}\\s*(?:gb|tb)")

    /** 容量出现次数（≥2 个容量也算规格完整，如 "8gb+256gb"） */
    private val CAPACITY = Regex("\\d{1,3}\\s*(?:gb|tb)")

    /** 屏幕尺寸（"6.75英寸"）；单独出现不足以判"规格完整"，需与容量同时出现 */
    private val SCREEN_SIZE = Regex("\\d+(?:\\.\\d+)?\\s*(?:英寸|寸)")

    /** 系列后缀（机型高配版本；query 没写而标题写了 → 降权） */
    private val SERIES_SUFFIXES = listOf("pro", "max", "ultra", "plus")

    /** 打分入参：与数据源无关的最小信息集 */
    data class Rankable(
        val title: String,
        val price: Double,
        /** 结构化国补标记（识货 labels=PUBLIC_SUBSIDIES）；当当/值得买只能从标题文本判断 */
        val explicitSubsidy: Boolean = false
    )

    /** 打分结果（[item] 原样带回，便于调用方保留数据源信息） */
    data class Scored<T>(val item: T, val score: Double)

    /**
     * 单条打分。[medianPrice] 是**同一批候选池**的中位价（价格带锚点）；
     * 传 0 表示池子里只有一条，此时不计价格带偏离。
     */
    fun score(title: String, price: Double, medianPrice: Double, keyword: String, explicitSubsidy: Boolean = false): Double {
        val lower = title.lowercase()
        val normalizedTitle = QueryRelevance.normalize(title)
        val normalizedQuery = QueryRelevance.normalize(keyword)
        val compact = lower.replace(Regex("\\s+"), "")

        var total = 0.0
        if (CATEGORY_WORDS.any { normalizedTitle.contains(it) }) total += WEIGHT_CATEGORY
        if (SECONDHAND.containsMatchIn(lower)) total += WEIGHT_SECONDHAND
        if (MULTI_MODEL.containsMatchIn(lower)) total += WEIGHT_MULTI_MODEL
        if (isSpecComplete(compact)) total += WEIGHT_SPEC
        if (explicitSubsidy || SUBSIDY.containsMatchIn(lower)) total += WEIGHT_SUBSIDY
        if (medianPrice > 0.0 && price > 0.0) {
            total += WEIGHT_PRICE_BAND * abs(ln(maxOf(price, 1.0) / maxOf(medianPrice, 1.0)))
        }
        if (SERIES_SUFFIXES.any { !normalizedQuery.contains(it) && normalizedTitle.contains(it) }) {
            total += WEIGHT_SERIES_SUFFIX
        }
        val brand = QueryRelevance.brandOf(normalizedQuery)
        if (brand != null && !QueryRelevance.hasAnyAlias(brand, normalizedTitle)) {
            total += WEIGHT_BRAND_MISSING
        }
        return total
    }

    /**
     * 候选池打分并降序排序（同分时保持入参顺序 —— Kotlin 的 sortedBy* 是稳定排序，
     * 调用方按"当当 → 值得买"入池，即沿用旧的"当当优先"tie-break）。
     * 泛型版本让调用方保留自己的条目类型（含 url / 图片 / SKU 等下游要用的字段）。
     */
    fun <T> rankBy(
        keyword: String,
        items: List<T>,
        titleOf: (T) -> String,
        priceOf: (T) -> Double,
        subsidyOf: (T) -> Boolean = { false }
    ): List<Scored<T>> {
        if (items.isEmpty()) return emptyList()
        val median = medianPrice(items.map(priceOf).filter { it > 0.0 })
        return items.map { Scored(it, score(titleOf(it), priceOf(it), median, keyword, subsidyOf(it))) }
            .sortedByDescending { it.score }
    }

    /** [Rankable] 版本的便捷入口 */
    fun rank(keyword: String, items: List<Rankable>): List<Scored<Rankable>> =
        rankBy(keyword, items, { it.title }, { it.price }, { it.explicitSubsidy })

    /** argmax（池子为空返回 null） */
    fun best(keyword: String, items: List<Rankable>): Rankable? = rank(keyword, items).firstOrNull()?.item

    /** 泛型 argmax */
    fun <T> bestBy(
        keyword: String,
        items: List<T>,
        titleOf: (T) -> String,
        priceOf: (T) -> Double,
        subsidyOf: (T) -> Boolean = { false }
    ): T? = rankBy(keyword, items, titleOf, priceOf, subsidyOf).firstOrNull()?.item

    /**
     * 中位价锚点：排序后取 `n / 2` 下标（偶数条取靠上的那个）。
     * 沿用 2026-09-28 调参回放时的口径（PriceLens-probe/eval_fix2.py 的 `median`），
     * 这样单测里复现的分数与实况回放一致。
     */
    internal fun medianPrice(prices: List<Double>): Double {
        if (prices.isEmpty()) return 0.0
        val sorted = prices.sorted()
        return sorted[sorted.size / 2]
    }

    /** 规格完整：内存+存储组合，或"容量 + 屏幕尺寸"同时出现 */
    internal fun isSpecComplete(compactLowerTitle: String): Boolean = MEMORY_STORAGE_COMBO.containsMatchIn(compactLowerTitle) ||
        (CAPACITY.findAll(compactLowerTitle).count() >= 2) ||
        (SCREEN_SIZE.containsMatchIn(compactLowerTitle) && CAPACITY.containsMatchIn(compactLowerTitle))
}
