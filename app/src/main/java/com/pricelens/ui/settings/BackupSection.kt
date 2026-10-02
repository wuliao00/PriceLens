package com.pricelens.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pricelens.R
import com.pricelens.data.backup.BackupFormat
import com.pricelens.data.backup.WebDavProbe
import com.pricelens.data.repository.SettingsRepository
import com.pricelens.ui.components.SectionHeader
import com.pricelens.ui.theme.Dims
import com.pricelens.util.SecretMask

/**
 * 设置页 · 备份与恢复区块（§五）：
 *  - WebDAV 主路径：地址/账号/密码（密码掩码）→ 测试连接（成功/认证失败/连不上/其他错误码四档如实）/
 *    立即备份 / 恢复（列出远端文件选一份）；
 *  - SAF 兜底：导出到本机 / 从本机导入（同一份 payload 格式，无需 NAS 也能用）；
 *  - 恢复成功给出「恢复 N 个收藏 / M 个盯价目标」；冲突策略在文案里写明（新者胜）。
 *
 * 凭证只经 [SettingsRepository]（SecretStore 密文）存取；本区块不写任何明文持久化。
 */
@Composable
fun BackupSection(settings: SettingsRepository) {
    val vm: BackupViewModel = hiltViewModel()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val remoteFiles by vm.remoteFiles.collectAsStateWithLifecycle()

    var url by remember { mutableStateOf(settings.webdavUrl) }
    var user by remember { mutableStateOf(settings.webdavUser) }
    var password by remember { mutableStateOf(settings.webdavPassword) }
    // 掩码显示惯例与 CredentialsSection 一致：默认只露首尾，点「显示」才能编辑
    var revealPassword by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportSaf(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importSaf(uri)
    }

    SectionHeader(stringResource(R.string.settings_section_backup))
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.settings_backup_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Dims.SpacingS))
        OutlinedTextField(
            value = url,
            onValueChange = {
                url = it
                saved = false
            },
            label = { Text(stringResource(R.string.settings_backup_url)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = user,
            onValueChange = {
                user = it
                saved = false
            },
            label = { Text(stringResource(R.string.settings_backup_user)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = if (revealPassword) password else SecretMask.mask(password),
            onValueChange = {
                if (revealPassword) {
                    password = it
                    saved = false
                }
            },
            readOnly = !revealPassword,
            label = { Text(stringResource(R.string.settings_backup_password)) },
            singleLine = true,
            trailingIcon = {
                TextButton(onClick = { revealPassword = !revealPassword }) {
                    Text(
                        stringResource(
                            if (revealPassword) R.string.settings_secret_hide else R.string.settings_secret_show
                        )
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dims.SpacingS, Alignment.End)) {
            // 测活用输入框当前值（未保存先试），结论四档如实分开
            TextButton(
                enabled = !busy,
                onClick = { vm.testConnection(url, user, password) }
            ) {
                Text(stringResource(R.string.settings_backup_test))
            }
            Button(
                enabled = !busy,
                onClick = {
                    vm.save(url, user, password)
                    saved = true
                },
                shape = MaterialTheme.shapes.small
            ) {
                Text(stringResource(R.string.settings_backup_save))
            }
        }

        // 结论 / 进行中 / 最近一次操作反馈（同一行位置，状态互斥显示）
        val status: Pair<String, Color> = backupStatus(message, busy)
        Text(
            status.first,
            style = MaterialTheme.typography.bodySmall,
            color = status.second
        )
        if (saved) {
            Text(
                stringResource(R.string.settings_backup_saved),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dims.SpacingS, Alignment.End)) {
            Button(
                enabled = !busy,
                onClick = { vm.backupNow() },
                shape = MaterialTheme.shapes.small
            ) {
                Text(stringResource(R.string.settings_backup_now))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dims.SpacingS, Alignment.End)) {
            TextButton(
                enabled = !busy,
                onClick = { vm.openRestorePicker() }
            ) {
                Text(stringResource(R.string.settings_backup_restore))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dims.SpacingS, Alignment.End)) {
            TextButton(
                enabled = !busy,
                onClick = { importLauncher.launch(arrayOf("*/*")) }
            ) {
                Text(stringResource(R.string.settings_backup_import))
            }
            Button(
                enabled = !busy,
                onClick = { exportLauncher.launch(BackupFormat.safExportFileName(System.currentTimeMillis())) },
                shape = MaterialTheme.shapes.small
            ) {
                Text(stringResource(R.string.settings_backup_export))
            }
        }
    }

    // 「选一份远端备份」对话框：列表非空时逐个可选；空列表如实说"远端还没有备份"
    val files = remoteFiles
    if (files != null) {
        AlertDialog(
            onDismissRequest = { vm.dismissPicker() },
            title = { Text(stringResource(R.string.settings_backup_pick_title)) },
            text = {
                if (files.isEmpty()) {
                    Text(stringResource(R.string.settings_backup_pick_empty))
                } else {
                    Column {
                        files.forEach { item ->
                            TextButton(onClick = { vm.restore(item.fileName) }) {
                                Text(item.fileName)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.dismissPicker() }) {
                    Text(stringResource(R.string.settings_backup_cancel))
                }
            }
        )
    }
}

/** 状态区文案 + 色调：进行中/无结论/各档结论一处收口（Composable 侧零判断） */
@Composable
private fun backupStatus(message: BackupViewModel.Message?, busy: Boolean): Pair<String, Color> = when {
    busy -> stringResource(R.string.settings_backup_running) to MaterialTheme.colorScheme.onSurfaceVariant
    message is BackupViewModel.Message.Tested -> testProbeCopy(message.probe)
    message is BackupViewModel.Message.BackupOk ->
        stringResource(R.string.settings_backup_ok, message.name) to MaterialTheme.colorScheme.primary
    message is BackupViewModel.Message.RestoreOk ->
        stringResource(
            R.string.settings_backup_restore_ok,
            message.favorites,
            message.targets
        ) to MaterialTheme.colorScheme.primary
    message is BackupViewModel.Message.Exported ->
        stringResource(
            R.string.settings_backup_export_ok,
            message.favorites,
            message.targets
        ) to MaterialTheme.colorScheme.primary
    message is BackupViewModel.Message.Imported ->
        stringResource(
            R.string.settings_backup_import_ok,
            message.favorites,
            message.targets
        ) to MaterialTheme.colorScheme.primary
    message is BackupViewModel.Message.Failed ->
        stringResource(R.string.settings_backup_failed, message.reason) to MaterialTheme.colorScheme.error
    else -> "" to MaterialTheme.colorScheme.onSurfaceVariant
}

/** 测活结论 → 文案+色调：成功 / 认证失败 / 连不上 / 其他错误码，四档都如实，不合并成"失败" */
@Composable
private fun testProbeCopy(probe: WebDavProbe): Pair<String, Color> = when (probe) {
    is WebDavProbe.Ok -> stringResource(R.string.settings_backup_test_ok) to MaterialTheme.colorScheme.primary
    is WebDavProbe.AuthFailed ->
        stringResource(R.string.settings_backup_test_auth) to MaterialTheme.colorScheme.onSurfaceVariant
    is WebDavProbe.Unreachable ->
        stringResource(R.string.settings_backup_test_unreachable) to MaterialTheme.colorScheme.onSurfaceVariant
    is WebDavProbe.HttpError ->
        stringResource(R.string.settings_backup_test_http, probe.code) to MaterialTheme.colorScheme.onSurfaceVariant
}
