package com.pricelens.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 「熔金行情」品牌色板（2026-11 手机端大改造）。
 *
 * 配色从"价格"这件事本身长出来，而不是借一套通用蓝：
 *  - **主色 = 金**（金钱/价签的直觉色）：亮色用深金 #A16207 保证浅底对比度，
 *    暗色用亮金 #FBBF24 在墨黑底上发光；
 *  - **语义三原色**：跌/利好 = 绿、涨/风险 = 红、信息/链接 = 电蓝，另补券 = 品红；
 *  - **中性色分冷暖**：亮色是暖纸（#F5F2EB 底 / #FCFAF4 面），暗色是冷墨
 *    （#0E1015 底 / #151820 面），背景/表面/容器/分隔线四层各有其位。
 *
 * 全部 29 个 M3 角色显式给值：不留任何一个槽位落回 Material 默认的紫，
 * 动态取色关闭时（新默认）整套品牌色就是用户的默认体验。
 */
val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFFA16207),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFBE7B8),
    onPrimaryContainer = Color(0xFF573A00),
    secondary = Color(0xFF6D6046),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEFE5CE),
    onSecondaryContainer = Color(0xFF4C4130),
    tertiary = Color(0xFF1D4ED8),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDDE7FF),
    onTertiaryContainer = Color(0xFF0A2A6B),
    error = Color(0xFFC62828),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFADADA),
    onErrorContainer = Color(0xFF7F1D1D),
    background = Color(0xFFF5F2EB),
    onBackground = Color(0xFF1D1A16),
    surface = Color(0xFFFCFAF4),
    onSurface = Color(0xFF1D1A16),
    surfaceVariant = Color(0xFFEAE4D5),
    onSurfaceVariant = Color(0xFF60594B),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F4EC),
    surfaceContainer = Color(0xFFF0ECE1),
    surfaceContainerHigh = Color(0xFFE9E4D7),
    surfaceContainerHighest = Color(0xFFE2DCCD),
    surfaceTint = Color(0xFFA16207),
    inverseSurface = Color(0xFF332F2A),
    inverseOnSurface = Color(0xFFF4F0E6),
    inversePrimary = Color(0xFFFBBF24),
    outline = Color(0xFF8B8171),
    outlineVariant = Color(0xFFD9D2C0),
    scrim = Color(0xFF000000)
)

val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFFBBF24),
    onPrimary = Color(0xFF231600),
    primaryContainer = Color(0xFF5A4000),
    onPrimaryContainer = Color(0xFFFFE2A8),
    secondary = Color(0xFFCDBFA4),
    onSecondary = Color(0xFF3B3226),
    secondaryContainer = Color(0xFF524737),
    onSecondaryContainer = Color(0xFFEBE0CB),
    tertiary = Color(0xFF82AFFF),
    onTertiary = Color(0xFF0A2455),
    tertiaryContainer = Color(0xFF213C78),
    onTertiaryContainer = Color(0xFFDAE6FF),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF4A0E0E),
    errorContainer = Color(0xFF7A1F1F),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E1015),
    onBackground = Color(0xFFECE8DF),
    surface = Color(0xFF151820),
    onSurface = Color(0xFFECE8DF),
    surfaceVariant = Color(0xFF272C37),
    onSurfaceVariant = Color(0xFFACA696),
    surfaceContainerLowest = Color(0xFF0A0C10),
    surfaceContainerLow = Color(0xFF181C26),
    surfaceContainer = Color(0xFF1E232E),
    surfaceContainerHigh = Color(0xFF252B38),
    surfaceContainerHighest = Color(0xFF2D3442),
    surfaceTint = Color(0xFFFBBF24),
    inverseSurface = Color(0xFFECE8DF),
    inverseOnSurface = Color(0xFF1D1A16),
    inversePrimary = Color(0xFFA16207),
    outline = Color(0xFF716A5C),
    outlineVariant = Color(0xFF353B49),
    scrim = Color(0xFF000000)
)

/**
 * §3.5 语义色（暗色感知）：历史低价 / 先涨后降 / 中性标签，大改造新增 券 / 信息 两色。
 * 亮色为深饱和色（浅底高对比），暗色为浅色变体（深底保证对比度）。
 * 经 LocalSemanticColors 在 PriceLensTheme 中按主题提供。
 *
 * [coupon] / [info] 带默认值：旧构造点（只给前三色）不用改也能编译，
 * 新组件（券卡、信息徽标）按主题取到完整六色。
 */
@Immutable
data class SemanticPalette(
    val lowPrice: Color,
    val suspicious: Color,
    val neutral: Color,
    val coupon: Color = Color(0xFFA21CAF),
    val info: Color = Color(0xFF1D4ED8)
)

/** 亮色语义色：深绿 / 深红 / 暖灰 / 深品红 / 电蓝，浅底对比度达标 */
private val LightSemantic = SemanticPalette(
    lowPrice = Color(0xFF15803D),
    suspicious = Color(0xFFC62828),
    neutral = Color(0xFF78716C),
    coupon = Color(0xFFA21CAF),
    info = Color(0xFF1D4ED8)
)

/** 暗色语义色：酸绿 / 浅红 / 暖灰 / 浅品红 / 浅蓝，墨底（#0E1015 级）对比度达标 */
private val DarkSemantic = SemanticPalette(
    lowPrice = Color(0xFF4ADE80),
    suspicious = Color(0xFFFF8A80),
    neutral = Color(0xFFA8A294),
    coupon = Color(0xFFF0ABFC),
    info = Color(0xFF82AFFF)
)

/** 当前主题的语义色入口：PriceLensTheme 按 darkTheme 注入对应版本 */
val LocalSemanticColors = staticCompositionLocalOf { LightSemantic }

/** 按主题选择语义色集（供 PriceLensTheme 内部调用） */
internal fun semanticColorsFor(darkTheme: Boolean): SemanticPalette = if (darkTheme) DarkSemantic else LightSemantic

/**
 * §3.5 兼容对象：保持原 `SemanticColors.X` 静态引用不破坏。
 * 委托到亮色值；新引用点一律改用 LocalSemanticColors.current 以获得暗色适配。
 */
object SemanticColors {
    val LowPrice = LightSemantic.lowPrice
    val Suspicious = LightSemantic.suspicious
    val Neutral = LightSemantic.neutral
}
