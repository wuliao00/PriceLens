package com.pricelens.domain

import com.pricelens.util.PriceFormatter
import com.pricelens.util.QueryRelevance
import com.pricelens.util.SearchQueryCleaner
import kotlin.math.roundToInt

/**
 * 自动追价的自适应调度、关键词同款匹配与「防被套」买入信号。
 *
 * 全部是**纯函数**：无 Context、无 Room、无网络、无时间 API（"现在几点/是否 WiFi/电量低不低"
 * 一律由调用方算好传进来），因此可以在 JVM 单测里把每个边界钉死。
 * 使用方：[com.pricelens.data.repository.AutoTrackRepository]（轮次与设置）、
 * [com.pricelens.worker.AutoTrackWorker]（按 [decideNext] 重排下一轮）、
 * 以及 UI 侧直接展示 [BuySignal]。
 *
 * 与既有 `util/judgePrice` 的关系：那是概览页「≈ 历史低价 / 疑似先涨后降」的粗判（5% 档），
 * 这里按本次大更新的口径单独实现（≤2% 判近低、近 7 天先涨后降 >10% 判假降价），
 * 两者阈值不同、互不替代；本文件是追价/价保链路的唯一口径。
 *
 * F1 纪律：本文件的任何判断都只接受**真现价**（[PriceSample.isLive]，即 `!referenceOnly`）。
 * 星罗「券后历史低价」这类参考值既然不是今天的价，就既不能触发降价通知，也不能当买入信号依据。
 */

/**
 * 一轮自动追价的调度输入。
 *
 * @param enabled 用户是否开启自动追价（关闭时链式任务应结束，不再排下一轮）
 * @param intervalMinutes 用户选择的巡检间隔档位（30/60/120/180/360 分钟；不在档位内会被归一到最近档）
 * @param activeTargets 当前活跃盯价目标数（0 时没有可追的对象，链应结束）
 * @param nearestDistanceRatio 最近的那个目标「现价距目标价」的比例：`(现价-目标价)/目标价`，
 *   ≤0 表示已经到/低于目标；null = 目前没有任何历史价可算（不是 0）
 * @param quietHoursEnabled 用户是否配置了免打扰时段（关掉时不算"夜间"）
 * @param quietPauseEnabled 夜间是否暂停自动追价（暂停 = 免打扰时段整段不跑；不暂停 = 夜间降频）
 * @param onWifi 当前是否 WiFi（查询失败按"是"处理由调用方决定，与 NotificationGate 同口径）
 * @param wifiOnly 是否仅在 WiFi 下追价
 * @param batteryLow 是否处于低电量（且未充电）
 * @param lastRunAtMs 上一轮完成时刻（毫秒；0 = 本进程/本机还没跑过；仅用于日志与状态，不参与间隔计算）
 */
data class AutoTrackPlanInput(
    val enabled: Boolean,
    val intervalMinutes: Int,
    val activeTargets: Int,
    val nearestDistanceRatio: Double?,
    val nowMinuteOfDay: Int,
    val quietHoursEnabled: Boolean,
    val quietPauseEnabled: Boolean,
    val quietStartMinute: Int,
    val quietEndMinute: Int,
    val onWifi: Boolean,
    val wifiOnly: Boolean,
    val batteryLow: Boolean,
    val lastRunAtMs: Long = 0L
)

/**
 * 下一轮调度决策。
 *
 * @param shouldRun true = 此刻适合真正发起一轮检查（否则只重排、不发网络请求）
 * @param nextDelayMinutes 距下一轮应等待的分钟数；**0 表示链结束**（自动追价关闭/没有目标）
 * @param reason 人读原因（写进日志与 [com.pricelens.data.repository.AutoTrackState.lastNote] 的候选文案）
 */
data class AutoTrackDecision(val shouldRun: Boolean, val nextDelayMinutes: Int, val reason: String)

/**
 * 一条候选「同款」的匹配结论（[AutoTrackPolicy.matchOffer]）。
 *
 * @param accepted 是否通过严格条件（只有 true 才能当本轮现价/写曲线/触发通知）
 * @param relevance 标题与关键词的重叠分（0..1，仅供排序与置信度展示，不是放行条件本身）
 * @param reason 人读原因（不匹配时写清是哪一条挡下的，便于真机排查"为什么没查到"）
 */
data class OfferMatch(val accepted: Boolean, val relevance: Double, val reason: String) {
    companion object {
        fun reject(reason: String): OfferMatch = OfferMatch(false, 0.0, reason)
    }
}

/** 买入信号种类（UI 直接按此出徽标/文案） */
enum class BuySignalKind {
    /** 现价已贴近历史最低（≤2%） */
    NEAR_HISTORY_LOW,

    /** 近 7 天先涨后降且回落幅度 >10%，警惕"假降价" */
    FAKE_DROP,

