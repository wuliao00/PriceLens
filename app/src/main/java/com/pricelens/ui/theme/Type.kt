package com.pricelens.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * §3.2 字体层级（清晰原则）：字号梯度 11/12/14/16/20/28，
 * 行高约为字号的 1.25~1.43 倍，保证阅读留白。
 */
val PriceLensTypography = Typography(
    displayLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 16.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, lineHeight = 14.sp)
)

/**
 * 价格数值专用样式：大号金额强调（展示级字重）。
 * fontFeatureSettings("tnum") 启用等宽数字，数字滚动/跳动时不抖动（§2.3）。
 */
object PriceType {
    val PriceHero = TextStyle(
        fontSize = 32.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 40.sp,
        fontFeatureSettings = "tnum"
    )

    /** 列表/卡片内的主价格（次级强调，等宽数字） */
    val PriceLarge = TextStyle(
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 28.sp,
        fontFeatureSettings = "tnum"
    )
}
