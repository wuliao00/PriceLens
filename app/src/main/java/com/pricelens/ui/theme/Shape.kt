package com.pricelens.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes

/**
 * §3.4 圆角形状令牌（深度/极简原则）：与 Dims.CardCorner/ButtonCorner/ChipCorner 对齐，
 * 全库统一走 MaterialTheme.shapes，避免游离圆角值。
 *
 * 映射约定：
 * - extraLarge / large → 卡片（Dims.CardCorner = 16.dp）
 * - medium            → 按钮（Dims.ButtonCorner = 12.dp）
 * - small             → 标签/胶囊（Dims.ChipCorner = 8.dp）
 */
val PriceLensShapes = Shapes(
    extraLarge = RoundedCornerShape(Dims.CardCorner),
    large = RoundedCornerShape(Dims.CardCorner),
    medium = RoundedCornerShape(Dims.ButtonCorner),
    small = RoundedCornerShape(Dims.ChipCorner)
)
