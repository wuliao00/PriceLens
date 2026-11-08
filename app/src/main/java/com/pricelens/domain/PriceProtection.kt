package com.pricelens.domain

import java.util.Calendar
import kotlin.math.ceil
import kotlin.math.max

/**
 * 价保助手（买后）的纯规则层：平台窗口表、可申请金额、截止日、幂等提醒判定。
 *
 * 全部纯函数：无 Context/Room/网络，时间一律由调用方传入（毫秒时间戳）。
 * 使用方：[com.pricelens.data.repository.PriceProtectionRepository]（现价来自自动搜索/直查）
 * 与 [com.pricelens.worker.PriceProtectionWorker]（周期性检查）。
 *
 * 规则来源：`docs/research/2026-11-价保与防套-规则.md`（2026-11 检索各平台公开规则）。
 * **平台规则随时会变**，本文件的窗口表集中成一处常量（[ProtectionPlatform]），并逐条带
 * 「以订单页/客服口径为准」的 note；research 之后微调只需改这张表。
 *
 * 口径声明（必读）：
 *  - 窗口：京东自营按商品标识 7/15/30/90/180/365 天（**签收次日起算**，本机拿不到签收时间，
 *    用"购买日次日 00:00"作保守下界，可能少算、不会多算）；京东第三方按下单后
 *    168/360/720/2160 小时；天猫/淘宝默认 15 天（大件 30 天）；拼多多默认 7 天
 *    （大家电 30 天、生鲜 72 小时）；唯品会 10 天；抖音等规则多变 → 归入「其他平台」默认 7 天
 *    并提示以平台为准；用户可覆盖窗口（[ProtectionPlatform] + `windowDaysFor(platform, override)`）。
 *  - 金额：本版是**诚实简化版**——`可申请金额 = 实付价 − 当前同款最低可信价`，
 *    且必须 >0、≤ 实付价；文案写清"名义差额，实际以平台扣除已退/赠品/返卡后为准"。
 *    各平台官方公式（京东京豆/E卡/赠品/已赔金额、拼多多消费券、天猫券归因等）本版不做，
 *    宁可少算也不编一个看起来精确的假数。
 *  - 无价保（窗口天数 0）/窗口外：只展示价格变化（[ProtectionStatus.dropYuan]），不叫「价保」、
 *    不给"可申请"结论、不发提醒。
 *  - 拿不到现价（null/0/负数）时如实报 [ProtectionWindowState.NO_PRICE]，绝不拿 0 或旧价冒充。
 *  - 幂等：同一购买记录、同一「降价档位」（每 [PriceProtectionPolicy.DROP_TIER_YUAN] 元一档）
 *    只提醒一次；价格继续跌到更高档位时允许再提醒，但两次提醒至少间隔
 *    [PriceProtectionPolicy.RENOTIFY_MIN_INTERVAL_MS]（12 小时）。
 */

/**
 * 平台价保窗口表（**唯一事实来源**；改规则只改这里）。
 *
 * @param platformKey 与 `PurchaseRecordEntity.platform` / `PriceTargetEntity.platform` 同口径的小写平台名
 * @param label 人读平台名（UI 展示）
 * @param defaultWindowDays 默认窗口天数（0 = 该平台默认无价保；本表内所有平台都有默认值）
 * @param windowNote 规则说明（必须带「以订单页/客服口径为准/以平台为准」，不承诺）
 */