    /** 数据不足/单源/多源不一致：提醒去商品页核价，不给"可买"结论 */
    VERIFY_PAGE,

    /** 价格处于常规区间，没有可行动的结论 */
    WATCH
}

/**
 * 买前「防被套」信号（UI 直接展示）。
 *
 * @param kind 枚举种类，UI 据此配图标/颜色
 * @param label 短标签（≤8 字，徽标用）
 * @param detail 一句话解释（含具体数字与来源，正文用）
 */
data class BuySignal(val kind: BuySignalKind, val label: String, val detail: String)

/**
 * 自动追价规则收口点。同样的输入永远得到同样的决策，便于单测与真机复盘。
 */
object AutoTrackPolicy {

    /** 自动追价的硬下限：再频繁只会浪费流量与电量（用户可选档位见 [INTERVAL_STEPS_MINUTES]） */
    const val MIN_INTERVAL_MINUTES: Int = 30

    /** 主动巡检的硬上限：一天最多也只需看几次 */
    const val MAX_INTERVAL_MINUTES: Int = 360

    /** 用户可选间隔档位（分钟）；[decideNext] 的输出只会落在这几档里（夜间暂停的等待值除外） */
    val INTERVAL_STEPS_MINUTES: List<Int> = listOf(30, 60, 120, 180, 360)

    /** 「近历史低」阈值：现价 ≤ 历史最低 ×(1+2%) */
    const val NEAR_HISTORY_LOW_RATIO: Double = 0.02

    /** 「假降价」阈值：近 7 天先涨后降的回落幅度必须 **>10%**（严格大于） */
    const val FAKE_DROP_RATIO: Double = 0.10

    /** 候选价与历史参考价的可接受比值下界（挡住"不同规格/挂羊头卖狗肉"的低价候选） */
    const val MIN_PRICE_RATIO: Double = 0.5

    /** 候选价与历史参考价的可接受比值上界 */
    const val MAX_PRICE_RATIO: Double = 2.0

    /** 绝对价格上限：超过它基本是解析错误（如把"88VIP 88"拼成 8888）而不是真价格 */
    const val MAX_SANE_PRICE: Double = 1_000_000.0

    private const val MINUTES_PER_DAY = 24 * 60

