package com.pricelens.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pricelens.ui.layout.RowBudget

/**
 * 主题组合入口：色板（Color.kt）+ 字体（Type.kt）+ 形状（Shape.kt）+ 语义色（LocalSemanticColors）。
 * 动效规格见 Motion.kt（纯常量令牌，无需注入）。
 */
@Composable
fun PriceLensTheme(darkTheme: Boolean = isSystemInDarkTheme(), dynamicColor: Boolean = true, content: @Composable () -> Unit) {
    // §3.1 动态取色（Android 12+ 壁纸取色），低版本回退品牌基准色板
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(LocalSemanticColors provides semanticColorsFor(darkTheme)) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = PriceLensTypography,
            shapes = PriceLensShapes,
            content = content
        )
    }
}

/** §3.3 间距与圆角基准 */
object Dims {
    val SpacingXS = 4.dp
    val SpacingS = 8.dp
    val SpacingM = 12.dp
    val SpacingL = 16.dp
    val SpacingXL = 20.dp
    val SpacingXXL = 24.dp
    val SpacingXXXL = 32.dp
    val CardCorner = 16.dp
    val ButtonCorner = 12.dp
    val ChipCorner = 8.dp
    val ListItemHeight = 100.dp

    /**
     * 各源报价的**紧凑单行**高度（2026-10-02 密度重排）。
     *
     * 数字不是拍的，由 [RowBudget.rowHeightDp] 派生，改输入就先得让 RowBudgetTest 变红：
     *  行内最高元素 = 状态徽标 24dp（labelSmall 12sp/16sp 行高 + 上下各 4dp 内边距）
     *  × 系统"大字号"档 fontScale 1.15 → 27.6 → 28dp
     *  + 上下内边距各 [SpacingL]16dp → 60dp → 取整到 4dp 网格 = **60dp**。
     *
     * 为什么不是 56：56 在 fontScale ≥1.15 时把徽标下沿裁掉（上一轮 ListItemHeight 写死
     * 100dp 后被大字号撑破，是同一个错的另一个方向）。
     * 为什么不是 64：概览首屏这类行有 5~6 条，每行多 4dp 就是 20~24dp 的白滚动量
     * （≈一整个正文段落），密度目标就白做了一半。
     *
     * 横向最坏负载（长中文源名 + 绝对日期 + 「无结果」三字徽标）的宽度核算见
     * [RowBudget] 的注释与 `SourceQuoteList`——结论是固定段最坏 212dp ≤ 内容宽 302.86dp，
     * 源名收敛到 6 个 14sp 汉字（84dp），Compose 侧再留 maxLines=1 + Ellipsis 兜底。
     */
    val RowCompact = RowBudget.RowCompactDp.dp

    /** 结果头卡缩略图边长：两行 16sp 标题（2×22dp）+ 一行 16sp 现价（20dp）→ 64dp */
    val ThumbHeader = RowBudget.headerThumbDp(2, 22, 20).dp

    /** 列表行缩略图（收藏行）：RowCompact 60 − 上下各 10dp 呼吸 → 40dp */
    val ThumbRow = 40.dp

    /**
     * 概览走势条高度：详情大曲线在盯价页是 200dp，概览只回答"最近是在涨还是在跌"。
     * 88dp = 视口逻辑高（788.57dp）的 11%，仍高于 Canvas 上下留白（2×12dp）+ 可辨形状所需。
     */
    val CurveCompact = 88.dp

    /** 设置页组间留白：比 section 内行距（[SpacingM]12dp）高一档，也比页边距（[SpacingL]16dp）大，分组靠留白认 */
    val GroupGap = 28.dp

    /** 行内图标（引导卡标题行的图标与 chevron） */
    val IconInline = 20.dp

    /** 步骤进度点：当前步与其余步（替换原先散在 OnboardingFlow 里的 8dp/6dp 字面量） */
    val DotActive = 8.dp

    /** 步骤进度点（非当前步） */
    val DotIdle = 6.dp

    /** 多行凭证输入框：设置页与新手引导同一档，不再一处 120dp 一处 110dp */
    val TextAreaTall = 120.dp
}
