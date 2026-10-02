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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pricelens.R
import com.pricelens.data.remote.CookieProbe
import com.pricelens.data.remote.ManmanbuyApi
import com.pricelens.ui.components.SectionHeader
import com.pricelens.ui.theme.Dims
import com.pricelens.update.UpdateRepository
import com.pricelens.update.UpdateState
import com.pricelens.util.SecretMask

/**
 * 设置页 · 数据区块：缓存占用查看 / 刷新 / 清理。
 */
@Composable
fun DataSection(
    settings: com.pricelens.data.repository.SettingsRepository,
    cacheStats: String,
    onRefresh: () -> Unit,
    onClear: () -> Unit
) {
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
    // 剪贴板识别开关：读剪贴板是敏感能力，必须有开关与说明（文档 §4.2 的合规要求）
    SettingsRow(
        title = stringResource(R.string.settings_clipboard_title),
        desc = stringResource(R.string.settings_clipboard_desc)
    ) {
        Switch(
            checked = settings.clipboardDetectEnabled,
            onCheckedChange = { settings.setClipboardDetectEnabled(it) }
        )
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
    // 默认掩码显示（文档 §2.3）：截图发出去 = 凭证泄露，2026-10-02 的真事。
    // 「显示」后才可编辑/复制；掩码态下只读，避免"在掩码上编辑"这种半吊子状态。
    var revealApiKey by remember { mutableStateOf(false) }
    var revealCookie by remember { mutableStateOf(false) }
    // 「检测 Cookie」用输入框当前值（不是已保存值）打探针，故状态与 ViewModel 都留在本区块
    val probeVm: CookieProbeViewModel = hiltViewModel()
    val probeUi by probeVm.ui.collectAsStateWithLifecycle()
    // collectAsStateWithLifecycle 是委托属性，Kotlin 不能对它智能转换，故取一份局部快照
    val probeState: CookieProbeViewModel.Ui = probeUi

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
            value = if (revealApiKey) apiKey else SecretMask.mask(apiKey),
            onValueChange = {
                if (revealApiKey) {
                    apiKey = it
                    saved = false
                }
            },
            readOnly = !revealApiKey,
            label = { Text(stringResource(R.string.settings_linkstars_key)) },
            singleLine = true,
            trailingIcon = {
                TextButton(onClick = { revealApiKey = !revealApiKey }) {
                    Text(
                        stringResource(
                            if (revealApiKey) R.string.settings_secret_hide else R.string.settings_secret_show
                        )
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        // 星罗好货的 apikey 只能从「提报系统」申请（手机号+验证码 → **人工审核** → 个人中心申请 key）。
        // 这里给一条直达入口，省得用户被 openapi.linkstars.com（那只是 API 主机）误导。
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://xl.linkstars.com/admin/#/login"))
                )
            }) {
                Text(stringResource(R.string.settings_linkstars_apply))
            }
        }
        Text(
            stringResource(R.string.settings_linkstars_apply_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Dims.SpacingS))
        OutlinedTextField(
            value = if (revealCookie) cookie else SecretMask.mask(cookie),
            onValueChange = {
                if (revealCookie) {
                    cookie = it
                    saved = false
                }
            },
            readOnly = !revealCookie,
            label = { Text(stringResource(R.string.settings_mmb_cookie)) },
            trailingIcon = {
                TextButton(onClick = { revealCookie = !revealCookie }) {
                    Text(
                        stringResource(
                            if (revealCookie) R.string.settings_secret_hide else R.string.settings_secret_show
                        )
                    )
                }
            },
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
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dims.SpacingS, Alignment.End)
        ) {
            // 「检测 Cookie」：结论只有四种，且"没够着"绝不写成"没有"
            Button(
                enabled = cookie.isNotBlank() && probeUi !is CookieProbeViewModel.Ui.Running,
                onClick = { probeVm.probe(cookie) },
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    stringResource(
                        if (probeUi is CookieProbeViewModel.Ui.Running) {
                            R.string.settings_mmb_probe_running
                        } else {
                            R.string.settings_mmb_probe_btn
                        }
                    )
                )
            }
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
        Text(
            probeCopy(probeUi),
            style = MaterialTheme.typography.bodySmall,
            color = if (probeState is CookieProbeViewModel.Ui.Result &&
                probeState.probe is CookieProbe.Ok
            ) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        if (saved) {
            Text(
                stringResource(R.string.settings_credentials_saved),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // 结论指向"账号没授权京东 / 已授权可取数"时，给一个能走到位的入口：
        // 打开应用内网页，由站点自己完成授权检查与取数。**不代过验证码、不代点授权**，
        // 卡住时页面会原样呈现，状态栏只负责说清"现在在哪一步、下一步点哪"。
        val probeResult = (probeUi as? CookieProbeViewModel.Ui.Result)?.probe
        if (cookie.isNotBlank() && (probeResult is CookieProbe.JdNotAuthorized || probeResult is CookieProbe.Ready)) {
            Spacer(Modifier.height(Dims.SpacingS))
            Button(
                onClick = {
                    context.startActivity(
                        Intent(context, MmbHistoryActivity::class.java).apply {
                            putExtra(MmbHistoryActivity.EXTRA_PRODUCT_URL, ManmanbuyApi.PROBE_PRODUCT_URL)
                        }
                    )
                },
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    stringResource(
                        if (probeResult is CookieProbe.JdNotAuthorized) {
                            R.string.settings_mmb_fetch_open_auth
                        } else {
                            R.string.settings_mmb_fetch_open
                        }
                    )
                )
            }
            Text(
                stringResource(R.string.settings_mmb_fetch_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 结论 → 文案资源 id（纯映射，无 Compose 依赖，可在 JVM 单测里直接断言） */
internal fun mmbProbeStringRes(probe: CookieProbe): Int = when (probe) {
    is CookieProbe.Ok -> R.string.settings_mmb_probe_ok
    is CookieProbe.LoggedOut -> R.string.settings_mmb_probe_logged_out
    is CookieProbe.JdNotAuthorized -> R.string.settings_mmb_probe_need_jd_auth
    is CookieProbe.Ready -> R.string.settings_mmb_probe_ready
    is CookieProbe.Captcha -> R.string.settings_mmb_probe_captcha
    is CookieProbe.NoData -> R.string.settings_mmb_probe_no_data
    is CookieProbe.Unreachable -> R.string.settings_mmb_probe_unreachable
}

/** 结论 → 填充参数：Ok 填价格点数（%1$d），其余填技术原因（%1$s） */
internal fun mmbProbeArg(probe: CookieProbe): Any = when (probe) {
    is CookieProbe.Ok -> probe.points.size
    is CookieProbe.LoggedOut -> probe.reason
    is CookieProbe.JdNotAuthorized -> probe.reason
    is CookieProbe.Ready -> probe.reason
    is CookieProbe.Captcha -> probe.reason
    is CookieProbe.NoData -> probe.reason
    is CookieProbe.Unreachable -> probe.reason
}

/** 检测 Cookie 的一行结果文案：Idle 显示引导，Running 显示"检测中…" */
@Composable
private fun probeCopy(ui: CookieProbeViewModel.Ui): String = when (ui) {
    is CookieProbeViewModel.Ui.Idle -> stringResource(R.string.settings_mmb_probe_idle)
    is CookieProbeViewModel.Ui.Running -> stringResource(R.string.settings_mmb_probe_running)
    is CookieProbeViewModel.Ui.Result -> stringResource(mmbProbeStringRes(ui.probe), mmbProbeArg(ui.probe))
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
