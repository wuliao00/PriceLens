package com.pricelens.util

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.pricelens.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shizuku 辅助：借助 Shizuku 的 ADB 级权限，一键完成
 *  - 开启无障碍服务（settings put secure enabled_accessibility_services）
 *  - 授予悬浮窗权限（appops set SYSTEM_ALERT_WINDOW allow）
 *
 * 状态检测为响应式：注册 Binder 生命周期回调（received/dead/授权结果），
 * Shizuku 在后台启动/停止/授权时状态流自动更新，UI 无需手动刷新、无需轮询。
 * 命令执行走官方 UserService（AIDL）通道：bindUserService → IShellService.exec。
 */
object ShizukuHelper {

    /** 四态：未安装 / 已装未启动 / 运行中未授权 / 已就绪 */
    enum class ShizukuState { NOT_INSTALLED, INSTALLED_NOT_RUNNING, RUNNING_NOT_GRANTED, READY }

    private val _status = MutableStateFlow(ShizukuState.NOT_INSTALLED)
    val status: StateFlow<ShizukuState> = _status.asStateFlow()

    private var initialized = false
    private var appCtx: Context? = null

    /** Application onCreate 时调用一次：注册 Binder 回调，之后状态自动流转 */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appCtx = context.applicationContext
        try {
            // sticky：若 Binder 已就绪会立即回调一次；服务后台启动/停止时自动推送
            rikka.shizuku.Shizuku.addBinderReceivedListenerSticky { refresh() }
            rikka.shizuku.Shizuku.addBinderDeadListener { refresh() }
            rikka.shizuku.Shizuku.addRequestPermissionResultListener { _, grantResult ->
                refresh()
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    // GKD 式体验：在 Shizuku 弹窗里点了"允许" → 自动开启全部权限，
                    // 用户回到设置页时无障碍/悬浮窗/通知已就绪
                    appCtx?.let { ctx -> oneClickSetup(ctx) { } }
                }
            }
        } catch (_: Exception) {
        }
        refresh()
    }

    /** 重算当前状态（Binder 回调触发；ON_RESUME 时也可兜底调用） */
    fun refresh() {
        val ctx = appCtx ?: return
        val installed = isInstalled(ctx)
        val alive = installed && isAlive()
        val granted = alive && isGranted()
        val newState = when {
            !installed -> ShizukuState.NOT_INSTALLED
            !alive -> ShizukuState.INSTALLED_NOT_RUNNING
            !granted -> ShizukuState.RUNNING_NOT_GRANTED
            else -> ShizukuState.READY
        }
        if (newState != _status.value) {
            LogT.i("Shizuku 状态: ${_status.value} -> $newState (installed=$installed alive=$alive granted=$granted)")
        }
        _status.value = newState
    }

    /** Shizuku 官方包名（Manifest <queries> 已声明，Android 11+ 可见） */
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    /** Shizuku APK 是否已安装（区分"未安装"与"已装未启动"两种状态） */
    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: Exception) {
        false
    }

    /** 打开已安装的 Shizuku App（引导用户启动服务）；未安装跳 GitHub 发布页 */
    fun openShizukuApp(context: Context) {
        val launch = try {
            context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        } catch (_: Exception) {
            null
        }
        if (launch != null) {
            launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
        } else {
            UrlOpener.open(context, "https://github.com/RikkaApps/Shizuku/releases/latest")
        }
    }

    /**
     * Binder 是否可用。必须用 getBinder() 而不是 pingBinder()：
     * getBinder() 在静态 Binder 缺失时会主动向本应用的 provider 请求拉取
     * （覆盖"进程被系统回收后重启、错过 Shizuku 推送"的场景），
     * pingBinder() 只读静态字段，会误报"服务未启动"。
     */
    fun isAlive(): Boolean = try {
        val binder = rikka.shizuku.Shizuku.getBinder()
        binder != null && binder.pingBinder()
    } catch (_: Exception) {
        false
    }

    fun isGranted(): Boolean = try {
        rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        false
    }

    fun requestPermission() {
        try {
            rikka.shizuku.Shizuku.requestPermission(1001)
        } catch (_: Exception) {
        }
    }

    private fun userServiceArgs(context: Context): rikka.shizuku.Shizuku.UserServiceArgs = rikka.shizuku.Shizuku.UserServiceArgs(
        ComponentName(context, ShizukuShellService::class.java)
    )
        .daemon(false)
        .processNameSuffix("shell")
        .debuggable(BuildConfig.DEBUG)
        .version(1)

    /**
     * 自定义脚本入口：经 Shizuku（ADB/shell 权限）执行任意 shell 命令，
     * 返回合并后的 stdout+stderr。需 Shizuku READY，否则回调错误提示。
     */
    fun runCustomScript(context: Context, script: String, onResult: (Boolean, String) -> Unit) {
        if (!isAlive() || !isGranted()) {
            onResult(false, "Shizuku 未就绪：请先在设置中启动服务并授权")
            return
        }
        execViaShizuku(context, script) { ok, output ->
            onResult(ok, if (ok) output else (if (output.isEmpty()) "执行失败（Shizuku 连接异常）" else output))
        }
    }

    /** UserService AIDL 通用执行：bind → exec → unbind，回传合并输出 */
    private fun execViaShizuku(context: Context, cmd: String, onResult: (Boolean, String) -> Unit) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                var ok = false
                var output = ""
                try {
                    val shell = IShellService.Stub.asInterface(binder)
                    output = shell.exec(cmd)
                    ok = true
                } catch (e: Exception) {
                    output = e.message ?: ""
                } finally {
                    try {
                        rikka.shizuku.Shizuku.unbindUserService(
                            userServiceArgs(context),
                            this,
                            true
                        )
                    } catch (_: Exception) {
                    }
                    onResult(ok, output)
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {}
        }

        try {
            rikka.shizuku.Shizuku.bindUserService(userServiceArgs(context), connection)
        } catch (e: Exception) {
            onResult(false, e.message ?: "Shizuku 绑定失败")
        }
    }

    /**
     * 一键开启无障碍 + 悬浮窗 + 通知权限。完成后回调 true/false。
     *
     * 命令保持幂等：已在列表中则不重复追加；低版本无通知运行时权限时忽略报错。
     * 写完 settings 后再主动 ping 一次 UserService（避免 R8 混淆下"看似 OK 实则未连"）。
     */
    fun oneClickSetup(context: Context, onDone: (Boolean) -> Unit) {
        if (!isAlive() || !isGranted()) {
            onDone(false)
            return
        }
        // 脚本实现与 ScriptStore 预置脚本共用（accessibilitySetupScript）：
        // 组件名是 Kotlin 变量，经插值直接写入脚本；shell 层变量由函数内部转义。
        // 此前误用 ${'$'}svc 转义，导致脚本引用未定义 shell 变量、写入空值，一键开启必失败。
        val cmd = ScriptStore.accessibilitySetupScript(ScriptStore.ACCESSIBILITY_COMPONENT)

        execViaShizuku(context, cmd) { ok, output ->
            val writeOk = ok && output.contains("SETUP_OK")
            if (writeOk) {
                // 写入成功 → 自动跳无障碍设置页（部分 ROM 还需要用户在系统弹窗里点"确定"）
                // 这样把"系统拒绝"的最后一个确认步骤也自动引导到位
                try {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                } catch (_: Exception) {
                }
            }
            onDone(writeOk)
        }
    }
}
