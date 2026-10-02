package com.pricelens.ui.settings

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.pricelens.R
import com.pricelens.accessibility.OverlayManager
import com.pricelens.ui.components.PriceBadge
import com.pricelens.ui.components.SectionHeader
import com.pricelens.ui.onboarding.isPriceLensAccessibilityEnabled
import com.pricelens.ui.onboarding.rememberPermissionStates
import com.pricelens.ui.theme.BadgeTone
import com.pricelens.ui.theme.Dims
import com.pricelens.util.ShizukuHelper
import com.pricelens.util.UrlOpener

/**
 * 设置页 · 权限区块：无障碍 / 悬浮窗 / 通知 / Shizuku 四态 + 后台保活设置入口。
 * 逻辑与阶段2完全一致，仅迁移文案与留白令牌；
 * v2.6.0 起权限读取与刷新时机改由 [rememberPermissionStates] 统一承载
 * （原 `isAccessibilityEnabled` 也上移为 [isPriceLensAccessibilityEnabled] 供引导共用，行为等价）。
 *
 * 2026-10 起末尾追加「后台保活设置」入口（[KeepAliveScreen]，文档 §12）：
 * 国产 ROM 上盯价被杀是权限四态之外的另一个静默故障源。
 */

@Composable
fun PermissionSection(onOpenKeepAlive: () -> Unit) {
    SectionHeader(stringResource(R.string.settings_section_permission))

    val context = LocalContext.current
    // 从系统设置返回时刷新各项状态：ON_RESUME + refreshKey 算法已抽到
    // [com.pricelens.ui.onboarding.rememberPermissionStates]，与新手引导共用同一份判定，行为不变
    val permissions = rememberPermissionStates()
    val accEnabled = permissions.accessibilityGranted
    val overlayEnabled = permissions.overlayGranted
    val notifEnabled = permissions.notificationsGranted
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permissions.refresh() }

    val enabledText = stringResource(R.string.perm_enabled)
    val goEnableText = stringResource(R.string.perm_go_enable)

    PermissionRow(
        title = stringResource(R.string.perm_acc_title),
        desc = stringResource(R.string.perm_acc_desc),
        granted = accEnabled,
        actionText = if (accEnabled) enabledText else goEnableText
    ) {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    PermissionRow(
        title = stringResource(R.string.perm_overlay_title),
        desc = stringResource(R.string.perm_overlay_desc),
        granted = overlayEnabled,
        actionText = if (overlayEnabled) enabledText else goEnableText
    ) {
        OverlayManager.requestPermission(context)
    }

    PermissionRow(
        title = stringResource(R.string.perm_notif_title),
        desc = stringResource(R.string.perm_notif_desc),
        granted = notifEnabled,
        actionText = if (notifEnabled) enabledText else goEnableText
    ) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            )
        }
    }

    // Shizuku 四态（响应式）：Binder 回调驱动状态流，服务后台启动/授权后自动刷新
    val shizukuInstalled = permissions.shizukuInstalled
    val shizukuAlive = permissions.shizukuAlive
    val shizukuGranted = permissions.shizukuReady
    val bothReady = shizukuGranted && accEnabled && overlayEnabled

    PermissionRow(
        title = stringResource(R.string.perm_shizuku_title),
        desc = when {
            !shizukuInstalled -> stringResource(R.string.perm_shizuku_desc_not_installed)
            !shizukuAlive -> stringResource(R.string.perm_shizuku_desc_not_alive)
            !shizukuGranted -> stringResource(R.string.perm_shizuku_desc_not_granted)
            bothReady -> stringResource(R.string.perm_shizuku_desc_ready)
            else -> stringResource(R.string.perm_shizuku_desc_idle)
        },
        granted = bothReady,
        actionText = when {
            !shizukuInstalled -> stringResource(R.string.perm_shizuku_install)
            !shizukuAlive -> stringResource(R.string.perm_shizuku_open)
            !shizukuGranted -> stringResource(R.string.perm_shizuku_grant)
            bothReady -> stringResource(R.string.perm_shizuku_done)
            else -> stringResource(R.string.perm_shizuku_oneclick)
        }
    ) {
        when {
            !shizukuInstalled -> UrlOpener.open(context, "https://github.com/RikkaApps/Shizuku/releases/latest")
            !shizukuAlive -> ShizukuHelper.openShizukuApp(context)
            !shizukuGranted -> ShizukuHelper.requestPermission()
            else -> ShizukuHelper.oneClickSetup(context) { permissions.refresh() }
        }
    }

    // 后台保活设置入口（文档 §12）：权限四态之外，国产 ROM 的「自启动/后台限制」是另一个静默故障源
    SettingsRow(
        title = stringResource(R.string.keepalive_entry_title),
        desc = stringResource(R.string.keepalive_entry_desc)
    ) {
        TextButton(onClick = onOpenKeepAlive) {
            Text(stringResource(R.string.keepalive_entry_action))
        }
    }
}

/** 权限行：标题 + 已授权徽标 / 去开启按钮 + 描述 */
@Composable
internal fun PermissionRow(title: String, desc: String, granted: Boolean, actionText: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Dims.SpacingM)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            if (granted) {
                PriceBadge(
                    stringResource(R.string.perm_action_granted, actionText),
                    BadgeTone.POSITIVE
                )
            } else {
                Button(onClick = onClick, shape = MaterialTheme.shapes.small) {
                    Text(actionText)
                }
            }
        }
        Text(
            desc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
