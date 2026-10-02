package com.pricelens.domain

import java.text.DecimalFormat
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * §2.3 价格数字滚动（from→to）的纯逻辑：缓动取值 + 等宽格式化。
 *
 * 由 ui/coupon/CouponScreen.NetPriceHeader 的私有 countUp 做法提炼而来（该区本批次归其他代理，
 * 只提炼不改）。UI 侧只保留 `animateFloatAsState(tween(MotionDurations.PriceRoll,
 * easing = PriceLensEasing))` 产出的动画值，其余（取值序列、小数位、宽度锚点）都在这里，
 * 因此全部可以在 JVM 单测里钉死（本仓库无 Robolectric，Compose 侧只能这样保绿）。
 *
 * 宽度不抖策略 —— PriceType.PriceHero / PriceLarge 已开 `fontFeatureSettings = "tnum"`
 * （每个数字占同一格宽），所以抖动只可能来自**格数变化**，于是：
 *  1. 小数位按**目标值**取一次并全程固定：7,999 不会被渲染成 7,999.00，12.34 不会掉成 12.3；
 *  2. 跨千分位（999→1001）时两端格数不同 → 用等宽数字空格 [Pad] 把短的一侧补齐到
 *     [reservedCells]，全程格数恒定；占位补在**前缀之后、数字之前**，所以前缀左缘与数字右缘都不动；
 *  3. 缓动取 FastOutSlowIn（无 overshoot）→ 中间值不会越过两个端点，也就不会凭空多出一格。
 *
 * 已知代价（注释即口径，不粉饰）：
 *  - 跨数量级**下落**时（1,001→999.5）末帧仍带占位格，不缩回"最紧凑写法"——缩回就是抖动；
 *  - [Pad] 是 U+2007 FIGURE SPACE，开了 tnum 的字体下≈数字格宽；若某字体缺该字形回退普通空格，
 *    跨数量级那一帧仍可能微抖（真机未验证）。
 */
object NumberRoll {

    /** 最多保留的小数位（与 PriceFormatter 的 `#,##0.##` 口径一致） */
    const val MaxDecimals = 2

    /** 等宽数字场景下的占位空格（U+2007 FIGURE SPACE）：宽度≈一个数字格，用于跨数量级补格 */
    const val Pad: Char = '\u2007'

    /** 货币前缀（与 PriceFormatter.format 同一符号） */
    const val CurrencyPrefix = "¥"

    /** 默认帧步长：60fps ≈ 16ms，仅用于 [sequence] 的取值密度 */
    const val DefaultFrameMillis = 16

    /** 判断"scaled 值已是整数"的容差：价格量级（≤1e7×100）下浮点误差远小于它，真小数位不会被误判 */
    private const val IntegralTolerance = 1e-4

    /** 10^d 查表，避免 Math.pow 的浮点噪声 */
    private val Pow10 = doubleArrayOf(1.0, 10.0, 100.0)

    /** 按小数位缓存的格式化模板（含千分位）；DecimalFormat 非线程安全 → 每次取副本 */
    private val Patterns = Array(MaxDecimals + 1) { d ->
        DecimalFormat(if (d == 0) "#,##0" else "#,##0." + "0".repeat(d))
    }

    /**
     * FastOutSlowIn = cubic-bezier(0.4, 0, 0.2, 1)，与 Compose `FastOutSlowInEasing`
     * （即 ui.theme.PriceLensEasing）同一条曲线的解析实现。
     *
     * 注意：UI 侧从 animateFloatAsState 拿到的值**已经**缓动过了，不要再套一层（会二次缓动）；
     * 这里只服务 [sequence] 与单测，让"纯逻辑预览的曲线"和"真机跑的曲线"是同一条。
     */
    fun easeFastOutSlowIn(linearProgress: Float): Float {
        if (linearProgress <= 0f) return 0f
        if (linearProgress >= 1f) return 1f
        // 控制点 x 单调递增 → X(t) 对 t 单调，二分求 t 使 X(t)=x，再取 Y(t)=3t²-2t³
        var lo = 0f
        var hi = 1f
        var t = linearProgress
        repeat(24) {
            t = (lo + hi) / 2f
            if (bezier(t, 0.4f, 0.2f) < linearProgress) lo = t else hi = t
        }
        return bezier(t, 0f, 1f)
    }

    /** 一维三次贝塞尔（起点 0、终点 1，中间控制点 p1/p2） */
    private fun bezier(t: Float, p1: Float, p2: Float): Float {
        val inv = 1f - t
        return 3f * inv * inv * t * p1 + 3f * inv * t * t * p2 + t * t * t
    }

    /** 已缓动进度 → 滚动中的价格值（纯线性插值 + 进度钳制，绝不越过端点） */
    fun valueAt(from: Double, to: Double, easedProgress: Float): Double {
        val p = easedProgress.coerceIn(0f, 1f).toDouble()
        return from + (to - from) * p
    }

    /**
     * 一次滚动的取值序列：第 0 帧 = from，末帧**精确** = to（不留 0.9999 的尾巴）。
     * [durationMillis] ≤ 0 时退化为 [from, to] 两帧。
     */
    fun sequence(from: Double, to: Double, durationMillis: Int, frameMillis: Int = DefaultFrameMillis): List<Double> {
        val step = if (frameMillis > 0) frameMillis else DefaultFrameMillis
        val steps = if (durationMillis <= 0) 1 else (durationMillis / step).coerceAtLeast(1)
        return (0..steps).map { valueAt(from, to, easeFastOutSlowIn(it.toFloat() / steps)) }
    }

    /** 目标值实际需要的小数位（0..[MaxDecimals]）：整数价 0 位、一位小数 1 位、其余 2 位 */
    fun decimalsFor(value: Double): Int {
        if (!value.isFinite()) return MaxDecimals
        for (d in 0..MaxDecimals) {
            val scaled = value * Pow10[d]
            if (abs(scaled) < 1e15 && abs(scaled - scaled.roundToLong()) < IntegralTolerance) return d
        }
        return MaxDecimals
    }

    /** 按固定小数位渲染（含货币前缀、千分位），不做占位补格 */
    fun formatText(value: Double, decimals: Int, prefix: String = CurrencyPrefix): String {
        val formatter = Patterns[decimals.coerceIn(0, MaxDecimals)].clone() as DecimalFormat
        return prefix + formatter.format(value)
    }

    /** 本次滚动全程预留的格数 = 两个端点在同一小数位（按目标值取）下的较长者 */
    fun reservedCells(from: Double, to: Double, prefix: String = CurrencyPrefix): Int {
        val decimals = decimalsFor(to)
        return maxOf(formatText(from, decimals, prefix).length, formatText(to, decimals, prefix).length)
    }

    /**
     * 滚动中某一帧的显示文本：[value] 取值、[to] 定小数位、两端点定预留格数。
     * 全程 [cellCount] 恒定 → tnum 等宽数字下宽度不抖。
     */
    fun rollText(value: Double, from: Double, to: Double, prefix: String = CurrencyPrefix): String {
        val full = formatText(value, decimalsFor(to), prefix)
        val padding = (reservedCells(from, to, prefix) - full.length).coerceAtLeast(0)
        // 占位补在前缀之后、数字之前：前缀左缘与数字右缘都不动
        return prefix + Pad.toString().repeat(padding) + full.substring(prefix.length)
    }

    /** [rollText] 的格数（单测用它钉"宽度不抖"这条不变量） */
    fun cellCount(text: String): Int = text.length
}
