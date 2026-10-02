package com.pricelens.domain

/**
 * 盯价（价格跟踪）链路的纯校验 / 纯计数层：能不能盯、以什么 platform + productId 入库、
 * 入库会不会撞主键、检查轮次里每个目标为什么没进展——全部是无 Context、无 Room、
 * 无网络的纯函数，可直接单测。
 *
 * 修掉的真实缺陷（2026-09-26 取证）：
 *  1. 旧 `PriceWatchViewModel.setTarget` 把任何候选硬编码成 `platform="jd"` +
 *     `productId="jd:<sku>"`，于是淘宝/拼多多/当当/识货候选也会被建成"京东目标"。
 *  2. `PriceTargetEntity.productId` 是 `@PrimaryKey` 且 DAO 用 `OnConflictStrategy.REPLACE`，
 *     空 SKU 时 id 恒为 `"jd:"` → 第二个目标静默覆盖第一个（标题与目标价被偷换），
 *     这就是用户反馈的"跟踪错误"。
 *  3. 京东公开查价域名 p.3.cn 公网 DNS 已不返回可达地址、`item.m.jd.com` 价格被掩码
 *     → 匿名京东现价事实上拿不到；旧实现把"拿不到价"与"没在盯"混成同一种静默跳过。
 *
 * 这里不提供任何"看起来能盯"的假通道：没有查价能力的平台明确拒绝并给出原因，
 * 由 UI 把原因如实显示给用户（可改用本机无障碍实时价）。
 */

/** 平台查价通道 */
enum class WatchChannel {
    /** 京东：p.3.cn 公开批量查价 + 星罗好货 API Key 兜底（公开域名 2026-09 起已不可达） */
    JD_LOOKUP,

    /** 无服务端查价通道：入库后每轮只会被跳过，因此拒绝入库 */
    NONE
}

/** 候选"不可盯"的原因（UI 按此出具体文案，不再静默隐藏入口） */
enum class RejectReason {
    /** 既没有平台 ID，也没有可解析出 ID 的商品链接 */
    MISSING_ID,

    /** 有 ID 但形态非法（京东要求 6 位以上纯数字） */
    MALFORMED_ID,

    /** 平台合法但暂无查价通道（淘宝/拼多多/当当/识货/值得买） */
    NO_PRICE_CHANNEL,

    /** SKU 说是京东、链接却指向别的平台：跨平台冒用的直接证据，拒绝入库 */
    PLATFORM_CONFLICT
}

/** 已入库目标在检查轮次里"没有进展"的原因 */
enum class SkipReason {
    /** 平台无查价通道（历史遗留行或被其它入口写入的行） */
    NO_PRICE_CHANNEL,

    /** productId 形态非法（如空 SKU 造成的 "jd:"），无法定位商品 */
    INVALID_TARGET_ID,

    /** 通道在、ID 在，但本轮拿不到现价（p.3.cn 不可达且无星罗 Key 等） */
    PRICE_UNAVAILABLE
}

/** 盯价入口判定结果 */
sealed class WatchDecision {
    abstract val platform: String

    /** 可入库：platform + externalId 由链接/SKU 推导而来，绝不硬编码 */
    data class Watchable(
        override val platform: String,
        val externalId: String
    ) : WatchDecision() {
        /** Room 主键：`<platform>:<externalId>`，两侧都非空 */
        val productId: String = WatchTargetPolicy.targetId(platform, externalId)
    }

    /**
     * 不可盯。[displayId] 是解析到的原始 ID/商品号（可能为空串），仅用于文案里
     * 告诉用户"我们看到的是哪个 ID"，绝不入库。
     */
    data class Rejected(
        override val platform: String,
        val reason: RejectReason,
        val displayId: String
    ) : WatchDecision()
}

/** 入库动作（防止 REPLACE 静默覆盖用户的另一个目标） */
sealed class SavePlan {
    /** 新目标 */
    data class Create(val productId: String) : SavePlan()

    /** 同一 ID、标题基本一致：更新目标价（含把已删除的行重新激活） */
    data class UpdateSameProduct(
        val productId: String,
        val existingTitle: String,
        val existingActive: Boolean
    ) : SavePlan()

    /** 同一 ID 但标题差异大：必须先让用户确认，不能静默改名换价 */
    data class NeedConfirm(
        val productId: String,
        val existingTitle: String,
        val newTitle: String
    ) : SavePlan()

