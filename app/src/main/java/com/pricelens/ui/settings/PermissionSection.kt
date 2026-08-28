package com.pricelens.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pricelens.R
import com.pricelens.accessibility.OverlayManager
import com.pricelens.ui.components.PriceBadge
import com.pricelens.ui.components.SectionHeader
import com.pricelens.ui.theme.BadgeTone
import com.pricelens.ui.theme.Dims
import com.pricelens.util.ShizukuHelper
import com.pricelens.util.UrlOpener

/**
 * 设置页 · 权限区块：无障碍 / 悬浮窗 / 通知 / Shizuku 四态。
 * 逻辑与阶段2完全一致，仅迁移文案与留白令牌。
 */

/** 无障碍服务是否已在系统设置中开启 */
internal fun isAccessibilityEnabled(context: Context): Boolean {
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabled.contains("com.pricelens") &&
        enabled.contains("PriceMonitorService", ignoreCase = true)
}

@Composable
fun PermissionSection() {
    SectionHeader(stringResource(R.string.settings_section_permission))

    val context = LocalContext.current
    // 从系统设置返回时刷新各项状态
    var refreshKey by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(refreshKey) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshKey++
                ShizukuHelper.refresh() // 兜底：从系统设置/其他应用返回时重算
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val accEnabled = remember(refreshKey) { isAccessibilityEnabled(context) }
    val overlayEnabled = remember(refreshKey) { OverlayManager.canDrawOverlays(context) }
    val notifEnabled = remember(refreshKey) {
        androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshKey++ }

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
    val shizukuState by ShizukuHelper.status.collectAsStateWithLifecycle()
    val shizukuInstalled = shizukuState != ShizukuHelper.ShizukuState.NOT_INSTALLED
    val shizukuAlive = shizukuState == ShizukuHelper.ShizukuState.RUNNING_NOT_GRANTED ||
        shizukuState == ShizukuHelper.ShizukuState.READY
    val shizukuGranted = shizukuState == ShizukuHelper.ShizukuState.READY
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
            else -> ShizukuHelper.oneClickSetup(context) { refreshKey++ }
        }
    }
}

/** 权限行：标题 + 已授权徽标 / 去开启按钮 + 描述 */
@Composable
internal fun PermissionRow(title: String, desc: String, granted: Boolean, actionText: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
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
