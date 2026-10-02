package com.pricelens.ui.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.pricelens.ui.theme.Dims

/**
 * 全app统一的**紧凑列表行**基元（2026-10-02 版式统一）。
 *
 * 概览的「各源报价」、我的页的收藏/盯价目标/入口行、设置页的开关行共用同一件东西：
 * 固定高 [Dims.RowCompact]、小圆角 tile 底、左右 [Dims.SpacingS] 内边距、段间距 [Dims.SpacingS]。
 * 行高固定是刻意的（§2.5）：滚动时列表不会因某一行文案变长而整体抖动，
 * 而且"一屏能放几件事"变成一个可算的数——788.57dp 视口高 ÷ 60dp ≈ 13 行。
 *
 * 底色取 `surfaceVariant` 的 35% 而不是满不透明：满不透明在暗色下是一块块灰板子，
 * 0.35 既能认出"这是一行可点的东西"，又不把 onSurface / onSurfaceVariant 的对比度拖下水
 * （M3 的配对就是 onSurfaceVariant ↔ surfaceVariant，见 Theme.kt 的 DarkColors）。
 */
const val TileAlpha = 0.35f

/** 紧凑行：横向段位由调用方用 RowScope 排，权重段自己决定谁吸收剩余宽度 */
@Composable
fun CompactTileRow(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    val surface = Modifier
        .fillMaxWidth()
        .height(Dims.RowCompact)
        .clip(MaterialTheme.shapes.small)
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = TileAlpha))
        .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))
        .padding(horizontal = Dims.SpacingS)
    Row(
        modifier = surface,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dims.SpacingS),
        content = content
    )
}