    /**
     * 决策下一轮何时跑、此刻跑不跑。
     *
     * 优先级（自上而下，先命中先返回）：
     *  1. 关闭 / 没有目标 → 链结束（`nextDelayMinutes = 0`）；
     *  2. 夜间暂停 → 跳过，等到免打扰结束（等待值限制在 [MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES]）；
     *  3. 低电量（未充电）→ 跳过并降到 ≥180 分钟一档；
     *  4. 仅 WiFi 而当前非 WiFi → 跳过，30 分钟后重试（连上 WiFi 就继续）；
     *  5. 正常：按"最近目标距离"自适应缩短间隔，夜间不暂停时最少 180 分钟一档。
     *
     * @param nowMs 当前时刻（毫秒）——目前只用于日志/未来扩展，决策本身只用墙上时间分钟数
     */
    fun decideNext(input: AutoTrackPlanInput, nowMs: Long): AutoTrackDecision {
        if (!input.enabled) {
            return AutoTrackDecision(false, 0, "自动追价已关闭")
        }
        if (input.activeTargets <= 0) {
            return AutoTrackDecision(false, 0, "没有可追价的目标")
        }
        val interval = normalizeInterval(input.intervalMinutes)
        val inQuietWindow = input.quietHoursEnabled &&
            isQuietHours(input.nowMinuteOfDay, input.quietStartMinute, input.quietEndMinute)
        if (inQuietWindow && input.quietPauseEnabled) {
            val wait = minutesUntilQuietEnd(input.nowMinuteOfDay, input.quietStartMinute, input.quietEndMinute)
                .coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)
            return AutoTrackDecision(false, wait, "夜间暂停：免打扰结束后继续（约 $wait 分钟后）")
        }
        if (input.batteryLow) {
            val delay = maxOf(interval, 180)
            return AutoTrackDecision(false, delay, "电量偏低：降低检查频率（$delay 分钟后再试）")
        }
        if (input.wifiOnly && !input.onWifi) {
            return AutoTrackDecision(false, MIN_INTERVAL_MINUTES, "仅 WiFi 追价（当前非 WiFi）：30 分钟后重试")
        }
        val proximity = proximityIntervalMinutes(input.nearestDistanceRatio, interval)
        val period = if (inQuietWindow) maxOf(proximity, 180) else proximity
        val reason = when {
            inQuietWindow -> "夜间降频：每 $period 分钟检查"
            input.nearestDistanceRatio == null -> "暂无可算的现价：每 $period 分钟检查"
            input.nearestDistanceRatio!! <= 0.0 -> "已有目标到达/低于目标价：每 $period 分钟检查"
            input.nearestDistanceRatio!! <= NEAR_HISTORY_LOW_RATIO -> {
                "最近目标距达标 ≤2%：每 $period 分钟检查"
            }
            input.nearestDistanceRatio!! <= 0.05 -> "最近目标距达标 ≤5%：每 $period 分钟检查"
            input.nearestDistanceRatio!! <= 0.10 -> "最近目标距达标 ≤10%：每 $period 分钟检查"
            else -> "常规巡检：每 $period 分钟检查"
        }
        return AutoTrackDecision(true, period.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES), reason)
    }

    /**
     * 用户设置/外部传入的分钟数 → 合法档位：先夹到 [MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES]，
     * 再取**不超过它的最大档位**（宁可慢一点，也不擅自加速用户的省电选择），最低落到 30。
     */
    fun normalizeInterval(minutes: Int): Int {
        val clamped = minutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)
        return INTERVAL_STEPS_MINUTES.lastOrNull { it <= clamped } ?: MIN_INTERVAL_MINUTES
    }

    /**
     * 免打扰时段判断：`[start, end)` 半开区间，跨零点（start > end）自动按两段算；
     * `start == end` 视为"没有设置时段"（不静默变成全天暂停）。
     * 语义与 `worker/NotificationGate.inQuietWindow` 一致，两处分别实现是为了
     * 让 domain 不反向依赖 worker（否则纯函数层会拖上 Android 类）。
     */
    fun isQuietHours(nowMinute: Int, startMinute: Int, endMinute: Int): Boolean {
        val start = normalizeMinute(startMinute)
        val end = normalizeMinute(endMinute)
        val now = normalizeMinute(nowMinute)
        if (start == end) return false
        return if (start < end) now in start until end else now >= start || now < end
    }

    /** 当前处于免打扰时段时，还要等多少分钟才结束（不在时段内返回 0） */
    fun minutesUntilQuietEnd(nowMinute: Int, startMinute: Int, endMinute: Int): Int {
        if (!isQuietHours(nowMinute, startMinute, endMinute)) return 0
        val now = normalizeMinute(nowMinute)
        val end = normalizeMinute(endMinute)
        val start = normalizeMinute(startMinute)
        return if (start < end) {
            (end - now).coerceAtLeast(1)
        } else if (now >= start) {
            (MINUTES_PER_DAY - now + end).coerceAtLeast(1)
        } else {
            (end - now).coerceAtLeast(1)
        }
    }

    /**
     * 严格同款匹配（关键词通道的唯一放行点）：
     *  1. 关键词/标题非空；
     *  2. 价格是**正的真数**且不超过 [MAX_SANE_PRICE]（0/NaN/负数 = 这一路没给价，绝不落 0）；
     *  3. 标题必须过 [QueryRelevance.isRelevant]（配件/图书/其它品牌/顺序颠倒都会被它挡下）。
     *
     * 只做"能不能当候选"的判定；"这个价和该商品历史比是否合理"由
     * [isPriceRatioReasonable] 在拿到历史参考价后再判（两件事分开，单测边界才清楚）。
     */
    fun matchOffer(keyword: String, title: String, price: Double): OfferMatch {
        if (keyword.isBlank()) return OfferMatch.reject("关键词为空")
        if (title.isBlank()) return OfferMatch.reject("候选没有标题")
        if (!price.isFinite() || price <= 0.0) return OfferMatch.reject("候选没有有效价格")
        if (price > MAX_SANE_PRICE) return OfferMatch.reject("候选价格异常（$price）")
        if (!QueryRelevance.isRelevant(keyword, title)) return OfferMatch.reject("标题与关键词不相关")
        val relevance = SearchQueryCleaner.titleOverlap(keyword, title)
        return OfferMatch(true, relevance.coerceIn(0.0, 1.0), "标题相关（重叠 ${(relevance * 100).roundToInt()}%）")
    }

    /**
     * 候选价与历史参考价的比值是否在合理范围（[MIN_PRICE_RATIO]..[MAX_PRICE_RATIO]）。
     *
     * 参考价拿不到时**不做比值断言**（返回 true），但价格本身仍须是正数；
     * 这挡住的是"搜索到完全不同价位的东西"（如 99999 的套装 vs 5999 的单品），
     * 而不是精确比价。
     */
    fun isPriceRatioReasonable(candidate: Double, reference: Double?): Boolean {
        if (!candidate.isFinite() || candidate <= 0.0 || candidate > MAX_SANE_PRICE) return false
        if (reference == null || !reference.isFinite() || reference <= 0.0) return true
        val ratio = candidate / reference
        return ratio in MIN_PRICE_RATIO..MAX_PRICE_RATIO
    }

    /**
     * 买前「防被套」信号（纯函数，UI 直接读）。
     *
     * @param nowPrice 当前可信现价；null/0/负数 = 没拿到价（只给核价提醒，绝不编一个价）
     * @param historyLow 历史最低价（来自 `price_history.dayLow` 折叠后的最低点；null = 未知）
     * @param recentPrices 近 7 天价格序列（时间正序，任意来源的真现价；不传参考值/0）
     * @param liveSourceCount 本轮给出**可比现价**的来源数（0/1 = 单源，多源一致才算可信）
     * @param lowConfidence 准确性引擎给出的"低置信"结论（如多源冲突/数据过时）
     */
    fun buySignal(
        nowPrice: Double?,
        historyLow: Double?,
        recentPrices: List<Double> = emptyList(),
        liveSourceCount: Int = 0,
        lowConfidence: Boolean = false
    ): BuySignal {
        val price = nowPrice?.takeIf { it.isFinite() && it > 0.0 && it <= MAX_SANE_PRICE }
            ?: return BuySignal(
                BuySignalKind.VERIFY_PAGE,
                "先去页面核价",
                "自动搜索没拿到可信现价，请打开商品页核价（确认当前售价与规格）"
            )
        val series = recentPrices.filter { it.isFinite() && it > 0.0 && it <= MAX_SANE_PRICE }
        fakeDropPeak(series)?.let { peak ->
            val drop = (peak - price) / peak
            return BuySignal(
                BuySignalKind.FAKE_DROP,
                "疑似先涨后降",
                "近 7 天从 ${PriceFormatter.format(peak)} 回落到 ${PriceFormatter.format(price)}" +
                    "（-${percent(drop)}），警惕先抬价再打折"
            )
        }
        if (liveSourceCount <= 1 || lowConfidence) {
            return BuySignal(
                BuySignalKind.VERIFY_PAGE,
                "单源数据待核价",
                "只有 $liveSourceCount 个来源给出该价格，请到商品页确认规格与券后价"
            )
        }
        val low = historyLow?.takeIf { it.isFinite() && it > 0.0 && it <= MAX_SANE_PRICE }
        if (low != null) {
            val gap = (price - low) / low
            if (gap <= NEAR_HISTORY_LOW_RATIO) {
                return BuySignal(
                    BuySignalKind.NEAR_HISTORY_LOW,
                    "接近历史低价",
                    "现价 ${PriceFormatter.format(price)} 距历史最低 ${PriceFormatter.format(low)}" +
                        " 仅 ${percent(maxOf(gap, 0.0))}"
                )
            }
        }
        return BuySignal(
            BuySignalKind.WATCH,
            "继续观察",
            "现价 ${PriceFormatter.format(price)} 仍在常规区间，未见异常波动"
        )
    }

    /**
     * 「先涨后降」的峰值：序列里存在某个点 p[i]，它比之前的低点高、又比最后一点高出 >[FAKE_DROP_RATIO]。
     *
     * 只把 p[i] 当"涨价后的高点"：正常一路下跌（没有先涨）不会命中；
     * 高点必须是**不是最后一点**（最后一点就是现价，自己跟自己比没有意义）。
     *
     * @return 命中的最大峰值；序列不足 3 个点或没有"先涨后降"形态时返回 null
     */
    private fun fakeDropPeak(prices: List<Double>): Double? {
        if (prices.size < 3) return null
        val last = prices.last()
        var earlierLow = prices.first()
        var best: Double? = null
        for (i in 1 until prices.size - 1) {
            val peak = prices[i]
            if (peak > earlierLow && (peak - last) / peak > FAKE_DROP_RATIO) {
                if (best == null || peak > best!!) best = peak
            }
            if (prices[i] < earlierLow) earlierLow = prices[i]
        }
        return best
    }

    /** 比例 → 人读百分比（四舍五入到整数百分比；0.023 → "2%"） */
    private fun percent(ratio: Double): String = "${(ratio * 100).roundToInt()}%"

    /**
     * 按"最近目标距离"给出的候选间隔：
     *  - 未知 → 用户档位；
     *  - 已到达/低于目标或距达标 ≤2% → 30；
     *  - ≤5% → 60；≤10% → 120；≤25% → 不超过 180；
     *  - 其它 → 用户档位。
     */
    private fun proximityIntervalMinutes(nearestDistanceRatio: Double?, interval: Int): Int {
        if (nearestDistanceRatio == null || !nearestDistanceRatio.isFinite()) return interval
        return when {
            nearestDistanceRatio <= NEAR_HISTORY_LOW_RATIO -> 30
            nearestDistanceRatio <= 0.05 -> 60
            nearestDistanceRatio <= 0.10 -> 120
            nearestDistanceRatio <= 0.25 -> minOf(interval, 180)
            else -> interval
        }
    }

    private fun normalizeMinute(minute: Int): Int = ((minute % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
}
