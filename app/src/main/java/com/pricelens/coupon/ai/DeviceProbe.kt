package com.pricelens.coupon.ai

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build

/**
 * 采集 [DeviceCapability] 的六个标量。**唯一一处**碰 ActivityManager / BatteryManager /
 * ConnectivityManager 的地方 —— 判据全在 [ModelAdvisor] 与 [OnDeviceAiPolicy]（纯函数、可单测），
 * 这里只负责"把系统里的数读出来"，读不到就给保守值并让上层知道。
 *
 * 三个读不到的兜底（都取"对用户更保守"的那一侧）：
 *  - 电量读不到（部分 ROM 不给第三方应用）→ -1 ⇒ 会被电量判据拦下，用户看到的是"等条件"而不是"偷偷开跑"；
 *  - 内存读不到 → 0 ⇒ 机型判据直接拒绝（宁可不推荐，也不推荐一个会 OOM 的机型）；
 *  - 网络状况读不到 → 当作**计费网络** ⇒ 只影响下载（挡一下），不影响已有模型的推理。
 */
object DeviceProbe {

    private const val MIB = 1024L * 1024L

    /** `Build.SUPPORTED_ABIS` 里有 arm64 才算"本包引擎能跑"（引擎目前只编了 arm64-v8a） */
    fun abiSupported(): Boolean = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }

    fun capability(context: Context): DeviceCapability {
        val memory = ActivityManager.MemoryInfo()
        val am = context.getSystemService(ActivityManager::class.java)
        am?.getMemoryInfo(memory)
        val totalMb = if (memory.totalMem > 0) memory.totalMem / MIB else 0L
        val availMb = if (memory.availMem > 0) memory.availMem / MIB else 0L

        val battery = context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

        return DeviceCapability(
            deviceRamMb = totalMb,
            freeRamMb = availMb,
            batteryPercent = battery,
            onMeteredNetwork = isMetered(context),
            abiSupported = abiSupported(),
            androidSdk = Build.VERSION.SDK_INT
        )
    }

    /**
     * 计费网络判定。**没有活动网络时返回 false**：那一侧的判据是"下载要花流量"，
     * 而"没网"根本不是计费问题 —— 把它算成计费会让断网时的推荐卡显示"流量下不划算"，答非所问。
     */
    private fun isMetered(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