    /** productId 形态非法（空 ID / 缺平台前缀）：绝不入库 */
    data class Blocked(val productId: String) : SavePlan()
}

/** 已存在目标的最小视图（避免 domain 依赖 Room 实体） */
data class ExistingTarget(
    val productId: String,
    val title: String,
    val active: Boolean
)

/** 待检查目标的最小视图（runner 由 PriceTargetEntity 映射而来） */
data class WatchTargetRef(
    val productId: String,
    val platform: String,
    val targetPrice: Double
)

/**
 * 一轮检查里"被跳过的目标"按原因分类的计数。
 *
 * [referenceOnly] 不是第四个互斥桶，而是对 `noPrice` 的**补充说明**：这些目标本轮
 * 不是完全没数据，而是只拿到了"券后历史低价"这类参考值（F1）。它同时计入 `noPrice`
 * （所以 [total] 不重复相加），盯价页脚注用它如实区分"啥都没查到"和"查到的不是现价"。
 */
data class WatchSkipCounts(
    val noChannel: Int = 0,
    val badTargetId: Int = 0,
    val noPrice: Int = 0,
    val referenceOnly: Int = 0
) {
    val total: Int get() = noChannel + badTargetId + noPrice

    operator fun plus(other: WatchSkipCounts) = WatchSkipCounts(
        noChannel = noChannel + other.noChannel,
        badTargetId = badTargetId + other.badTargetId,
        noPrice = noPrice + other.noPrice,
        referenceOnly = referenceOnly + other.referenceOnly
    )

    /** 非零原因，按可行动性排序（先说"平台根本没通道"，再说"本轮取不到价"） */
    fun reasons(): List<SkipReason> = buildList {
        if (noChannel > 0) add(SkipReason.NO_PRICE_CHANNEL)
        if (badTargetId > 0) add(SkipReason.INVALID_TARGET_ID)
        if (noPrice > 0) add(SkipReason.PRICE_UNAVAILABLE)
    }
}

/** 一轮检查的纯计数结果 */
data class WatchRoundReport(
    val total: Int,
    val checked: Int,
    val triggeredProductIds: List<String>,
    val skipped: WatchSkipCounts
) {
    /** 一个目标都没真正查到价格 → 用户视角的"盯了没反应" */
    val stalled: Boolean get() = total > 0 && checked == 0
}

/** 目标价预填依据 */
sealed class TargetPrefill {
    /** 历史曲线确实属于同一 SKU，可以拿来当默认目标价 */
    data class Prefilled(val price: Double, val source: PrefillSource) : TargetPrefill()

    /** 依据不足：不预填，让用户自己填（宁缺毋错） */
    data class NeedsManual(val reason: PrefillReason) : TargetPrefill()
}

enum class PrefillSource {
    /** 同一 SKU 的历史当前价 */
    HISTORY_CURRENT,

    /** 同一 SKU 的历史最低价（当前价缺失时的次选） */
    HISTORY_LOWEST
}

enum class PrefillReason {
    /** 没有可用的历史价 */
    NO_HISTORY,

    /** 无法证明历史曲线属于当前 SKU（关键词没自带商品 ID，或曲线来自另一条爆料链接）→ 不预填 */
    CROSS_SOURCE_HISTORY
}

/**
 * 盯价规则收口点。全部为纯函数：同样的输入永远得到同样的判定，便于单测与真机排查。
 */
object WatchTargetPolicy {
    const val PLATFORM_JD = "jd"
    const val PLATFORM_TAOBAO = "taobao"
    const val PLATFORM_PDD = "pdd"
    const val PLATFORM_DANGDANG = "dangdang"
    const val PLATFORM_SHIHUO = "shihuo"
    const val PLATFORM_SMZDM = "smzdm"
    const val PLATFORM_UNKNOWN = "unknown"

    /** 京东 SKU：6 位以上纯数字（现行 12~13 位），同时是 `jd:` 后必须满足的形态 */
    val JD_SKU = Regex("^[0-9]{6,}$")

    /** 合法 productId：小写平台前缀 + 非空外部 ID；`"jd:"`、`"12345678"`（无前缀）都不合法 */
    private val TARGET_ID = Regex("^[a-z][a-z0-9_]{1,15}:[A-Za-z0-9_.@-]{2,64}$")

