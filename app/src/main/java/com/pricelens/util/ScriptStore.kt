package com.pricelens.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 自定义脚本仓库：SharedPreferences 持久化（名称 + 内容），
 * 附带 3 个安全预置脚本。脚本经 Shizuku（ADB/shell 权限）执行。
 */
object ScriptStore {

    data class Script(
        // System.nanoTime 生成
        val id: String,
        val name: String,
        val content: String,
        val builtin: Boolean = false
    )

    private const val PREFS = "pricelens"
    private const val KEY = "custom_scripts"

    /** 本应用无障碍服务 ComponentName（一键授权脚本共用） */
    const val ACCESSIBILITY_COMPONENT = "com.pricelens/com.pricelens.accessibility.PriceMonitorService"

    /**
     * 生成「一键开启无障碍 + 悬浮窗 + 通知」shell 脚本。
     * ShizukuHelper.oneClickSetup 与预置脚本 builtin_setup 共用此实现，避免两处逻辑分叉。
     * 注意：[svc] 是 Kotlin 变量，经原始字符串插值直接写入脚本；
     * shell 层自身的变量（CUR/NEW）必须写成 ${'$'}VAR 才能在脚本里保留 `$VAR` 字面量。
     * 脚本幂等：已在无障碍列表则不重复追加；结尾回显 SETUP_OK/SETUP_FAIL 供调用方校验写入是否生效。
     */
    fun accessibilitySetupScript(svc: String): String = """
        CUR=`settings get secure enabled_accessibility_services`
        case "`echo ${'$'}CUR`" in
          *com.pricelens*) ;;
          *) if [ -z "${'$'}CUR" ] || [ "${'$'}CUR" = "null" ]; then
                 settings put secure enabled_accessibility_services "$svc";
             else
                 settings put secure enabled_accessibility_services "`echo ${'$'}CUR`:$svc";
             fi ;;
        esac
        settings put secure accessibility_enabled 1
        # --uid 形式兼容 Android 13+，失败回退直接 appops set（兼容 MIUI/ColorOS）
        appops set --uid com.pricelens SYSTEM_ALERT_WINDOW allow || appops set com.pricelens SYSTEM_ALERT_WINDOW allow
        pm grant com.pricelens android.permission.POST_NOTIFICATIONS || true
        NEW=`settings get secure enabled_accessibility_services`
        case "`echo ${'$'}NEW`" in
          *com.pricelens*) echo SETUP_OK ;;
          *) echo SETUP_FAIL ;;
        esac
    """.trimIndent()

    /** 安全预置脚本（不可删除，可查看/运行） */
    val builtins = listOf(
        Script(
            id = "builtin_setup",
            name = "一键开启无障碍+悬浮窗+通知",
            content = accessibilitySetupScript(ACCESSIBILITY_COMPONENT) +
                "\necho 已开启 PriceLens 全部权限",
            builtin = true
        ),
        Script(
            id = "builtin_prop",
            name = "查看设备信息",
            content = """
                echo "== 型号 =="
                getprop ro.product.marketname || getprop ro.product.model
                echo "== 系统 =="
                getprop ro.build.version.release
                echo "== 安全补丁 =="
                getprop ro.build.version.security_patch
                echo "== 电量 =="
                dumpsys battery | grep level
            """.trimIndent(),
            builtin = true
        ),
        Script(
            id = "builtin_foreground",
            name = "查看当前前台应用",
            content = """
                dumpsys activity activities 2>/dev/null | grep -m1 "topResumedActivity" \
                  || dumpsys window 2>/dev/null | grep -m1 "mCurrentFocus"
            """.trimIndent(),
            builtin = true
        )
    )

    fun loadCustom(context: Context): List<Script> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Script(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    content = o.optString("content")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveCustom(context: Context, scripts: List<Script>) {
        val arr = JSONArray()
        scripts.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("content", it.content))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun add(context: Context, name: String, content: String): List<Script> {
        val updated = loadCustom(context) + Script(
            id = System.nanoTime().toString(),
            name = name.trim().ifEmpty { "未命名脚本" },
            content = content
        )
        saveCustom(context, updated)
        return updated
    }

    fun update(context: Context, id: String, name: String, content: String): List<Script> {
        val updated = loadCustom(context).map {
            if (it.id == id) {
                it.copy(name = name.trim().ifEmpty { it.name }, content = content)
            } else {
                it
            }
        }
        saveCustom(context, updated)
        return updated
    }

    fun remove(context: Context, id: String): List<Script> {
        val updated = loadCustom(context).filterNot { it.id == id }
        saveCustom(context, updated)
        return updated
    }
}
