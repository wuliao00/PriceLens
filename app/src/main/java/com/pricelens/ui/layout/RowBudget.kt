package com.pricelens.ui.layout

import kotlin.math.ceil
import kotlin.math.max

/**
 * 紧凑行/卡片的尺寸预算（纯算术，JVM 单测直接断言）。
 *
 * 为什么算术放这里而不是把数字写死在 Theme.kt：本仓库的教训是「UI 密度要按最坏负载测」，
 * 而最坏负载是三个变量的乘积——字号（sp 随系统 fontScale 放大）、dp 内边距（不随字号变）、
 * 可用宽度（真机最窄那台）。数字一旦写死在令牌里就没人再核对，改一处就悄悄溢出。
 * 现在 `Dims.RowCompact = RowBudget.RowCompactDp.dp`，动任何输入都得过 RowBudgetTest。
 *
 * 真机基准（本轮走查机 OPPO PLB110 / Android 15 / 1256×2760 @560dpi，2026-10-02 实测 wm size/density）：
 *  density = 560/160 = 3.5 px/dp → 逻辑宽 1256/3.5 = 358.86dp，逻辑高 2760/3.5 = 788.57dp。
 *  选它是因为两台测试机里它更窄：旧机 vivo V2156A 是 1080/3.0 = 360.0dp，比这台宽 1.14dp，
 *  按这台算出来的行在旧机上只会更松。**注意这是几何基准，不是观感结论**——
 *  PriceLens 还没在这台机上跑过（版面与深色观感待本轮真机走查复核，见 docs/ROADMAP 的第三批行）。
 *  概览 LazyColumn 的 contentPadding = Dims.SpacingXL(20dp) 两侧 → 列表可用 318.86dp；
 *  紧凑行自身左右各 Dims.SpacingS(8dp) → 内容宽 CONTENT_WIDTH_DP = 302.86dp。
 */
object RowBudget {
    /** Material 触控目标下限：可点行高不得低于此值 */
    const val TOUCH_MIN_DP = 48

    /** 国产 ROM 常见「大字号」档 ≈1.15；1.3 为超大字号档（只用来验证不塌陷） */
    const val FONT_SCALE_LARGE = 1.15f

    /** 超大字号档 */
    const val FONT_SCALE_XLARGE = 1.3f

    /** 真机最窄可用宽（推导见文件注释） */
    const val DEVICE_WIDTH_DP = 358.86f

    /** 屏幕左右留白（Dims.SpacingXL） */
    const val SCREEN_GUTTER_DP = 20f

    /** 行自身左右内边距（Dims.SpacingS） */
    const val ROW_INNER_PAD_DP = 8f

    /** 一行能放内容的真实宽度 */
    const val CONTENT_WIDTH_DP = DEVICE_WIDTH_DP - 2 * SCREEN_GUTTER_DP - 2 * ROW_INNER_PAD_DP

    /** 段间距（Dims.SpacingS，一行三处） */
    const val GAP_DP = 8f

    // —— 最坏负载：不是"平均有多长"，是"每条都可能同时取到多长" ——

    /** "¥12,999" = 8 字符 @16sp 等宽数字（≈0.545em） */
    const val PRICE_SLOT_DP = 70f

    /** "2026-10-01" = 10 字符 @12sp（≈0.55em） */
    const val STAMP_SLOT_DP = 66f

    /** 徽标"无结果" = 3 汉字 @12sp + 左右各 Dims.SpacingS */
    const val BADGE_SLOT_DP = 52f

    /** 源名（bodyMedium 14sp）下一个汉字的宽度：CJK ≈ 1em */
    const val CJK_CHAR_SP = 14f

    /** 行内最高绘制元素：徽标 = labelSmall 行高 16 + 上下 4×2 */
    const val ROW_CONTENT_DP = 24

    /** 行上下内边距（Dims.SpacingL ×2） */
    const val ROW_VPAD_DP = 16

    /** 网格：派生高度向上取整到这里，避免 1dp 抖动 */
    const val GRID_DP = 4

    /**
     * 行高 = 内容（随字号放大）+ 上下内边距（不随字号变）→ 抬到触控下限 → 取整到网格。
     * 基准输入（24 / 16 / 1.15）派生出 **60dp**，即 [RowCompactDp]。
     */
    fun rowHeightDp(contentDp: Int, vPadDp: Int, fontScale: Float, gridDp: Int = GRID_DP): Int {
        val scaled = ceil(contentDp * fontScale).toInt()
        val withPadding = max(scaled + 2 * vPadDp, TOUCH_MIN_DP)
        return ceilToGrid(withPadding, gridDp)
    }

    /** 紧凑报价行的行高（60）；56 会在大字号下裁掉徽标，64 则每行白花 4dp 滚动量 */
    val RowCompactDp: Int = rowHeightDp(ROW_CONTENT_DP, ROW_VPAD_DP, FONT_SCALE_LARGE)

    /** 固定段总宽：字号段随 fontScale 放大，段间距不放大 */
    fun fixedSlotsDp(fontScale: Float = 1f): Float = (PRICE_SLOT_DP + STAMP_SLOT_DP + BADGE_SLOT_DP) * fontScale + 3 * GAP_DP

    /** 留给源名的宽度（负数就说明固定段自己已经溢出） */
    fun labelWidthDp(widthDp: Float = CONTENT_WIDTH_DP, fontScale: Float = 1f): Float = widthDp - fixedSlotsDp(fontScale)

    /** 源名能放几个汉字（向下取整；这是"最坏负载"下的结论，平均负载不作数） */
    fun labelUnits(widthDp: Float = CONTENT_WIDTH_DP, fontScale: Float = 1f): Int =
        (labelWidthDp(widthDp, fontScale) / (CJK_CHAR_SP * fontScale)).toInt()

    /** 一行能放几个某字号的汉字（社区三段用它算标题/摘要/元信息预算） */
    fun lineUnits(charSp: Float, widthDp: Float = CONTENT_WIDTH_DP, fontScale: Float = 1f): Int = (widthDp / (charSp * fontScale)).toInt()

    /**
     * 最坏负载下这一行是否还放得下 [minLabelUnits] 个字的源名。
     * 放不下的结论是"别再往这一行加字段"，而不是"再加一个 weight 试试看"。
     */
    fun worstCaseFits(widthDp: Float, fontScale: Float, minLabelUnits: Int = 2): Boolean =
        labelWidthDp(widthDp, fontScale) >= CJK_CHAR_SP * fontScale * minLabelUnits

    /** 头卡缩略图边长 = 两行标题 + 一行现价（与文本列同高，图不会把行拉高） */
    fun headerThumbDp(titleLines: Int, titleLineDp: Int, priceLineDp: Int, gridDp: Int = GRID_DP): Int =
        ceilToGrid(titleLines * titleLineDp + priceLineDp, gridDp)

    private fun ceilToGrid(value: Int, gridDp: Int): Int = (value + gridDp - 1) / gridDp * gridDp
}
