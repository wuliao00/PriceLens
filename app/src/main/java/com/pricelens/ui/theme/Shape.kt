package com.pricelens.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Shape

/**
 * §3.4 圆角形状令牌（深度/极简原则）：与 Dims.CardCorner/ButtonCorner/ChipCorner 对齐，
 * 全库统一走 MaterialTheme.shapes，避免游离圆角值。
 *
 * 映射约定（2026-11 大改造：卡片圆角 16→20，更柔和的"悬浮卡片"感）：
 * - extraLarge / large → 卡片（Dims.CardCorner = 20.dp）
 * - medium            → 按钮 / 紧凑行 tile（Dims.ButtonCorner = 12.dp）
 * - small             → 标签/胶囊（Dims.ChipCorner = 8.dp）
 */
val PriceLensShapes = Shapes(
    extraLarge = RoundedCornerShape(Dims.CardCorner),
    large = RoundedCornerShape(Dims.CardCorner),
    medium = RoundedCornerShape(Dims.ButtonCorner),
    small = RoundedCornerShape(Dims.ChipCorner)
)

/**
 * 全圆角胶囊（大改造新增）：搜索框、语义徽标、分段选择器、状态芯片共用。
 * 50% 圆角在任何高度下都是正胶囊，不用按控件高度各算一个圆角值。
 */
val PillShape: Shape = RoundedCornerShape(50)
