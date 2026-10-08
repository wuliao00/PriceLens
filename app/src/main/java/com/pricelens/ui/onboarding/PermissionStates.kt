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

/** 本 App 的无障碍服务类名（不随 applicationId 变：它由 namespace 决定，调试包也是这个类名） */
private const val PRICELENS_SERVICE_CLASS = "com.pricelens.accessibility.PriceMonitorService"

/**
 * 纯判定部分。抽出来是为了能被 JVM 单测钉住——读 `Settings.Secure` 要 Context，测不到，
 * 而"两个包的开关会不会互相串味"恰恰是这次改动的全部风险所在（见 [isPriceLensAccessibilityEnabled]）。
 *
 * @param settingValue `enabled_accessibility_services` 原文，形如 `pkg/class:pkg/class`；没开启时是 null 或字面量 "null"
 * @param packageName **本包**的 applicationId（正式版 `com.pricelens`，调试包 `com.pricelens.dev`）
 */
fun accessibilityComponentEnabled(settingValue: String?, packageName: String): Boolean {
    if (packageName.isBlank()) return false
    if (settingValue.isNullOrBlank() || settingValue.equals("null", ignoreCase = true)) return false
    val component = "$packageName/$PRICELENS_SERVICE_CLASS"
    // 按 ':' 切逐项匹配、项内用 contains 而不是全等：个别 ROM 会在那串后面挂额外字段，
    // 全等会把"明明开着"判成没开——假阴比假阳更难被发现，也更糟：用户照着"没开"去开一遍，
    // 界面还是说他没开。而包名部分不会串味：`com.pricelens/…` 不是 `com.pricelens.dev/…` 的子串，反之亦然。
    return settingValue.split(':').any { it.contains(component, ignoreCase = true) }
}

/**
 * 无障碍服务是否已开启（从 PermissionSection 抽出的同一份判定，供引导与提示条复用）。
 * 命名与设置页原 internal 方法区分，避免两个包之间同名互相混淆。
 *
 * F16（2026-10-08）：判的是**这个包自己的组件**，不是子串 `"com.pricelens"`。
 * 旧写法 `enabled.contains("com.pricelens") && enabled.contains("PriceMonitorService")`
 * 在调试包上会误判：调试包的 applicationId 是 `com.pricelens.dev`，它同样 `contains("com.pricelens")`
 * ——于是"正式包的无障碍开着"会被调试包读成"我开着"（反之亦然）。
 * 同一台机上并装这两个包是本仓库验证流程的常态，不是假想情形。
 */
fun isPriceLensAccessibilityEnabled(context: Context): Boolean = accessibilityComponentEnabled(
    Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
    context.packageName
)

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