enum class ProtectionPlatform(
    val platformKey: String,
    val label: String,
    val defaultWindowDays: Int,
    val windowNote: String
) {
    /** 京东自营：商品页标识 7/15/30/90/180/365 天，签收次日起算（本机用购买日次日 00:00 保守下界） */
    JD(
        platformKey = "jd",
        label = "京东自营",
        defaultWindowDays = 7,
        windowNote = "自营常见 7/15/30/90/180/365 天，签收次日起算；以订单页/客服口径为准"
    ),

    /** 京东第三方 / 厂商配送：下单后 168/360/720/2160 小时（=7/15/30/90 天），从提交订单时间起算 */
    JD_THIRD(
        platformKey = "jd_third",
        label = "京东第三方",
        defaultWindowDays = 7,
        windowNote = "第三方常见 7/15/30/90 天（下单后 168/360/720/2160 小时）；以订单页/客服口径为准"
    ),

    /** 淘宝 / 天猫：常见 15 天，大件 30 天，从实际付款时间起算 */
    TAOBAO(
        platformKey = "taobao",
        label = "淘宝天猫",
        defaultWindowDays = 15,
        windowNote = "常见 15 天、大件 30 天（付款起算）；以商品页标识为准"
    ),

    /** 拼多多：其他类目 7 天、大家电 30 天、水产/生鲜/熟食 72 小时；仅"降价补差"标识商品 */
    PDD(
        platformKey = "pdd",
        label = "拼多多",
        defaultWindowDays = 7,
        windowNote = "其他类目 7 天、大家电 30 天、生鲜 72 小时；仅带「降价补差」标识的商品，以平台规则为准"
    ),

    /** 唯品会：公开规则页标题为「10 天价保」，页面易跳转，按 10 天初值 */
    VIP(
        platformKey = "vipshop",
        label = "唯品会",
        defaultWindowDays = 10,
        windowNote = "常见 10 天；规则页可能变动，以 App 内为准"
    ),

    /** 其他平台（含抖音：官方规范有废止/新增公示，**不硬编码**，默认 7 天并提示以平台为准） */
    OTHER(
        platformKey = "other",
        label = "其他平台",
        defaultWindowDays = 7,
        windowNote = "抖音等平台规则多变、不硬编码；以商品页/订单页标识为准"
    );

    /** 该平台是否有价保（默认值 >0；用户把窗口覆盖成 0 时由 [PriceProtectionPolicy] 判定无价保） */
    val hasProtection: Boolean get() = defaultWindowDays > 0

    companion object {
        /** 平台名 → 规则行；认不出的平台一律归入 [OTHER]（不猜、也不假装确定） */
        fun of(platform: String?): ProtectionPlatform = when (platform?.trim()?.lowercase()) {
            "jd", "jingdong", "京东", "京东自营" -> JD
            "jd_third", "jd3p", "京东第三方", "京东三方" -> JD_THIRD
            "taobao", "tmall", "淘宝", "天猫", "淘宝天猫" -> TAOBAO
            "pdd", "pinduoduo", "拼多多" -> PDD
            "vipshop", "vip", "唯品会" -> VIP
            else -> OTHER
        }

        /** 可识别档位（UI 选择器用；天数为 0 表示"该商品无价保"的手动选项） */
        fun windowOptionsOf(platform: String?): List<ProtectionWindowOption> {
            val base = when (of(platform)) {
                JD -> listOf(7, 15, 30, 90, 180, 365).map { ProtectionWindowOption(it, 0) }
                JD_THIRD -> listOf(
                    ProtectionWindowOption(7, 168),
                    ProtectionWindowOption(15, 360),
                    ProtectionWindowOption(30, 720),
                    ProtectionWindowOption(90, 2160)
                )
                TAOBAO -> listOf(15, 30).map { ProtectionWindowOption(it, 0) }
                PDD -> listOf(
                    ProtectionWindowOption(7, 0),
                    ProtectionWindowOption(30, 0),
                    ProtectionWindowOption(3, 72)
                )
                VIP -> listOf(ProtectionWindowOption(10, 0))
                OTHER -> listOf(ProtectionWindowOption(7, 0))
            }
            // 「无价保」是合法的手动选项（用户明确知道该商品没有价保标识）
            return base + ProtectionWindowOption(0, 0)
        }
    }
}

/**
 * 一个可选的价保窗口档位（UI 选择器的一项）。
 *
 * @param days 天数（0 = 无价保）
 * @param hours 小时数（0 = 按天算；京东第三方 168/360/720/2160、拼多多生鲜 72 用小时表达）
 * @param label 人读标签，见 [PriceProtectionPolicy.windowOptionLabel]
 */
data class ProtectionWindowOption(val days: Int, val hours: Int) {
    /** 人读标签（如"15 天"、"168 小时（7 天）"、"无价保"） */
    val label: String get() = PriceProtectionPolicy.windowOptionLabel(this)
}

/** 一次价保评估的结论状态（UI 按此出颜色/文案） */
enum class ProtectionWindowState {
    /** 无价保（窗口天数被覆盖为 0，或平台默认无价保）：仅展示价格变化，不叫价保 */
    NO_PROTECTION,

