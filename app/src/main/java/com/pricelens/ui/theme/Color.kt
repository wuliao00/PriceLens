package com.pricelens.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** §3.1 动态取色（Android 12+ 壁纸取色）关闭时的品牌基准色板，低版本回退品牌蓝 */
val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF1B6EF3),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E6FF),
    secondary = Color(0xFF565E71),
    surface = Color(0xFFFCFCFF),
    surfaceVariant = Color(0xFFE1E2EC),
    background = Color(0xFFFCFCFF),
    error = Color(0xFFBA1A1A)
)

val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFA8C8FF),
    onPrimary = Color(0xFF00306B),
    primaryContainer = Color(0xFF00468E),
    secondary = Color(0xFFBEC6DC),
    surface = Color(0xFF1A1B21),
    surfaceVariant = Color(0xFF44464F),
    background = Color(0xFF1A1B21),
    error = Color(0xFFFFB4AB)
)

/**
 * §3.5 语义色（暗色感知）：历史低价 / 先涨后降 / 中性标签。
 * 亮色为深饱和色（浅底高对比），暗色为浅色变体（深底保证对比度）。
 * 经 LocalSemanticColors 在 PriceLensTheme 中按主题提供。
 */
@Immutable
data class SemanticPalette(
    val lowPrice: Color,
    val suspicious: Color,
    val neutral: Color
)

/** 亮色语义色：深绿 / 深红 / 中灰，浅底对比度达标 */
private val LightSemantic = SemanticPalette(
    lowPrice = Color(0xFF2E7D32),
    suspicious = Color(0xFFC62828),
    neutral = Color(0xFF757575)
)

/** 暗色语义色：浅色变体，暗底（#1A1B21 级）对比度达标 */
private val DarkSemantic = SemanticPalette(
    lowPrice = Color(0xFF81C784),
    suspicious = Color(0xFFEF9A9A),
    neutral = Color(0xFFB0B0B0)
)

/** 当前主题的语义色入口：PriceLensTheme 按 darkTheme 注入对应版本 */
val LocalSemanticColors = staticCompositionLocalOf { LightSemantic }

/** 按主题选择语义色集（供 PriceLensTheme 内部调用） */
internal fun semanticColorsFor(darkTheme: Boolean): SemanticPalette = if (darkTheme) DarkSemantic else LightSemantic

/**
 * §3.5 兼容对象：保持原 `SemanticColors.X` 静态引用不破坏。
 * 委托到亮色值；域外引用点（BilibiliScreen/CommunityScreen/CouponScreen/PriceCard）
 * 后续迁移时应改用 LocalSemanticColors.current 以获得暗色适配。
 */
object SemanticColors {
    val LowPrice = LightSemantic.lowPrice
    val Suspicious = LightSemantic.suspicious
    val Neutral = LightSemantic.neutral
}
