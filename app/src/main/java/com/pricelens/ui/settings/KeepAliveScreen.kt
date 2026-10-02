package com.pricelens.ui.settings

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pricelens.R
import com.pricelens.keepalive.KeepAliveJump
import com.pricelens.keepalive.KeepAlivePlan
import com.pricelens.keepalive.Rom
import com.pricelens.ui.components.PriceBadge
import com.pricelens.ui.components.SectionHeader
import com.pricelens.ui.theme.BadgeTone
import com.pricelens.ui.theme.Dims
import com.pricelens.util.LogT
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 国产 ROM 保活引导页（文档 §12，设置 → 权限 → 后台保活设置）。
 *
 * 诚实原则：
 *  - 能自动判定的替你判定（通知权限、电池优化白名单）；
 *  - 判不了的不装能判：自启动 / 后台弹窗没有公开查询接口，直接写「系统不给查」；
 *    「前台服务是否在跑」同理，只如实显示本进程最近一轮盯价时间；
 *  - 跳转全部包 runCatching：任何一家 ROM 没有某个 Activity 都不闪退，
 *    候选链走完仍失败就静默（链尾的应用详情页几乎不会缺失）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeepAliveScreen(onBack: () -> Unit) {
    val viewModel: KeepAliveViewModel = hiltViewModel()
    val lastRound by viewModel.lastRound.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 与权限区块同一套「回前台重读」机制：从系统设置页返回后立即看到新状态
    val lifecycleOwner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val current = tick
    val notificationsGranted = remember(current) {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    val batteryUnrestricted = remember(current) { readBatteryUnrestricted(context) }
    // Android 13+ 通知是运行时权限：与设置页 PermissionSection 同一套做法
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        tick++
    }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.keepalive_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { inner ->
        Column(
            Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dims.SpacingL)
        ) {
            IntroCard()
            AutoCheckSection(
                notificationsGranted = notificationsGranted,
                onNotificationAction = {
                    if (Build.VERSION.SDK_INT >= 33 && !notificationsGranted) {
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        openAppNotificationSettings(context)
                    }
                },
                batteryUnrestricted = batteryUnrestricted,
                onBatteryAction = { launchKeepAlive(context, KeepAlivePlan.batteryTargets()) },
                lastRoundText = lastRound?.let {
                    stringResource(
                        R.string.keepalive_service_last_round,
                        ROUND_TIME_FORMAT.format(Date(it.atMillis))
                    )
                } ?: stringResource(R.string.keepalive_service_no_round)
            )
            ManualSection(
                onAutoStart = { launchKeepAlive(context, KeepAlivePlan.autoStartTargets(viewModel.rom)) },
                onBackgroundPopup = { launchKeepAlive(context, KeepAlivePlan.backgroundPopupTargets(viewModel.rom)) }
            )
            StepsSection(viewModel.rom)
            Spacer(Modifier.height(Dims.SpacingXXL))
        }
    }
}

