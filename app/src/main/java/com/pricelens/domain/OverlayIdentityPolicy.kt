package com.pricelens.domain

import com.pricelens.accessibility.PriceBasis
import com.pricelens.accessibility.ShopPlatform

/**
 * 免凭证曲线（浮窗确认身份）的纯函数判定层：键生成、身份匹配、确认规划、读价资格。
 * 无 Context、无 Room、无网络，可直接单测
 * （app/src/test/java/com/pricelens/domain/OverlayIdentityPolicyTest.kt）。
 *
 * 三条实测否证（docs/API.md）：浮窗拿不到确定性 SKU —— ``只能由用户提供``。
 * 所以身份 = 用户看着标题按下``就是这个商品``，键 = 平台+规范化标题的单向摘要，
 * 与 `jd:<sku>` 分命名空间（附录 B 反例：伪造 SKU/塞进 price_targets 都会造错配或误报）。
 */
object OverlayIdentityPolicy {

    /** 身份键前缀：与 WatchTargetPolicy 的 <平台>:<外部ID> 形态同构，但是独立命名空间 */
    const val ID_PREFIX = "ovl"

    /** 确认身份数硬上限：防"每读到一条不同 banner 文本就新建一行"的失控增长 */
    const val MAX_IDENTITIES = 20

    /**
     * 自动认领既有身份的相似度下限（C2 红线）：宁可开新键，不可串旧键。
     *
     * 实测分布：同商品营销改名（加`【自营】`前缀、多一行`官方标配`）落在 0.92~0.96，
     * 而**不同 variant**（`128G` vs `256G` = 0.82、`小米14` vs `小米14Pro` = 0.84）
     * 落在 0.64~0.89 —— 0.9 正好把两类隔开。
     *
     * 但光靠阈值不够：长标题里"只换一个词"的 variant 也能越过 0.9 ——
     * 实测 `128G` vs `512G` = 0.909、`iPhone 15` vs `iPhone 16` = 0.909，
     * 所以 [match] 额外要求数字串（容量/型号/代际）逐段全等，见 [digitRuns]。
     *
     * 这里刻意不复用 [WatchTargetPolicy.titlesLikelySameProduct]（阈值 0.4 + 互相包含）：
     * 那条规则回答的是"同一 SKU 改名要不要提醒用户"，宽容是优点；
     * 但身份匹配是**自动把今天的价写进某条已有曲线**，宽容 = 不同容量/型号的价格
     * 串进同一条 `ovl:` 曲线 = 曲线造假（用户从未确认过的商品也会涨点）。
     */
    const val MATCH_SIMILARITY = 0.9

    /** 已有身份行的最小视图（domain 不依赖 Room 实体） */
    data class IdentityRef(val productId: String, val platform: String, val title: String)

    /** [digitRuns] 用的数字段提取器 */
    private val DIGIT_RUN = Regex("\\d+")

    /** 确认规划结果。Already 认领既有行是防点分裂的核心；Full/NotEligible 都不建行 */
    sealed class ConfirmPlan {
        data class Create(
            val productId: String,
            val platform: String,
            val title: String,
            val normalizedTitle: String
        ) : ConfirmPlan()

        data class Already(val identity: IdentityRef) : ConfirmPlan()

        /** 已达 MAX_IDENTITIES：提示用户先清理 */
        data object Full : ConfirmPlan()

        /** 标题为空或平台未知：连确认资格都没有 */
        data object NotEligible : ConfirmPlan()
    }

    /** 平台 → 键用小写名；UNKNOWN 返回 null（没有资格建立身份） */
    fun platformKey(platform: ShopPlatform): String? = when (platform) {
        ShopPlatform.JD -> WatchTargetPolicy.PLATFORM_JD
        ShopPlatform.TAOBAO -> WatchTargetPolicy.PLATFORM_TAOBAO
        ShopPlatform.PDD -> WatchTargetPolicy.PLATFORM_PDD
        ShopPlatform.UNKNOWN -> null
    }

    /**
     * 身份键 = `ovl:base36(FNV-1a(平台|规范化标题))`，一经生成永不改：
     * 之后标题怎么变都不换键（匹配走标题相似度，§5.2），新旧日点始终在同一条曲线上。
     */
    fun identityId(platform: String, title: String): String = ID_PREFIX + ":" + base36(fnv1a(platform + "|" + normalizedTitle(title)))

    /** 与 WatchTargetPolicy 的防撞判定同一套规范化规则，绝不各写一份 */
    fun normalizedTitle(title: String): String = WatchTargetPolicy.normalizeTitle(title)

    /**
     * 匹配既有身份：只在同平台内比（京东 vs 淘宝重名商品因此天然隔离），
     * 命中条件是**确定性的**：规范化标题全等（等价于 [identityId] 相同），
     * 或 bigram Dice ≥ [MATCH_SIMILARITY] **且** 数字串逐段全等（[digitRuns]）。
     * 规范化后为空串的旧行不参与相似度比较（否则短噪声标题会跟谁都"像"）。
     */
    fun match(rows: List<IdentityRef>, platform: String, title: String): IdentityRef? {
        val target = normalizedTitle(title)
        if (target.isEmpty()) return null
        return rows.firstOrNull { ref ->
            if (ref.platform != platform) return@firstOrNull false
            val stored = normalizedTitle(ref.title)
            stored == target ||
                (stored.isNotEmpty() && bigramDice(stored, target) >= MATCH_SIMILARITY && digitRuns(stored) == digitRuns(target))
        }
    }

