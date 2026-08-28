package com.pricelens.util

import java.util.concurrent.TimeUnit

/** 命令执行超时（秒）：防止挂起命令永久阻塞 Shizuku 的 Binder 线程 */
private const val EXEC_TIMEOUT_SECONDS = 10L

/**
 * Shizuku UserService 实现：运行在 Shizuku 的 shell（ADB）权限下，
 * 必须有无参构造。绑定的生命周期由 ShizukuHelper 管理。
 */
class ShizukuShellService : IShellService.Stub() {

    override fun exec(command: String): String {
        val process = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
        // 限时等待退出：无超时的 waitFor() 在命令挂起时会永久阻塞 Binder 线程
        val finished = process.waitFor(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroy()
            return "$out\n[pricelens] 执行超时（${EXEC_TIMEOUT_SECONDS}s），进程已终止"
        }
        return out
    }

    override fun destroy(): Int = 0
}