/** 顶部「为什么」卡片：一句话把因果说明白（盯价被杀 → 降价提醒就不会来） */
@Composable
private fun IntroCard() {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Dims.SpacingL)
    ) {
        Column(Modifier.padding(Dims.SpacingL)) {
            Text(
                stringResource(R.string.keepalive_intro_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(Dims.SpacingXS))
            Text(
                stringResource(R.string.keepalive_intro_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

/** 自动检查区：能判定的自动判定，判不了的如实说「系统不给查」 */
@Composable
private fun AutoCheckSection(
    notificationsGranted: Boolean,
    onNotificationAction: () -> Unit,
    batteryUnrestricted: Boolean?,
    onBatteryAction: () -> Unit,
    lastRoundText: String
) {
    SectionHeader(stringResource(R.string.keepalive_section_auto))
    KeepAliveRow(
        title = stringResource(R.string.keepalive_notif_title),
        desc = stringResource(R.string.keepalive_notif_desc),
        badgeText = stringResource(
            if (notificationsGranted) R.string.keepalive_status_on else R.string.keepalive_status_off
        ),
        badgeTone = if (notificationsGranted) BadgeTone.POSITIVE else BadgeTone.NEGATIVE,
        actionText = stringResource(
            if (notificationsGranted) R.string.keepalive_action_go else R.string.perm_go_enable
        ),
        onAction = onNotificationAction
    )
    KeepAliveRow(
        title = stringResource(R.string.keepalive_battery_title),
        desc = stringResource(R.string.keepalive_battery_desc),
        badgeText = when (batteryUnrestricted) {
            true -> stringResource(R.string.keepalive_status_on)
            false -> stringResource(R.string.keepalive_status_off)
            null -> stringResource(R.string.keepalive_status_unknown)
        },
        badgeTone = when (batteryUnrestricted) {
            true -> BadgeTone.POSITIVE
            false -> BadgeTone.NEGATIVE
            null -> BadgeTone.NEUTRAL
        },
        actionText = stringResource(R.string.keepalive_action_go),
        onAction = onBatteryAction
    )
    // 没有「服务在跑」的公开查询：不装能判，只转述本进程最近一轮盯价时间
    KeepAliveRow(
        title = stringResource(R.string.keepalive_service_title),
        desc = stringResource(R.string.keepalive_service_desc),
        badgeText = stringResource(R.string.keepalive_status_unknown),
        badgeTone = BadgeTone.NEUTRAL,
        extra = lastRoundText
    )
}

/** 系统不给查的两项：状态不猜，跳转尽力而为 */
@Composable
private fun ManualSection(onAutoStart: () -> Unit, onBackgroundPopup: () -> Unit) {
    SectionHeader(stringResource(R.string.keepalive_section_manual))
    KeepAliveRow(
        title = stringResource(R.string.keepalive_autostart_title),
        desc = stringResource(R.string.keepalive_autostart_desc),
        badgeText = stringResource(R.string.keepalive_status_unknown),
        badgeTone = BadgeTone.NEUTRAL,
        actionText = stringResource(R.string.keepalive_action_go),
        onAction = onAutoStart
    )
    KeepAliveRow(
        title = stringResource(R.string.keepalive_bgpopup_title),
        desc = stringResource(R.string.keepalive_bgpopup_desc),
        badgeText = stringResource(R.string.keepalive_status_unknown),
        badgeTone = BadgeTone.NEUTRAL,
        actionText = stringResource(R.string.keepalive_action_go),
        onAction = onBackgroundPopup
    )
}

/** 分品牌步骤表：按 ROM 识别结果只显示对应那一段 */
@Composable
private fun StepsSection(rom: Rom) {
    SectionHeader(stringResource(R.string.keepalive_steps_title, rom.label))
    Text(
        stringResource(KeepAlivePlan.stepsRes(rom)),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(Modifier.height(Dims.SpacingS))
    Text(
        stringResource(R.string.keepalive_footnote),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** 保活行：标题 + 状态徽章 / 描述 / 附加事实（如最近一轮盯价时间）/ 去设置按钮 */
@Composable
private fun KeepAliveRow(
    title: String,
    desc: String,
    badgeText: String,
    badgeTone: BadgeTone,
    extra: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
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
            PriceBadge(badgeText, badgeTone)
        }
        Text(
            desc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (extra != null) {
            Text(
                extra,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (actionText != null && onAction != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onAction) {
                    Text(actionText)
                }
            }
        }
    }
}

/**
 * 电池优化白名单状态；查询被 ROM 拒绝时返回 null ——
 * 判不了就如实显示「系统不给查」，不许猜一个值糊弄用户。
 */
private fun readBatteryUnrestricted(context: Context): Boolean? = runCatching {
    (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .isIgnoringBatteryOptimizations(context.packageName)
}.getOrNull()

/**
 * 逐个候选尝试跳转：厂商 Activity 名随 ROM 版本变，系统对显式组件也可能因未导出/受限直接拒绝
 * （ActivityNotFoundException / SecurityException），所以每一步都 runCatching，失败就试下一个；
 * 候选链的链尾固定是应用详情页。整个过程兜到底，绝不闪退（文档 §12.3）。
 */
private fun launchKeepAlive(context: Context, targets: List<KeepAliveJump>) {
    for (target in targets) {
        val ok = runCatching { context.startActivity(keepAliveIntent(context, target)) }.isSuccess
        if (ok) return
    }
    LogT.w("保活引导：跳转候选全部失败（含应用详情页），已静默处理")
}

private fun keepAliveIntent(context: Context, target: KeepAliveJump): Intent = when (target) {
    is KeepAliveJump.VendorPage -> Intent()
        .setComponent(ComponentName(target.pkg, target.cls))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .apply { target.extraPkgNameKey?.let { putExtra(it, context.packageName) } }
    is KeepAliveJump.SystemPage -> Intent(target.action)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .apply { if (target.withPackageUri) data = Uri.parse("package:${context.packageName}") }
    KeepAliveJump.AppDetails -> Intent(KeepAlivePlan.APP_DETAILS_ACTION)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .setData(Uri.parse("package:${context.packageName}"))
}

/** 应用通知设置页（已授予时给用户一个复查/关闭的入口）；跳不动就退应用详情页 */
private fun openAppNotificationSettings(context: Context) {
    val ok = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.isSuccess
    if (!ok) launchKeepAlive(context, listOf(KeepAliveJump.AppDetails))
}

/** 最近一轮盯价时间（跨天也看得懂，所以带日期） */
private val ROUND_TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