    /** 确认规划（结构镜像 planSave）：命中 → Already；满额 → Full；否则 Create */
    fun planConfirm(existing: List<IdentityRef>, platform: ShopPlatform, title: String): ConfirmPlan {
        val key = platformKey(platform) ?: return ConfirmPlan.NotEligible
        if (title.isBlank()) return ConfirmPlan.NotEligible
        match(existing, key, title)?.let { return ConfirmPlan.Already(it) }
        if (existing.size >= MAX_IDENTITIES) return ConfirmPlan.Full
        return ConfirmPlan.Create(
            productId = identityId(key, title),
            platform = key,
            title = title,
            normalizedTitle = normalizedTitle(title)
        )
    }

    /** 读价不够格成为日点的原因（NONE = 够格）；UI 据此解释"为什么今天没记上" */
    enum class SampleSkip { NONE, NO_PRICE, NON_PAGE_BASIS, NO_TITLE, UNKNOWN_PLATFORM }

    /**
     * 只有页面价（PriceBasis.PAGE）够格写日点：券后价/到手价混进同一条曲线，
     * 会造出``历史最低是券后价``的假摔 —— 与 F1 同形错误（PriceSample.kt 头部注释）。
     */
    fun skipFor(price: Double, basis: PriceBasis, title: String?, platform: ShopPlatform): SampleSkip = when {
        platformKey(platform) == null -> SampleSkip.UNKNOWN_PLATFORM
        title.isNullOrBlank() -> SampleSkip.NO_TITLE
        price <= 0.0 -> SampleSkip.NO_PRICE
        basis != PriceBasis.PAGE -> SampleSkip.NON_PAGE_BASIS
        else -> SampleSkip.NONE
    }

    /** 够格的读价 → 写日点用的样本；出处恒为 SELF_WATCH 写入通道（DayCurve 落库存名） */
    fun sampleFor(price: Double, basis: PriceBasis, title: String?, platform: ShopPlatform): PriceSample? =
        if (skipFor(price, basis, title, platform) == SampleSkip.NONE) PriceSample(price, PriceSource.SELF_WATCH) else null

    /** detection 是否够格出现「就是这个商品」按钮：标题是身份素材，平台未知/没标题时按钮本身不出现 */
    fun canConfirm(title: String?, platform: ShopPlatform): Boolean = !title.isNullOrBlank() && platformKey(platform) != null

    /**
     * 规范化标题里的数字串按出现顺序（`appleiphone15128g黑色5g手机` → `[15, 128, 5]`）。
     *
     * 为什么它是自动认领的第二道闸门：容量、型号年份、代际全部写在数字里，
     * 而营销改名（加前缀/后缀、换店铺名、删括号装饰）几乎不动数字。
     * Dice 单挡不住"长标题里只换一个词"（512G / iPhone 16 都能拿到 0.909），
     * 数字串不等就把它们挡在新键之外 —— 代价是"改了带数字的型号名"会被当成
     * 新商品开新键（曲线分裂），那个方向上是安全的：假曲线比分裂严重得多。
     */
    private fun digitRuns(normalized: String): List<String> = DIGIT_RUN.findAll(normalized).map { it.value }.toList()

    /** 字符二元组 Dice 系数（与 [WatchTargetPolicy] 的私有实现同一算法，这里独立一份）：
     * 不复用 `titlesLikelySameProduct` / 其私有 `bigramSimilarity` —— 身份认领的阈值
     * 与盯价改名的阈值是两个业务问题，共用量化的话下一次改盯价阈值会静默改掉曲线身份。
     */
    private fun bigramDice(a: String, b: String): Double {
        if (a.length < 2 || b.length < 2) return if (a == b) 1.0 else 0.0
        val left = a.windowed(2).groupingBy { it }.eachCount()
        val right = b.windowed(2).groupingBy { it }.eachCount()
        val common = left.entries.sumOf { (gram, count) -> minOf(count, right[gram] ?: 0) }
        return (2.0 * common) / (left.values.sum() + right.values.sum())
    }

    private fun fnv1a(text: String): Long {
        var hash = -3750763034362895579L // FNV-1a 64 位 offset basis
        for (ch in text) {
            hash = hash xor ch.code.toLong()
            hash *= 1099511628211L
        }
        return hash
    }

    /** 键形态要落在 WatchTargetPolicy.TARGET_ID 的 [A-Za-z0-9_.@-] 白名单里，故用纯字母数字 */
    private fun base36(value: Long): String {
        val digits = "0123456789abcdefghijklmnopqrstuvwxyz"
        var v = if (value == Long.MIN_VALUE) Long.MAX_VALUE else if (value < 0) -value else value
        if (v == 0L) return "0"
        val sb = StringBuilder()
        while (v > 0) {
            sb.append(digits[(v % 36).toInt()])
            v /= 36
        }
        return sb.reverse().toString().padStart(6, '0')
    }
}
