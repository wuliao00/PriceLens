package com.pricelens.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.pricelens.BuildConfig
import com.pricelens.R
import com.pricelens.util.CrashHandler
import com.pricelens.util.CrashLog
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页 · 诊断区块（文档 §十三的本地形态）：崩溃日志只写本机，由用户自己导出。
 *
 * 没有「发送匿名崩溃报告」开关 —— 因为根本没有上传通道；「导出」是用户把文件
 * 交给谁（或发给我排查）的自主决定，导出文本整体脱敏。调试包多一行「测试崩溃」，
 * 用于验证崩溃→落盘→导出这条链路。
 */
@Composable
fun DiagnosticsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var count by remember { mutableStateOf(CrashHandler.count(context)) }
    var exported by remember { mutableStateOf(false) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) { CrashHandler.exportTo(context, uri) }
                exported = result.isSuccess
            }
        }
    }

    SettingsSubtitle(stringResource(R.string.settings_section_diagnostics))
    SettingsRow(
        title = stringResource(R.string.settings_crash_title),
        desc = if (count > 0) {
            stringResource(R.string.settings_crash_desc, count)
        } else {
            stringResource(R.string.settings_crash_empty)
        }
    ) {
        TextButton(
            enabled = count > 0,
            onClick = {
                exported = false
                exporter.launch(CrashLog.exportFileName(System.currentTimeMillis(), ZoneId.systemDefault()))
            }
        ) {
            Text(stringResource(R.string.settings_crash_export))
        }
        TextButton(
            enabled = count > 0,
            onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { CrashHandler.clear(context) }
                    count = CrashHandler.count(context)
                    exported = false
                }
            }
        ) {
            Text(stringResource(R.string.settings_crash_clear))
        }
    }
    if (exported) {
        Text(
            stringResource(R.string.settings_crash_exported),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }

    // 验证崩溃链路用的按钮：只在调试包里存在（与文档 §13.1 的做法一致）
    if (BuildConfig.DEBUG) {
        SettingsRow(
            title = stringResource(R.string.settings_crash_test_title),
            desc = stringResource(R.string.settings_crash_test_desc)
        ) {
            TextButton(onClick = { throw RuntimeException("crash-log ping") }) {
                Text(stringResource(R.string.settings_crash_test_btn))
            }
        }
    }
}