    /** item.jd.com / item.m.jd.com 商品链接里的 SKU（m 站形如 /product/{sku}.html） */
    private val JD_URL_SKU = Regex("item\\.(?:m\\.)?jd\\.com/(?:product/)?([0-9]{1,20})")
    private val DANGDANG_URL_ID = Regex("product\\.dangdang\\.com/(?:product/)?([0-9]{3,})")
    private val SHIHUO_URL_ID = Regex("(?:shihuo|poizon)\\.(?:cn|com)/(?:[a-z]*[/?=].{0,40}?)?([0-9]{5,})")
    private val QUERY_ITEM_ID = Regex("[?&#](?:id|item_id|goods_id|goodsId|product_id|sku)=([0-9A-Za-z]{4,})")

    /** App 分享出的京东链接常是 `?sku=1234567890` 形态（与 SearchViewModel 的本地超集对齐） */
    private val JD_SKU_PARAM = Regex("[?&#]sku=([0-9]{6,})")

    /** 京东 SKU 直查要求非空且为纯数字形态；空串/纯空白视为"没有 ID" */
    fun normalizeSku(raw: String?): String? = raw?.trim()?.removePrefix("J_")?.removePrefix("j_")?.takeIf { it.isNotEmpty() }

    /** item.jd.com/{sku}.html、item.m.jd.com/product/{sku}.html 与 ?sku={sku} 三种形态 */
    fun jdSkuFromUrl(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        JD_URL_SKU.find(url)?.groupValues?.getOrNull(1)?.let { return it }
        // 只认京东域名上的 sku 参数，避免把别的站点 ?sku= 当成京东商品号
        return if (url.lowercase().contains("jd.com")) JD_SKU_PARAM.find(url)?.groupValues?.getOrNull(1) else null
    }

    /**
     * 搜索词里是否自带京东商品定位（链接或纯数字 SKU）。
     * 关键词自带 SKU 时，本轮历史价曲线一定就是用这个 SKU 拉的；
     * 不带时历史曲线可能取自某条爆料链接，无法证明属于当前候选。
     */
    fun jdSkuFromKeyword(keyword: String): String? {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return null
        jdSkuFromUrl(trimmed)?.let { return it }
        return normalizeSku(trimmed)?.takeIf { it.all { c -> c.isDigit() } }
    }

    /** 由链接猜平台（候选模型不带平台字段，只能从 URL 域名判定，绝不默认京东） */
    fun detectPlatform(url: String?): String {
        val host = url?.lowercase() ?: return PLATFORM_UNKNOWN
        return when {
            host.contains("jd.com") -> PLATFORM_JD
            host.contains("taobao.com") || host.contains("tmall.com") || host.contains("tb.cn") -> PLATFORM_TAOBAO
            host.contains("yangkeduo") || host.contains("pinduoduo") || host.contains("pdd.com") -> PLATFORM_PDD
            host.contains("dangdang.com") -> PLATFORM_DANGDANG
            host.contains("shihuo") || host.contains("poizon") -> PLATFORM_SHIHUO
            host.contains("smzdm.com") -> PLATFORM_SMZDM
            else -> PLATFORM_UNKNOWN
        }
    }

    /** 平台内的商品号（仅用于文案展示，非京东平台目前一律不可盯） */
    fun externalIdFor(url: String?, platform: String): String? {
        if (url.isNullOrBlank()) return null
        return when (platform) {
            PLATFORM_JD -> jdSkuFromUrl(url)
            PLATFORM_DANGDANG -> DANGDANG_URL_ID.find(url)?.groupValues?.getOrNull(1)
            PLATFORM_SHIHUO -> SHIHUO_URL_ID.find(url)?.groupValues?.getOrNull(1) ?: QUERY_ITEM_ID.find(url)?.groupValues?.getOrNull(1)
            PLATFORM_TAOBAO, PLATFORM_PDD -> QUERY_ITEM_ID.find(url)?.groupValues?.getOrNull(1)
            else -> null
        }
    }

    fun channelOf(platform: String): WatchChannel = if (platform == PLATFORM_JD) WatchChannel.JD_LOOKUP else WatchChannel.NONE

    fun isTrackablePlatform(platform: String): Boolean = channelOf(platform) != WatchChannel.NONE

    fun targetId(platform: String, externalId: String): String = "$platform:${externalId.trim()}"

