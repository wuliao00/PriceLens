package com.pricelens.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pricelens.R
import com.pricelens.ui.components.SectionHeader
import com.pricelens.ui.theme.Dims

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
 * 设置页 · 数据源凭证（均可选）：
 *  - 星罗好货 apikey：历史低价参考 + 盯价查价兜底
 *  - 慢慢买 Cookie：拉取完整历史价格曲线（仅存本机）
 */
@Composable
fun CredentialsSection(settings: com.pricelens.data.repository.SettingsRepository) {
    SectionHeader(stringResource(R.string.settings_section_credentials))

    var apiKey by remember { mutableStateOf(settings.linkstarsApiKey) }
    var cookie by remember { mutableStateOf(settings.manmanbuyCookie) }
    var saved by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = apiKey,
            onValueChange = {
                apiKey = it
                saved = false
            },
            label = { Text(stringResource(R.string.settings_linkstars_key)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(Dims.SpacingS))
        OutlinedTextField(
            value = cookie,
            onValueChange = {
                cookie = it
                saved = false
            },
            label = { Text(stringResource(R.string.settings_mmb_cookie)) },
            modifier = Modifier.fillMaxWidth().height(120.dp)
        )
        Spacer(Modifier.height(Dims.SpacingS))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = {
                    settings.setLinkstarsApiKey(apiKey)
                    settings.setManmanbuyCookie(cookie)
                    saved = true
                },
                shape = MaterialTheme.shapes.small
            ) {
                Text(stringResource(R.string.settings_credentials_save))
            }
        }
        if (saved) {
            Text(
                stringResource(R.string.settings_credentials_saved),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
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