    /** 本轮拿不到现价：不给"可申请/未降价"结论 */
    NO_PRICE,

    /** 已过价保窗口 */
    OUT_OF_WINDOW,

    /** 在窗口内，但价格没降到买入价以下（或降幅不足申请门槛） */
    NOT_DROPPED,

    /** 在窗口内且可申请（金额见 [ProtectionStatus.claimableYuan]） */
    CLAIMABLE
}

/**
 * 一次价保评估结果。
 *
 * @param state 结论状态
 * @param claimable 是否可申请（false 时 [claimableYuan] 为 0）
 * @param claimableYuan 预计可申请金额（元，保留 2 位小数；已扣噪声门槛）
 * @param dropYuan 当前价较买入价的降幅（元，≥0；无价保平台也照常给，用于"展示价格变化"）
 * @param dropPercent 降幅百分比（0..100）
 * @param windowDays 本次评估用的窗口天数（0 = 无价保）
 * @param windowHours 本次评估用的小时窗口（0 = 按天算；京东第三方/拼多多生鲜用小时）
 * @param deadlineMs 价保截止时刻（毫秒；0 = 无窗口/无价保）
 * @param remainingDays 剩余天数（向上取整；0 = 今天截止/已过期/无窗口）
 * @param alreadyNotified 之前是否已经提醒过（幂等输入原样回传）
 * @param shouldNotify true = 这一轮应该发价保提醒（可申请且满足档位/间隔幂等条件）
 * @param urgent true = 窗口剩余 ≤24 小时且可申请：通知应使用**高优先级**（"最后一天"）
 * @param label 短标签（UI 徽标用）
 * @param note 一句话说明（含金额/天数；无价保平台明确写"仅展示价格变化"）
 */
data class ProtectionStatus(
    val state: ProtectionWindowState,
    val claimable: Boolean,
    val claimableYuan: Double,
    val dropYuan: Double,
    val dropPercent: Double,
    val windowDays: Int,
    val windowHours: Int,
    val deadlineMs: Long,
    val remainingDays: Int,
    val alreadyNotified: Boolean,
    val shouldNotify: Boolean,
    val urgent: Boolean,
    val label: String,
    val note: String
)

/**
 * 价保规则收口点（纯函数）。
 */
object PriceProtectionPolicy {

    /** 一天的毫秒数 */
    const val DAY_MS: Long = 24L * 3600 * 1000

    /** 一小时的毫秒数 */
    const val HOUR_MS: Long = 3600 * 1000L

    /** 噪声门槛下限（元）：差价不到 1 元不叫"可申请价保" */
    const val MIN_CLAIM_YUAN: Double = 1.0

    /** 噪声门槛占比：实付价 ×0.5%（买 1 万的东西差 20 元才值得提醒，见 research P13） */
    const val NOISE_RATIO: Double = 0.005

    /** 降价档位（元）：每 10 元一档；同一档位只提醒一次（幂等） */
    const val DROP_TIER_YUAN: Double = 10.0

    /** 两次价保提醒的最小间隔（毫秒）：价格连跌时最多每 12 小时提醒一次 */
    const val RENOTIFY_MIN_INTERVAL_MS: Long = 12L * 3600 * 1000

    /** 窗口剩余不超过这么久时用高优先级通知（"最后一天"） */
    const val URGENT_REMAINING_MS: Long = DAY_MS

    /** 拼多多生鲜档：3 天 = 72 小时 */
    const val PDD_FRESH_DAYS: Int = 3

    /** 拼多多生鲜档小时数 */
    const val PDD_FRESH_HOURS: Int = 72

    /**
     * 平台的有效窗口天数：`override == null` 用平台默认值；非 null 时以用户选择为准
     * （0 = 用户明确标记"该商品无价保"）。上限 365 天，避免误输入。
     */
    fun windowDaysFor(platform: String, override: Int? = null): Int {
        if (override != null) return override.coerceIn(0, 365)
        return ProtectionPlatform.of(platform).defaultWindowDays.coerceIn(0, 365)
    }