    /** 拆出 productId 的外部 ID；京东额外要求 SKU 形态合法（挡住 `"jd:"` 这类坏行） */
    fun externalIdOf(productId: String, platform: String): String? {
        val prefix = "$platform:"
        if (!productId.startsWith(prefix)) return null
        val external = productId.removePrefix(prefix).trim()
        return if (platform == PLATFORM_JD) external.takeIf { it.matches(JD_SKU) } else external.takeIf { it.length >= 2 }
    }

    fun isValidTargetId(productId: String): Boolean {
        if (!productId.matches(TARGET_ID)) return false
        val platform = productId.substringBefore(':')
        return externalIdOf(productId, platform) != null
    }

    /**
     * 核心判定：这个候选能不能盯、以什么 platform + productId 入库。
     *
     * 优先用显式 SKU；否则从候选链接里解析（值得买/公众号爆料常直接给京东链接，
     * 这类候选因此仍可盯，但平台是从链接推导出来的，而不是硬编码成 jd）。
     */
    fun decide(skuId: String?, url: String?): WatchDecision {
        val detectedByLink = detectPlatform(url)
        normalizeSku(skuId)?.let { raw ->
            // 跨平台冒用防线：SKU 说是京东、链接却是淘宝/拼多多/当当…时拒绝，
            // 绝不重演"任何候选都写成 platform=jd"的旧行为
            if (detectedByLink != PLATFORM_JD && detectedByLink != PLATFORM_UNKNOWN) {
                return WatchDecision.Rejected(detectedByLink, RejectReason.PLATFORM_CONFLICT, raw)
            }
            return if (raw.matches(JD_SKU)) {
                WatchDecision.Watchable(PLATFORM_JD, raw)
            } else {
                WatchDecision.Rejected(PLATFORM_JD, RejectReason.MALFORMED_ID, raw)
            }
        }
        jdSkuFromUrl(url)?.let { sku ->
            return if (sku.matches(JD_SKU)) {
                WatchDecision.Watchable(PLATFORM_JD, sku)
            } else {
                WatchDecision.Rejected(PLATFORM_JD, RejectReason.MALFORMED_ID, sku)
            }
        }
        val platform = detectPlatform(url)
        if (platform == PLATFORM_UNKNOWN) {
            return WatchDecision.Rejected(PLATFORM_UNKNOWN, RejectReason.MISSING_ID, "")
        }
        return WatchDecision.Rejected(
            platform = platform,
            reason = if (isTrackablePlatform(platform)) RejectReason.MISSING_ID else RejectReason.NO_PRICE_CHANNEL,
            displayId = externalIdFor(url, platform).orEmpty()
        )
    }

    /** 入库前的主键防撞：非法 ID 直接拒绝，同 ID 不同标题要求确认，不再无脑 REPLACE */
    fun planSave(existing: List<ExistingTarget>, decision: WatchDecision.Watchable, newTitle: String): SavePlan {
        val productId = decision.productId
        if (!isValidTargetId(productId)) return SavePlan.Blocked(productId)
        val holder = existing.firstOrNull { it.productId == productId } ?: return SavePlan.Create(productId)
        return if (titlesLikelySameProduct(holder.title, newTitle)) {
            SavePlan.UpdateSameProduct(productId, holder.title, holder.active)
        } else {
            SavePlan.NeedConfirm(productId, holder.title, newTitle)
        }
    }

    /** 已入库行是否还能被跟踪（供 UI 统计"历史遗留的坏目标"） */
    fun isTrackableTarget(productId: String, platform: String): Boolean =
        isTrackablePlatform(platform) && externalIdOf(productId, platform) != null

    /**
     * 本轮该目标的跳过原因；null 表示可以正常比对。
     *
     * F1：判定看的是"有没有**现价**"，不是"有没有一个正数"。
     * 星罗券后历史低价这类参考样本（[PriceSample.isLive] 为 false）在这里就被判成
     * [SkipReason.PRICE_UNAVAILABLE]，因此既不会进 `checked`，也拿不到触发通知的机会。
     */
    fun skipReasonFor(target: WatchTargetRef, prices: Map<String, PriceSample>, lookupFailed: Boolean): SkipReason? {
        if (!isTrackablePlatform(target.platform)) return SkipReason.NO_PRICE_CHANNEL
        if (externalIdOf(target.productId, target.platform) == null) return SkipReason.INVALID_TARGET_ID
        if (lookupFailed) return SkipReason.PRICE_UNAVAILABLE
        val sample = prices[externalIdOf(target.productId, target.platform)]
        return if (sample == null || !sample.isLive) SkipReason.PRICE_UNAVAILABLE else null
    }

