package com.pricelens.ui.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.pricelens.R
import com.pricelens.ui.components.SectionHeader

/**
 * 设置页 · 数据区块：缓存占用查看 / 刷新 / 清理。
 */
@Composable
fun DataSection(cacheStats: String, onRefresh: () -> Unit, onClear: () -> Unit) {
    SectionHeader(stringResource(R.string.settings_section_data))
    SettingsRow(
        title = stringResource(R.string.settings_cache_title),
        desc = cacheStats
    ) {
        TextButton(onClick = onRefresh) {
            Text(stringResource(R.string.settings_cache_refresh))
        }
        Button(onClick = onClear, shape = MaterialTheme.shapes.small) {
            Text(stringResource(R.string.settings_cache_clear))
        }
    }
}

/**
 * 设置页 · 关于区块：版本 / 隐私声明 / 免费声明弹窗。
 */
@Composable
fun AboutSection(versionName: String) {
    SectionHeader(stringResource(R.string.settings_section_about))

    var showDisclaimer by remember { mutableStateOf(false) }

    SettingsRow(
        title = stringResource(R.string.settings_about_version, versionName),
        desc = stringResource(R.string.settings_about_privacy)
    ) {
        TextButton(onClick = { showDisclaimer = true }) {
            Text(stringResource(R.string.settings_about_disclaimer))
        }
    }

    if (showDisclaimer) {
        AlertDialog(
            onDismissRequest = { showDisclaimer = false },
            title = { Text(stringResource(R.string.disclaimer_title)) },
            text = { Text(stringResource(R.string.disclaimer_body)) },
            confirmButton = {
                TextButton(onClick = { showDisclaimer = false }) {
                    Text(stringResource(R.string.disclaimer_ok))
                }
            }
        )
    }
}
