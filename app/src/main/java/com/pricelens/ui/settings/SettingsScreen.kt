package com.pricelens.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pricelens.BuildConfig
import com.pricelens.R
import com.pricelens.accessibility.OverlayManager
import com.pricelens.ui.components.BadgeTone
import com.pricelens.ui.components.PriceBadge
import com.pricelens.ui.main.MainViewModel
import com.pricelens.util.ShizukuHelper
import com.pricelens.util.UrlOpener

/**
 * 设置页：外观 / 权限（无障碍·悬浮窗·通知·Shizuku）/ 数据（缓存）/ 关于与免费声明。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val dynamicTheme by viewModel.dynamicTheme.collectAsStateWithLifecycle()
    val cacheStats by viewModel.cacheStats.collectAsStateWithLifecycle()

    // 进入设置页自动计算一次缓存占用（否则一直停在"计算中…"）
    LaunchedEffect(Unit) { viewModel.refreshCacheStats() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { inner ->
        Column(
            Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            SettingsSection("外观")
            SettingSwitchRow(
                title = "Material You 动态取色",
                desc = "跟随系统壁纸取色（Android 12+）；关闭后使用品牌蓝",
                checked = dynamicTheme,
                onCheckedChange = { viewModel.setDynamicTheme(it) }
            )

            SettingsSection("权限")
            PermissionSection()

            SettingsSection("数据")
            CacheRow(
                stats = cacheStats,
                onRefresh = { viewModel.refreshCacheStats() },
                onClear = { viewModel.clearCache() }
            )

            SettingsSection("关于")
            AboutSection()

            Spacer(Modifier.height(8.dp))
            Text(
                "作者：莫 · PriceLens 永久免费开源\n任何收费行为都是骗子，请勿上当",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
private fun SettingsSection(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingSwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun CacheRow(stats: String, onRefresh: () -> Unit, onClear: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text("缓存占用（上限 30MB）", style = MaterialTheme.typography.bodyLarge)
            Text(
                stats,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onRefresh) { Text("刷新") }
        Button(onClick = onClear, shape = MaterialTheme.shapes.small) { Text("清理") }
    }
}

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    var showDisclaimer by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text("版本 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
            Text(
                "比价数据仅存本机，绝不上传服务器",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = { showDisclaimer = true }) { Text("免费声明") }
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

// ---------- 权限区（无障碍 / 悬浮窗 / 通知 / Shizuku） ----------

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
private fun PermissionSection() {
    val context = LocalContext.current
    // 从系统设置返回时刷新各项状态
    var refreshKey by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(refreshKey) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshKey++
                ShizukuHelper.refresh()   // 兜底：从系统设置/其他应用返回时重算
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

    PermissionRow(
        title = "无障碍服务（自动比价）",
        desc = "打开京东/淘宝/拼多多商品页，用本机登录账号自动识别实时价并弹比价浮窗。仅监听这三个应用。",
        granted = accEnabled,
        actionText = if (accEnabled) "已开启" else "去开启"
    ) {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    PermissionRow(
        title = "悬浮窗权限（比价浮窗）",
        desc = "无障碍检测到价格后，在其他应用上方显示比价浮窗。",
        granted = overlayEnabled,
        actionText = if (overlayEnabled) "已开启" else "去开启"
    ) {
        OverlayManager.requestPermission(context)
    }

    PermissionRow(
        title = "通知权限（降价提醒）",
        desc = "后台盯价达到目标价时推送提醒，不发送任何营销通知。",
        granted = notifEnabled,
        actionText = if (notifEnabled) "已开启" else "去开启"
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
        title = "Shizuku 一键授权（可选）",
        desc = when {
            !shizukuInstalled ->
                "未安装 Shizuku。安装后可一键开启无障碍+悬浮窗，免手动设置。"
            !shizukuAlive ->
                "Shizuku 已安装，但服务未启动（手机重启后服务会自动停止，属正常现象）。" +
                    "请打开 Shizuku 点「启动」重新运行服务，启动后本页会自动刷新。"
            !shizukuGranted ->
                "Shizuku 服务运行中。点击「授权」并在弹窗中允许后，将自动开启无障碍+悬浮窗+通知权限（GKD 式一键体验）。"
            bothReady -> "无障碍与悬浮窗均已就绪，无需 Shizuku 介入。"
            else -> "点击即可一键开启无障碍服务并授予悬浮窗权限。"
        },
        granted = bothReady,
        actionText = when {
            !shizukuInstalled -> "安装 Shizuku"
            !shizukuAlive -> "打开 Shizuku"
            !shizukuGranted -> "授权 Shizuku"
            bothReady -> "已完成"
            else -> "一键开启"
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

@Composable
internal fun PermissionRow(
    title: String,
    desc: String,
    granted: Boolean,
    actionText: String,
    onClick: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            if (granted) {
                PriceBadge("✓ $actionText", BadgeTone.POSITIVE)
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
