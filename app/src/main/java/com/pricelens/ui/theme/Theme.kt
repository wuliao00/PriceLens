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
}
