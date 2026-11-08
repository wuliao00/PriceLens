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
 * 全app统一的**紧凑列表行**基元（2026-10-02 版式统一；2026-11 大改造换底）。
 *
 * 概览的「各源报价」、我的页的收藏/盯价目标/入口行、设置页的开关行共用同一件东西：
 * 固定高 [Dims.RowCompact]、圆角 tile 底、左右 [Dims.SpacingS] 内边距、段间距 [Dims.SpacingS]。
 * 行高固定是刻意的（§2.5）：滚动时列表不会因某一行文案变长而整体抖动，
 * 而且"一屏能放几件事"变成一个可算的数——788.57dp 视口高 ÷ 60dp ≈ 13 行。
 *
 * 大改造：底色从"surfaceVariant 35% 半透明"换成实色 `surfaceContainer`，
 * 圆角从 small(8) 升到 medium(12)——暖纸/冷墨阶梯上的一块干净 tile，
 * 不再靠透明度叠色（半透明在暗色下是一块块发灰的板子）。行高/内边距/段间距不变，
 * RowBudget 的宽度账继续成立。
 */

/** 紧凑行：横向段位由调用方用 RowScope 排，权重段自己决定谁吸收剩余宽度 */
@Composable
fun CompactTileRow(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    val surface = Modifier
        .fillMaxWidth()
        .height(Dims.RowCompact)
        .clip(MaterialTheme.shapes.medium)
        .background(MaterialTheme.colorScheme.surfaceContainer)
        .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))
        .padding(horizontal = Dims.SpacingS)
    Row(
        modifier = surface,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dims.SpacingS),
        content = content
    )
}
