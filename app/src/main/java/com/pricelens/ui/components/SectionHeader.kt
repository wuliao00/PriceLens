package com.pricelens.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.pricelens.ui.theme.Dims

/**
 * 统一区块标题（清晰原则：层级走 Typography token，留白走 Dims）。
 * 替换 ProfileScreen.SectionTitle / SettingsScreen.SettingsSection /
 * CommunityScreen 内联标题等重复实现。
 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Dims.SpacingXL, bottom = Dims.SpacingS)
    )
}
