package com.pricelens.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pricelens.R
import com.pricelens.data.repository.SettingsRepository
import com.pricelens.ui.components.SectionHeader
import com.pricelens.ui.theme.Dims

/**
 * 设置页 · 外观区块（阶段4：SettingsScreen 按 权限/外观/数据/关于 拆分）。
 * 本文件同时承载设置页的通用行组件（SettingsRow / SettingSwitchRow / PermissionRow）。
 */
@Composable
fun AppearanceSection(settings: SettingsRepository) {
    val dynamicTheme by settings.dynamicColor.collectAsStateWithLifecycle()
    SectionHeader(stringResource(R.string.settings_section_appearance))
    SettingSwitchRow(
        title = stringResource(R.string.settings_dynamic_color_title),
        desc = stringResource(R.string.settings_dynamic_color_desc),
        checked = dynamicTheme,
        onCheckedChange = { settings.setDynamicColor(it) }
    )
}

/** 通用设置行：标题 + 描述 + 右侧尾随控件（开关/按钮） */
@Composable
fun SettingsRow(title: String, desc: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Dims.SpacingM)
    ) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        trailing()
    }
}

/** 开关行（动态取色等） */
@Composable
fun SettingSwitchRow(title: String, desc: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    SettingsRow(title = title, desc = desc) {
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
