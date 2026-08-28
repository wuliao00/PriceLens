package com.pricelens.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * §3.5 语义徽标语调（阶段4：从 ui/components/PriceCard.kt 迁入主题层）。
 * 颜色不再是静态常量，而是按当前主题从 [SemanticPalette] 动态取值，
 * 暗色模式下自动获得浅色变体（对比度达标）。
 */
enum class BadgeTone {
    /** 利好：历史低价 / 可入 / 免费 */
    POSITIVE,

    /** 风险：先涨后降 / 观望 / 券面额强调 */
    NEGATIVE,

    /** 中性：常态 / 一般说明 */
    NEUTRAL
}

/** 前景（文字）色：随主题切换语义色 */
@Composable
fun BadgeTone.fg(palette: SemanticPalette = LocalSemanticColors.current): Color = when (this) {
    BadgeTone.POSITIVE -> palette.lowPrice
    BadgeTone.NEGATIVE -> palette.suspicious
    BadgeTone.NEUTRAL -> palette.neutral
}

/** 背景色：语义色 12% 透明度，浅底/暗底均柔和 */
@Composable
fun BadgeTone.bg(palette: SemanticPalette = LocalSemanticColors.current): Color = fg(palette).copy(alpha = 0.12f)
