package com.pricelens.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * §3.5 语义徽标语调（阶段4：从 ui/components/PriceCard.kt 迁入主题层）。
 * 颜色不再是静态常量，而是按当前主题从 [SemanticPalette] 动态取值，
 * 暗色模式下自动获得浅色变体（对比度达标）。
 *
 * 2026-11 大改造：新增 [INFO]（电蓝：信息/链接/出处）与 [COUPON]（品红：券面额），
 * 与绿/红/灰一起构成"行情六色"，徽标、芯片、图例共用同一套语义。
 */
enum class BadgeTone {
    /** 利好：历史低价 / 可入 / 免费 */
    POSITIVE,

    /** 风险：先涨后降 / 观望 / 失败 */
    NEGATIVE,

    /** 中性：常态 / 一般说明 */
    NEUTRAL,

    /** 信息：出处 / 链接 / 数据说明 */
    INFO,

    /** 券：面额强调 / 到手价 */
    COUPON
}

/** 前景（文字）色：随主题切换语义色 */
@Composable
fun BadgeTone.fg(palette: SemanticPalette = LocalSemanticColors.current): Color = when (this) {
    BadgeTone.POSITIVE -> palette.lowPrice
    BadgeTone.NEGATIVE -> palette.suspicious
    BadgeTone.NEUTRAL -> palette.neutral
    BadgeTone.INFO -> palette.info
    BadgeTone.COUPON -> palette.coupon
}

/** 背景色：语义色 14% 透明度，浅底/暗底均柔和（大改造：12%→14%，胶囊更可读） */
@Composable
fun BadgeTone.bg(palette: SemanticPalette = LocalSemanticColors.current): Color = fg(palette).copy(alpha = 0.14f)
