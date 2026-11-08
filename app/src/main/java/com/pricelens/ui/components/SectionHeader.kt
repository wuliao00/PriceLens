package com.pricelens.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.pricelens.ui.theme.Dims
import com.pricelens.ui.theme.PillShape

/**
 * 统一区块标题（清晰原则：层级走 Typography token，留白走 Dims）。
 * 替换 ProfileScreen.SectionTitle / SettingsScreen.SettingsSection /
 * CommunityScreen 内联标题等重复实现。
 *
 * 2026-11 大改造：标题前加一枚金色"刻度条"（4×14dp 胶囊），
 * 区块归属一眼可辨——设置页八个 section 平铺时，滚动中也能认出组界。
 * 文字仍走 labelSmall（12sp SemiBold，规格内），颜色从纯 primary 改为 onSurface，
 * 让金色只出现在刻度条上：强调色是标点，不是正文。
 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Dims.SpacingXL, bottom = Dims.SpacingS)
    ) {
        Box(
            Modifier
                .width(Dims.SpacingXS)
                .height(Dims.SpacingL - Dims.SpacingXS)
                .clip(PillShape)
                .background(MaterialTheme.colorScheme.primary)
        )
        Spacer(Modifier.width(Dims.SpacingS))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