    /**
     * 一轮检查的纯计数：把"查到了价""达标""因何跳过"分清楚。
     * runner 只做取数与发通知，账目全部算在这里（可单测，含 p.3.cn 不可达场景）。
     */
    fun classifyRound(
        targets: List<WatchTargetRef>,
        pricesByPlatform: Map<String, Map<String, PriceSample>>,
        failedPlatforms: Set<String> = emptySet()
    ): WatchRoundReport {
        var checked = 0
        var noChannel = 0
        var badId = 0
        var noPrice = 0
        var referenceOnly = 0
        val triggered = mutableListOf<String>()
        for (target in targets) {
            val prices = pricesByPlatform[target.platform].orEmpty()
            val sample = externalIdOf(target.productId, target.platform)?.let { prices[it] }
            when (skipReasonFor(target, prices, target.platform in failedPlatforms)) {
                SkipReason.NO_PRICE_CHANNEL -> noChannel++
                SkipReason.INVALID_TARGET_ID -> badId++
                SkipReason.PRICE_UNAVAILABLE -> {
                    noPrice++
                    if (sample != null && sample.price > 0 && sample.source.referenceOnly) referenceOnly++
                }
                null -> {
                    checked++
                    val price = pricesByPlatform.getValue(target.platform)
                        .getValue(externalIdOf(target.productId, target.platform)!!).price
                    if (target.targetPrice > 0 && price <= target.targetPrice) triggered += target.productId
                }
            }
        }
        return WatchRoundReport(
            total = targets.size,
            checked = checked,
            triggeredProductIds = triggered,
            skipped = WatchSkipCounts(
                noChannel = noChannel,
                badTargetId = badId,
                noPrice = noPrice,
                referenceOnly = referenceOnly
            )
        )
    }

    /**
     * 目标价默认值：只有当历史曲线确实来自**当前候选的同一个 SKU** 时才预填。
     *
     * 旧实现无条件取 `history.current`，而历史价 URL 在关键词不带 SKU 时会取自
     * 一条爆料链接（SearchViewModel 的 ownSku 分支），于是"给 A 商品设目标价"
     * 可能被预填成 B 商品的价格。证明不了同来源就不预填，交给用户手填。
     */
    fun prefillTargetPrice(
        skuId: String,
        keyword: String,
        candidateUrl: String?,
        historyCurrent: Double,
        historyLowest: Double
    ): TargetPrefill {
        val keywordSku = jdSkuFromKeyword(keyword)
        val urlSku = jdSkuFromUrl(candidateUrl)
        val sameSku = keywordSku != null && keywordSku == skuId && (urlSku == null || urlSku == skuId)
        if (!sameSku) return TargetPrefill.NeedsManual(PrefillReason.CROSS_SOURCE_HISTORY)
        if (historyCurrent > 0) return TargetPrefill.Prefilled(historyCurrent, PrefillSource.HISTORY_CURRENT)
        if (historyLowest > 0) return TargetPrefill.Prefilled(historyLowest, PrefillSource.HISTORY_LOWEST)
        return TargetPrefill.NeedsManual(PrefillReason.NO_HISTORY)
    }

    /** 标题是否像同一商品（同 SKU 改名 / 营销短标题 vs 京东全标题都算同一商品） */
    fun titlesLikelySameProduct(first: String, second: String): Boolean {
        val a = normalizeTitle(first)
        val b = normalizeTitle(second)
        if (a.isEmpty() || b.isEmpty()) return a == b
        if (a == b) return true
        if (a.contains(b) || b.contains(a)) return true
        return bigramSimilarity(a, b) >= 0.4
    }

    /** 去掉空白/标点/【自营】等装饰，只留字母数字再比对 */
    internal fun normalizeTitle(title: String): String = title.lowercase().filter { it.isLetterOrDigit() }

    /** 字符二元组 Dice 系数：中文标题没有空格，按词切分会退化成整串不等 */
    private fun bigramSimilarity(a: String, b: String): Double {
        if (a.length < 2 || b.length < 2) return if (a == b) 1.0 else 0.0
        val left = a.windowed(2).groupingBy { it }.eachCount()
        val right = b.windowed(2).groupingBy { it }.eachCount()
        val common = left.entries.sumOf { (gram, count) -> minOf(count, right[gram] ?: 0) }
        return (2.0 * common) / (left.values.sum() + right.values.sum())
    }
}
