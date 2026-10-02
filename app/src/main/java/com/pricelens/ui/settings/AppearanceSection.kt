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
import com.pricelens.ui.theme.Dims

/**
 * 设置页 · 外观区块（阶段4：SettingsScreen 按 权限/外观/数据/关于 拆分）。
 * 本文件同时承载设置页的通用行组件（SettingsRow / SettingSwitchRow / PermissionRow 由
 * PermissionSection.kt 承载，行高与留白全走 Dims token）。
 *
 * 2026-10-02：本组只有一个开关，组标题「外观」（[SettingsBand]）已经说明白，
 * 所以撤掉原先重复的 section 小标题——"外观 / 外观"连着读两遍没有信息量。
 */
@Composable
fun AppearanceSection(settings: SettingsRepository) {
    val dynamicTheme by settings.dynamicColor.collectAsStateWithLifecycle()
    SettingSwitchRow(
        title = stringResource(R.string.settings_dynamic_color_title),
        desc = stringResource(R.string.settings_dynamic_color_desc),
        checked = dynamicTheme,
        onCheckedChange = { settings.setDynamicColor(it) }
    )
}

/**
 * 通用设置行：标题 + 描述 + 右侧尾随控件（开关/按钮）。
 *
 * 行高（2026-10-02 密度收口）：文本两行 20+16=36dp，但开关/按钮本身 48dp 是触控下限，
 * 于是行高由控件决定 = 48 + 上下各 [Dims.SpacingS]8 = **64dp**，正好落在
 * [Dims.RowCompact] 的同一档（60）附近——设置页与列表页读起来是同一个节拍。
 * 改动前是上下各 12dp（72dp/行），本页十来个行白占了一屏多的滚动量。
 */
@Composable
fun SettingsRow(title: String, desc: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Dims.SpacingS)
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
