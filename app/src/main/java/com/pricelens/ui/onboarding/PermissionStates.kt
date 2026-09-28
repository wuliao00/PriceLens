package com.pricelens.ui.onboarding

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pricelens.accessibility.OverlayManager
import com.pricelens.util.ShizukuHelper

/**
 * 权限态单一来源（原先内联在 PermissionSection 的 ON_RESUME + refreshKey 算法，现抽出复用）。
 *
 * 复用方：
 *  - [com.pricelens.ui.settings.PermissionSection]（行为与抽取前完全一致）
 *  - 新手引导各步骤 / 首页 SetupHintBar
 *
 * 刷新时机：页面 ON_RESUME（用户从系统设置页返回）+ 调用方主动 `refresh()`
 * （系统授权回调、Shizuku 一键开启完成后）。
 */

/**
 * 无障碍服务是否已开启（从 PermissionSection 抽出的同一份判定，供引导与提示条复用）。
 * 命名与设置页原 internal 方法区分，避免两个包之间同名互相混淆。
 */
fun isPriceLensAccessibilityEnabled(context: Context): Boolean {
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabled.contains("com.pricelens") &&
        enabled.contains("PriceMonitorService", ignoreCase = true)
}

/** 还缺哪些必要权限（首页 SetupHintBar 文案用） */
enum class MissingEssential {
    ACCESSIBILITY,
    OVERLAY
}

/** 一次读取的权限快照 + 主动重算入口 */
class PermissionStatesHandle(
    val accessibilityGranted: Boolean,
    val overlayGranted: Boolean,
    val notificationsGranted: Boolean,
    val shizukuState: ShizukuHelper.ShizukuState,
    val refresh: () -> Unit
) {
    /** 自动比价的两个必要权限 */
    val essentialsReady: Boolean get() = accessibilityGranted && overlayGranted

    val missingEssentials: List<MissingEssential>
        get() = buildList {
            if (!accessibilityGranted) add(MissingEssential.ACCESSIBILITY)
            if (!overlayGranted) add(MissingEssential.OVERLAY)
        }

    val shizukuInstalled: Boolean get() = shizukuState != ShizukuHelper.ShizukuState.NOT_INSTALLED
    val shizukuAlive: Boolean
        get() = shizukuState == ShizukuHelper.ShizukuState.RUNNING_NOT_GRANTED ||
            shizukuState == ShizukuHelper.ShizukuState.READY
    val shizukuReady: Boolean get() = shizukuState == ShizukuHelper.ShizukuState.READY
}

@Composable
fun rememberPermissionStates(): PermissionStatesHandle {
    val context = LocalContext.current
    // 计数型刷新键：ON_RESUME 与授权回调都只把它 +1，读取侧的 remember 随之重算
    val tick = remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(tick) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                tick.intValue++
                ShizukuHelper.refresh() // 兜底：从系统设置/其他应用返回时重算
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val current = tick.intValue
    val accessibilityGranted = remember(current) { isPriceLensAccessibilityEnabled(context) }
    val overlayGranted = remember(current) { OverlayManager.canDrawOverlays(context) }
    val notificationsGranted = remember(current) {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    val shizukuState by ShizukuHelper.status.collectAsStateWithLifecycle()

    return PermissionStatesHandle(
        accessibilityGranted = accessibilityGranted,
        overlayGranted = overlayGranted,
        notificationsGranted = notificationsGranted,
        shizukuState = shizukuState,
        refresh = { tick.intValue++ }
    )
}
