package com.pricelens.ui.settings

import android.content.Intent
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pricelens.R
import com.pricelens.ui.components.SectionHeader
import com.pricelens.ui.theme.Dims
import com.pricelens.update.UpdateRepository
import com.pricelens.update.UpdateState

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

    // 从内置登录页返回时自动回填抓取到的 Cookie（仅当存储值确实被外部更新）
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var lastStored by remember { mutableStateOf(settings.manmanbuyCookie) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val fetched = settings.manmanbuyCookie
                if (fetched != lastStored && fetched.isNotBlank()) {
                    cookie = fetched
                    lastStored = fetched
                    saved = true
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                stringResource(R.string.settings_mmb_login_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = {
                context.startActivity(Intent(context, ManmanbuyLoginActivity::class.java))
            }) {
                Text(stringResource(R.string.settings_mmb_login_btn))
            }
        }
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
 * 设置页 · 关于区块：版本 / 隐私声明 / 免费声明弹窗 / 检查更新 / 重看新手引导。
 */
@Composable
fun AboutSection(versionName: String, versionCode: Int, updateRepository: UpdateRepository, onReplayOnboarding: () -> Unit) {
    SectionHeader(stringResource(R.string.settings_section_about))

    var showDisclaimer by remember { mutableStateOf(false) }
    val updateState by updateRepository.state.collectAsStateWithLifecycle()

    SettingsRow(
        title = stringResource(R.string.settings_about_version, versionName),
        desc = stringResource(R.string.settings_about_privacy)
    ) {
        TextButton(onClick = { showDisclaimer = true }) {
            Text(stringResource(R.string.settings_about_disclaimer))
        }
    }

    // 检查更新 / 当前 v…：复用 SettingsRow 风格；判定与下载都交给 UpdateRepository
    SettingsRow(
        title = stringResource(R.string.update_check_action),
        desc = updateRowDesc(updateState, versionName)
    ) {
        TextButton(
            enabled = updateState !is UpdateState.Checking,
            onClick = { updateRepository.checkManually(versionCode) }
        ) {
            Text(
                stringResource(
                    if (updateState is UpdateState.Checking) {
                        R.string.update_checking
                    } else {
                        R.string.update_check_action
                    }
                )
            )
        }
    }

    SettingsRow(
        title = stringResource(R.string.settings_replay_onboarding),
        desc = stringResource(R.string.settings_replay_onboarding_desc)
    ) {
        TextButton(onClick = onReplayOnboarding) {
            Text(stringResource(R.string.settings_replay_action))
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

/** "检查更新"行的副标题：把闸门状态如实翻成人话（拿不到清单也不谎报"已最新"） */
@Composable
private fun updateRowDesc(state: UpdateState, versionName: String): String = when (state) {
    is UpdateState.UpToDate -> stringResource(R.string.update_up_to_date, state.offer.versionName)
    is UpdateState.Forced -> stringResource(R.string.update_available_hint, state.offer.versionName)
    is UpdateState.StrongHint -> stringResource(R.string.update_available_hint, state.offer.versionName)
    is UpdateState.Optional -> stringResource(R.string.update_available_hint, state.offer.versionName)
    is UpdateState.Unavailable -> stringResource(R.string.update_check_unavailable)
    else -> stringResource(R.string.update_current_version, versionName)
}
