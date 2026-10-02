package com.pricelens.util

import android.content.Context
import android.net.Uri
import android.os.Build
import com.pricelens.BuildConfig
import java.io.File
import java.time.ZoneId

/**
 * 崩溃兜底 + 导出（文档 §十三的本地形态，2026-10-02 用户拍板：纯本地、不上传）。
 *
 * 因为没有上传通道，所以没有 ACRA、也没有「发送崩溃报告」同意开关 ——
 * 只有一颗 UncaughtExceptionHandler 和设置页里用户自己触发的导出。
 *
 * 三个不许：不写 Cookie/header（只记版本与机型）、不联网、不吞掉系统默认处理
 * （先记账，再把异常原样交给前一个处理器，该弹「应用停止运行」还是弹）。
 */
object CrashHandler {

    private const val LOGCAT_TAIL_LINES = 800
    private const val LOGCAT_MAX_CHARS = 256 * 1024

    @Volatile
    private var installed = false

    /** 尽早调用（Application.attachBaseContext）：连 Application 初始化自己崩了也能留下现场 */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext ?: context
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrash(appContext, thread.name, throwable) }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    fun store(context: Context): CrashLogStore = CrashLogStore(File(context.filesDir, CrashLog.DIR_NAME))

    fun count(context: Context): Int = runCatching { store(context).listNewestFirst().size }.getOrDefault(0)

    fun clear(context: Context) {
        runCatching { store(context).clear() }
    }

    internal fun writeCrash(context: Context, threadName: String, throwable: Throwable) {
        val info = listOf(
            "app=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            "android=${Build.VERSION.RELEASE} (sdk=${Build.VERSION.SDK_INT})",
            "device=${Build.BRAND} ${Build.MODEL} / ${Build.DEVICE}"
        )
        val now = System.currentTimeMillis()
        val entry = CrashLog.buildEntry(now, ZoneId.systemDefault(), threadName, info, throwable)
        // 写盘前也过一遍脱敏：异常 message 里若混入了凭证串，磁盘上也不留原样
        store(context).write(CrashLog.redact(entry), now)
    }

    /** 导出到用户选定的 SAF 位置；返回写出的崩溃条数 */
    fun exportTo(context: Context, uri: Uri): Result<Int> = runCatching {
        val entries = store(context).listNewestFirst().mapNotNull { file ->
            runCatching { file.readText() }.getOrNull()
        }
        val appLine = "app=PriceLens ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" +
            " / android=${Build.VERSION.RELEASE} (sdk=${Build.VERSION.SDK_INT})" +
            " / device=${Build.BRAND} ${Build.MODEL} / ${Build.DEVICE}"
        val report = CrashLog.buildReport(
            generatedAtMs = System.currentTimeMillis(),
            zone = ZoneId.systemDefault(),
            appLine = appLine,
            entries = entries,
            logcat = readLogcatTail()
        )
        context.contentResolver.openOutputStream(uri)?.use { it.write(report.toByteArray()) }
            ?: error("openOutputStream 返回 null")
        entries.size
    }

    /** 尽最大努力取本应用自己的最近 logcat；拿不到就留空（导出里那一节直接省略）。 */
    private fun readLogcatTail(): String = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", LOGCAT_TAIL_LINES.toString()))
        val text = process.inputStream.bufferedReader().use { it.readText() }
        text.take(LOGCAT_MAX_CHARS)
    }.getOrDefault("")
}
