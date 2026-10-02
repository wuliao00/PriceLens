package com.pricelens.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.pricelens.R
import com.pricelens.data.repository.SettingsRepository
import java.util.Locale

/**
 * 设置页 · 通知区块（文档 UX「富通知」的选项部分）：
 *  - 仅 WiFi 时提醒；
 *  - 免打扰时段（[SettingsRepository.quietStartMinute, quietEndMinute) 半开区间，跨零点自动处理）。
 *
 * 闸门语义在 worker/NotificationGate（纯函数 + 单测）；被抑制只是"这一轮不发"，
 * 下一轮（30 分钟后）重新评估，不丢提醒。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotifySection(settings: SettingsRepository) {
    // 组标题「通知与提醒」（SettingsBand）已经说明这一组是什么，这里不再重复一遍小标题
    // 轻量刷新键：开关/时间改动后让 remember 重读（设置本身是同步的 prefs 读写）
    var tick by remember { mutableStateOf(0) }
    val wifiOnly = remember(tick) { settings.notifyWifiOnly }
    val quietEnabled = remember(tick) { settings.quietHoursEnabled }
    val startMinute = remember(tick) { settings.quietStartMinute }
    val endMinute = remember(tick) { settings.quietEndMinute }

    // 正在编辑哪一端：true=开始时间，false=结束时间，null=无
    var editing by remember { mutableStateOf<Boolean?>(null) }

    SettingsRow(
        title = stringResource(R.string.settings_notify_wifi_title),
        desc = stringResource(R.string.settings_notify_wifi_desc)
    ) {
        Switch(
            checked = wifiOnly,
            onCheckedChange = {
                settings.setNotifyWifiOnly(it)
                tick++
            }
        )
    }

    SettingsRow(
        title = stringResource(R.string.settings_notify_quiet_title),
        desc = if (quietEnabled) {
            stringResource(R.string.settings_notify_quiet_desc_on, fmtMinute(startMinute), fmtMinute(endMinute))
        } else {
            stringResource(R.string.settings_notify_quiet_desc_off)
        }
    ) {
        Switch(
            checked = quietEnabled,
            onCheckedChange = {
                settings.setQuietHoursEnabled(it)
                tick++
            }
        )
    }

    if (quietEnabled) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { editing = true }) {
                Text(stringResource(R.string.settings_notify_quiet_start) + " " + fmtMinute(startMinute))
            }
            TextButton(onClick = { editing = false }) {
                Text(stringResource(R.string.settings_notify_quiet_end) + " " + fmtMinute(endMinute))
            }
        }
    }

    editing?.let { isStart ->
        val base = if (isStart) startMinute else endMinute
        val pickerState = rememberTimePickerState(initialHour = base / 60, initialMinute = base % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { editing = null },
            title = {
                Text(
                    stringResource(
                        if (isStart) R.string.settings_notify_quiet_start else R.string.settings_notify_quiet_end
                    )
                )
            },
            text = { TimePicker(state = pickerState) },
            confirmButton = {
                TextButton(onClick = {
                    val minute = pickerState.hour * 60 + pickerState.minute
                    if (isStart) settings.setQuietStartMinute(minute) else settings.setQuietEndMinute(minute)
                    tick++
                    editing = null
                }) {
                    Text(stringResource(R.string.settings_notify_quiet_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

private fun fmtMinute(minute: Int): String = String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60)
