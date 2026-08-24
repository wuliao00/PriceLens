package com.pricelens.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** §3.1 动态取色（Android 12+ 壁纸取色），低版本回退品牌蓝 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF1B6EF3),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E6FF),
    secondary = Color(0xFF565E71),
    surface = Color(0xFFFCFCFF),
    surfaceVariant = Color(0xFFE1E2EC),
    background = Color(0xFFFCFCFF),
    error = Color(0xFFBA1A1A)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8C8FF),
    onPrimary = Color(0xFF00306B),
    primaryContainer = Color(0xFF00468E),
    secondary = Color(0xFFBEC6DC),
    surface = Color(0xFF1A1B21),
    surfaceVariant = Color(0xFF44464F),
    background = Color(0xFF1A1B21),
    error = Color(0xFFFFB4AB)
)

/** §3.2 字体层级 */
val PriceLensTypography = Typography(
    displayLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    bodyLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 16.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, lineHeight = 14.sp)
)

/** 语义色：历史低价 / 先涨后降 / 中性标签（§3.5） */
object SemanticColors {
    val LowPrice = Color(0xFF2E7D32)
    val Suspicious = Color(0xFFC62828)
    val Neutral = Color(0xFF757575)
}

@Composable
fun PriceLensTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = PriceLensTypography,
        content = content
    )
}

/** §3.3 间距与圆角基准 */
object Dims {
    val SpacingXS = 4.dp; val SpacingS = 8.dp; val SpacingM = 12.dp
    val SpacingL = 16.dp; val SpacingXL = 20.dp; val SpacingXXL = 24.dp
    val SpacingXXXL = 32.dp
    val CardCorner = 16.dp; val ButtonCorner = 12.dp
    val ChipCorner = 8.dp
    val ListItemHeight = 100.dp
}