    /**
     * 平台的有效小时窗口（0 = 按天算）：
     *  - 京东第三方：天数是小时档的整日等价（7→168 / 15→360 / 30→720 / 90→2160）；
     *  - 拼多多：覆盖成 [PDD_FRESH_DAYS] 天视为生鲜 72 小时档；
     *  - 其它平台按天算。
     */
    fun windowHoursFor(platform: String, override: Int? = null): Int {
        val rule = ProtectionPlatform.of(platform)
        val days = windowDaysFor(platform, override)
        if (days <= 0) return 0
        return when (rule) {
            ProtectionPlatform.JD_THIRD -> days * 24
            ProtectionPlatform.PDD -> if (days == PDD_FRESH_DAYS) PDD_FRESH_HOURS else 0
            else -> 0
        }
    }

    /** 窗口起算点：京东自营从"签收次日 00:00"起算，本机拿不到签收时间 → 用购买日次日 00:00 保守下界 */
    fun windowStartAtMs(platform: String, purchasedAtMs: Long): Long = if (ProtectionPlatform.of(platform) == ProtectionPlatform.JD) {
        nextDayStartMs(purchasedAtMs)
    } else {
        purchasedAtMs
    }

    /** 某时刻所在自然日的次日 00:00:00.000（本地时区） */
    fun nextDayStartMs(anchorMs: Long): Long = Calendar.getInstance().apply {
        timeInMillis = anchorMs
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** 档位人读标签："15 天" / "168 小时（7 天）" / "72 小时（3 天）" / "无价保" */
    fun windowOptionLabel(option: ProtectionWindowOption): String = when {
        option.days <= 0 -> "无价保"
        option.hours <= 0 -> "${option.days} 天"
        else -> "${option.hours} 小时（${option.days} 天）"
    }

    /** 噪声门槛：可申请金额超过它才值得提醒（实付 ×0.5%，至少 1 元） */
    fun minClaimYuan(purchasePrice: Double): Double {
        if (!purchasePrice.isFinite() || purchasePrice <= 0.0) return MIN_CLAIM_YUAN
        return max(MIN_CLAIM_YUAN, purchasePrice * NOISE_RATIO)
    }

    /**
     * 评估一条购买记录的价保状态。
     *
     * @param purchasePrice 买入实付价（≤0/NaN 视为无效数据，返回 [ProtectionWindowState.NO_PRICE]）
     * @param nowPrice 当前**同款最低可信价**；null/≤0/NaN = 本轮没拿到价（**绝不**当 0 或沿用旧价）
     * @param purchasedAtMs 购买时刻（毫秒）；作为窗口默认起算点
     * @param windowDays 价保窗口天数（0 = 无价保）
     * @param nowMs 评估时刻（毫秒）
     * @param alreadyNotified 是否提醒过（幂等输入；档位/间隔条件见下）
     * @param windowHours 非 0 = 按小时窗口算（京东第三方 168/360/720/2160、拼多多生鲜 72）
     * @param windowStartAtMs 窗口起算点（0/缺省 = [purchasedAtMs]）；京东自营可传
     *   [windowStartAtMs] 给出的"次日 00:00"
     * @param lastNotifiedYuan 上次提醒时的预计可申请金额（金额档位幂等用）
     * @param lastNotifiedAtMs 上次提醒时刻（两次提醒至少间隔 [RENOTIFY_MIN_INTERVAL_MS]）
     */
    fun evaluate(
        purchasePrice: Double,
        nowPrice: Double?,
        purchasedAtMs: Long,
        windowDays: Int,
        nowMs: Long,
        alreadyNotified: Boolean,
        windowHours: Int = 0,
        windowStartAtMs: Long = 0L,
        lastNotifiedYuan: Double = 0.0,
        lastNotifiedAtMs: Long = 0L
    ): ProtectionStatus {
        val days = windowDays.coerceAtLeast(0)
        val hours = windowHours.coerceAtLeast(0)
        val start = if (windowStartAtMs > 0L) windowStartAtMs else purchasedAtMs
        val deadline = deadlineOf(start, days, hours)
        val remainingMs = if (deadline > 0L) (deadline - nowMs).coerceAtLeast(0L) else 0L
        val remaining = remainingDays(remainingMs, days, hours)
        if (!purchasePrice.isFinite() || purchasePrice <= 0.0) {
            return status(
                state = ProtectionWindowState.NO_PRICE,
                claimable = false,
                claimableYuan = 0.0,
                dropYuan = 0.0,
                windowDays = days,
                windowHours = hours,
                deadlineMs = deadline,
                remainingDays = remaining,
                alreadyNotified = alreadyNotified,
                label = "买入价无效",
                note = "这条购买记录的实付价无效，无法算价保金额"
            )
        }
        val price = nowPrice?.takeIf { it.isFinite() && it > 0.0 }
        if (price == null) {
            val state = when {
                days <= 0 && hours <= 0 -> ProtectionWindowState.NO_PROTECTION
                nowMs > deadline -> ProtectionWindowState.OUT_OF_WINDOW
                else -> ProtectionWindowState.NO_PRICE
            }
            return status(
                state = state,
                claimable = false,
                claimableYuan = 0.0,
                dropYuan = 0.0,
                windowDays = days,
                windowHours = hours,
                deadlineMs = deadline,
                remainingDays = remaining,
                alreadyNotified = alreadyNotified,
                label = labelOf(state),
                note = when (state) {
                    ProtectionWindowState.NO_PROTECTION -> "该商品未配置价保（窗口 0 天）；本轮也暂取不到现价"
                    ProtectionWindowState.OUT_OF_WINDOW -> "已过价保窗口；本轮暂取不到现价，无法展示变化"
                    else -> "本轮暂取不到现价，价保状态未知（不拿 0 或旧价充数）"
                }
            )
        }
        // 诚实简化口径：名义差额 = 实付价 − 当前同款最低可信价；必须 >0 且 ≤ 实付价
        val drop = roundYuan((purchasePrice - price).coerceIn(0.0, purchasePrice))
        val dropPercent = if (purchasePrice > 0.0) ((drop / purchasePrice) * 100.0).coerceIn(0.0, 100.0) else 0.0
        val inWindow = nowMs <= deadline && (days > 0 || hours > 0)
        val threshold = minClaimYuan(purchasePrice)
        val state = when {
            days <= 0 && hours <= 0 -> ProtectionWindowState.NO_PROTECTION
            nowMs > deadline -> ProtectionWindowState.OUT_OF_WINDOW
            drop < threshold -> ProtectionWindowState.NOT_DROPPED
            else -> ProtectionWindowState.CLAIMABLE
        }
        val claimable = state == ProtectionWindowState.CLAIMABLE
        val claimableYuan = if (claimable) drop else 0.0
        val urgent = claimable && remainingMs <= URGENT_REMAINING_MS
        val notifyNow = claimable && inWindow && shouldNotify(
            claimableYuan = claimableYuan,
            alreadyNotified = alreadyNotified,
            lastNotifiedYuan = lastNotifiedYuan,
            lastNotifiedAtMs = lastNotifiedAtMs,
            nowMs = nowMs
        )
        val baseNote = noteOf(state, purchasePrice, price, claimableYuan, remaining)
        return status(
            state = state,
            claimable = claimable,
            claimableYuan = claimableYuan,
            dropYuan = drop,
            dropPercent = dropPercent,
            windowDays = days,
            windowHours = hours,
            deadlineMs = deadline,
            remainingDays = remaining,
            alreadyNotified = alreadyNotified,
            shouldNotify = notifyNow,
            urgent = urgent,
            label = labelOf(state),
            note = if (claimable) "$baseNote；名义差额，实际以平台扣除已退/赠品/返卡后为准" else baseNote
        )
    }

    /**
     * 幂等/节流判定（可单独单测）：
     *  - 从没提醒过 → 可以提醒；
     *  - 提醒过：只有**金额档位更高**（每 [DROP_TIER_YUAN] 元一档）且距上次提醒
     *    ≥[RENOTIFY_MIN_INTERVAL_MS] 才再提醒（价格继续跌可以多次申请，但不能刷屏）。
     */
    fun shouldNotify(
        claimableYuan: Double,
        alreadyNotified: Boolean,
        lastNotifiedYuan: Double,
        lastNotifiedAtMs: Long,
        nowMs: Long
    ): Boolean {
        if (!alreadyNotified) return true
        if (lastNotifiedAtMs > 0L && nowMs - lastNotifiedAtMs < RENOTIFY_MIN_INTERVAL_MS) return false
        return tierOf(claimableYuan) > tierOf(lastNotifiedYuan)
    }

    /** 金额档位：每 [DROP_TIER_YUAN] 元一档（向下取整） */
    fun tierOf(yuan: Double): Int = if (!yuan.isFinite() || yuan <= 0.0) 0 else (yuan / DROP_TIER_YUAN).toInt()

    /**
     * 价保截止时刻：起算点 + 小时窗口（优先）或天数窗口；无窗口返回 0（UI 不渲染"截止日"）。
     */
    fun deadlineOf(startAtMs: Long, windowDays: Int = 1, windowHours: Int = 0): Long {
        if (startAtMs <= 0L) return 0L
        if (windowHours > 0) return startAtMs + windowHours * HOUR_MS
        if (windowDays <= 0) return 0L
        return startAtMs + windowDays * DAY_MS
    }

    /**
     * 剩余天数：向上取整（差 1 毫秒也算还有 1 天，避免"今天还能申请"被显示成 0 天）。
     * 无窗口/已过期返回 0。小时窗口也折算成天（72 小时 → 3 天）。
     */
    fun remainingDays(remainingMs: Long, windowDays: Int = 1, windowHours: Int = 0): Int {
        if (windowDays <= 0 && windowHours <= 0) return 0
        if (remainingMs <= 0L) return 0
        return ceil(remainingMs.toDouble() / DAY_MS.toDouble()).toInt().coerceAtLeast(0)
    }

    /** 金额保留 2 位小数（避免 0.1+0.2 这类浮点尾数出现在"可申请 ¥12.999999"里） */
    fun roundYuan(value: Double): Double {
        if (!value.isFinite()) return 0.0
        // 用 BigDecimal 做「四舍五入到分」：12.345 在二进制浮点里其实是 12.34499999…，
        // round(v*100)/100 会得到 12.34 而不是 12.35（价保金额会差一分钱）。
        return java.math.BigDecimal.valueOf(value).setScale(2, java.math.RoundingMode.HALF_UP).toDouble()
    }

    private fun status(
        state: ProtectionWindowState,
        claimable: Boolean,
        claimableYuan: Double,
        dropYuan: Double,
        windowDays: Int,
        windowHours: Int,
        deadlineMs: Long,
        remainingDays: Int,
        alreadyNotified: Boolean,
        label: String,
        note: String,
        dropPercent: Double = 0.0,
        shouldNotify: Boolean = false,
        urgent: Boolean = false
    ): ProtectionStatus = ProtectionStatus(
        state = state,
        claimable = claimable,
        claimableYuan = claimableYuan,
        dropYuan = dropYuan,
        dropPercent = dropPercent,
        windowDays = windowDays,
        windowHours = windowHours,
        deadlineMs = deadlineMs,
        remainingDays = remainingDays,
        alreadyNotified = alreadyNotified,
        shouldNotify = shouldNotify,
        urgent = urgent,
        label = label,
        note = note
    )

    private fun labelOf(state: ProtectionWindowState): String = when (state) {
        ProtectionWindowState.NO_PROTECTION -> "无价保"
        ProtectionWindowState.NO_PRICE -> "暂取不到现价"
        ProtectionWindowState.OUT_OF_WINDOW -> "已过价保期"
        ProtectionWindowState.NOT_DROPPED -> "未降价"
        ProtectionWindowState.CLAIMABLE -> "可申请价保"
    }

    private fun noteOf(
        state: ProtectionWindowState,
        purchasePrice: Double,
        nowPrice: Double,
        claimableYuan: Double,
        remainingDays: Int
    ): String = when (state) {
        ProtectionWindowState.NO_PROTECTION ->
            "该商品未配置价保（窗口 0 天）；买入 ¥$purchasePrice，当前同款最低可信价 ¥$nowPrice（仅展示价格变化）"
        ProtectionWindowState.OUT_OF_WINDOW ->
            "已过价保窗口；当前同款最低可信价 ¥$nowPrice 较买入 ¥$purchasePrice 有变化，但无法申请价保"
        ProtectionWindowState.NOT_DROPPED ->
            "买入 ¥$purchasePrice，当前同款最低可信价 ¥$nowPrice：差额未超过提醒门槛"
        ProtectionWindowState.CLAIMABLE ->
            "买入 ¥$purchasePrice → 当前同款最低可信价 ¥$nowPrice，预计可退 ¥$claimableYuan，剩余 $remainingDays 天（以平台审核为准）"
        ProtectionWindowState.NO_PRICE ->
            "本轮暂取不到现价，价保状态未知（不拿 0 或旧价充数）"
    }
}
