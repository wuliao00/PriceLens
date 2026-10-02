package com.pricelens.keepalive

import android.provider.Settings
import androidx.annotation.StringRes
import com.pricelens.R

/** 一次「去设置」跳转目标（纯数据；Intent 的组装在 UI 层完成）。 */
sealed interface KeepAliveJump {

    /**
     * 厂商设置页：ROM 版本一变 Activity 名就可能变，所以同一个用途给一串候选按序尝试；
     * 包名/类名只负责「先送到哪」，送不到也没有正确性风险（链尾必到应用详情页）。
     */
    data class VendorPage(
        val pkg: String,
        val cls: String,
        /** 部分厂商页需要显式传入本应用包名（如 MIUI 权限编辑页的 extra_pkgname） */
        val extraPkgNameKey: String? = null
    ) : KeepAliveJump

    /** 系统标准设置页（action 取自 android.provider.Settings 的编译期常量，JVM 单测可直接对表） */
    data class SystemPage(val action: String, val withPackageUri: Boolean = false) : KeepAliveJump

    /** 最终兜底：应用信息页。每家 ROM 都有，永远放在链尾。 */
    data object AppDetails : KeepAliveJump
}

/**
 * 保活引导的「看哪段、跳哪里」决策（纯逻辑；文案本身在 strings.xml）。
 *
 *  - [stepsRes]：按识别出的品牌选步骤表（哪一段该显示）；
 *  - [autoStartTargets] / [backgroundPopupTargets]：自启动、后台弹出界面的跳转候选链；
 *  - [batteryTargets]：电池优化白名单的跳转候选链。
 *
 * 红线（文档 §12.3）：
 *  - 自启动 / 后台弹窗没有任何公开查询接口，这里只负责「把用户送到设置页」，
 *    不提供也不暗示任何「是否已开启」的判断；
 *  - 每一条链的最后一项必须是 [KeepAliveJump.AppDetails] —— 厂商 Activity 改名或不存在时，
 *    UI 层逐个 runCatching 尝试，最终一定能落到每家都有的应用信息页。
 */
object KeepAlivePlan {

    /** 应用信息页 action（UI 组装兜底 Intent 用；单测锁住这个字面值） */
    val APP_DETAILS_ACTION: String = Settings.ACTION_APPLICATION_DETAILS_SETTINGS

    /**
     * 电池优化白名单：优先尝试「直接为本应用请求不优化」（部分 ROM 无此页、或系统直接拒绝），
     * 再退到全量电池优化列表页，最后退应用详情页。查询状态走
     * PowerManager.isIgnoringBatteryOptimizations（见 UI 层），这里只管跳转。
     */
    fun batteryTargets(): List<KeepAliveJump> = listOf(
        KeepAliveJump.SystemPage(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, withPackageUri = true),
        KeepAliveJump.SystemPage(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        KeepAliveJump.AppDetails
    )

    /** 自启动 / 允许后台拉起：各品牌已知入口按顺序试，最后必落应用详情页 */
    fun autoStartTargets(rom: Rom): List<KeepAliveJump> {
        val vendor: List<KeepAliveJump> = when (rom) {
            Rom.XIAOMI -> listOf(
                KeepAliveJump.VendorPage("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            )
            Rom.HUAWEI -> listOf(
                KeepAliveJump.VendorPage(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                ),
                KeepAliveJump.VendorPage(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
                )
            )
            Rom.OPPO -> listOf(
                KeepAliveJump.VendorPage("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                KeepAliveJump.VendorPage("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"),
                KeepAliveJump.VendorPage("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")
            )
            Rom.VIVO -> listOf(
                KeepAliveJump.VendorPage("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                KeepAliveJump.VendorPage("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
            )
            Rom.OTHER -> emptyList()
        }
        return vendor + KeepAliveJump.AppDetails
    }

    /**
     * 后台弹出界面 / 后台运行限制：「后台弹出界面」是 MIUI 系独有的一道权限页；
     * 其它品牌没有对应入口，候选给空、链尾的应用详情页仍然在（UI 一定跳得动）。
     */
    fun backgroundPopupTargets(rom: Rom): List<KeepAliveJump> {
        val vendor: List<KeepAliveJump> = when (rom) {
            Rom.XIAOMI -> listOf(
                KeepAliveJump.VendorPage(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity",
                    extraPkgNameKey = "extra_pkgname"
                )
            )
            Rom.HUAWEI, Rom.OPPO, Rom.VIVO, Rom.OTHER -> emptyList()
        }
        return vendor + KeepAliveJump.AppDetails
    }

    /** 按识别出的品牌选步骤表（五段互不相同，单测钉住） */
    @StringRes
    fun stepsRes(rom: Rom): Int = when (rom) {
        Rom.XIAOMI -> R.string.keepalive_steps_xiaomi
        Rom.HUAWEI -> R.string.keepalive_steps_huawei
        Rom.OPPO -> R.string.keepalive_steps_oppo
        Rom.VIVO -> R.string.keepalive_steps_vivo
        Rom.OTHER -> R.string.keepalive_steps_other
    }
}
