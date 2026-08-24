package com.pricelens.util

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
        process.waitFor()
        return out
    }

    override fun destroy(): Int = 0
}
