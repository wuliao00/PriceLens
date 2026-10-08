package com.pricelens.coupon.ai

/**
 * 这台手机"跑不跑得动端侧兜底"的一份快照（纯数据，采集侧在 `DeviceProbe`）。
 *
 * 字段刻意只有六个、且都是标量：采集侧（ActivityManager / BatteryManager / ConnectivityManager / Build）
 * 各读一个数就能填满，判据本身则完全可单测 —— 与 [OnDeviceAiPolicy] 的分工一致。
 *
 * @param abiSupported 本机 ABI 是否在引擎覆盖范围内。这一条**不在**策略里，因为策略吃的是"七个标量"，
 *   而 ABI 是"引擎有没有编进来"的事实：装了个 32 位包，策略再宽松也跑不了 .so。
 * @param androidSdk 仅用于展示与日志（`minSdk` 已经保证了 26+）；策略不拿它做判据，避免又多一个"看着像阈值"的数
 */
data class DeviceCapability(
    val deviceRamMb: Long,
    val freeRamMb: Long,
    val batteryPercent: Int,
    val onMeteredNetwork: Boolean,
    val abiSupported: Boolean,
    val androidSdk: Int
)

/**
 * 端侧模型的"给用户看的那句话"由什么决定（纯函数，UI 只负责把 [reasonKey] 翻成文案）。
 *
 * 与 [OnDeviceAiPolicy.eligible] 的关系：策略回答"现在能不能跑"，这里回答**"该给用户看什么"**。
 * 两者不同的地方只有一处、但很关键：策略从 `userEnabled=false` 直接给出 Refused(off)，
 * 而首次进入时"用户还没表态"与"用户明确关掉了"要区分开 —— 否则推荐卡永远不出现。
 * 所以这里用 `userEnabled = true` 去问策略（把"机型行不行"问出来），用户开关单独判。
 */
data class ModelAdvice(
    val state: State,
    val reasonKey: String,
    /**
     * "这一页该提醒用户：下载走的是计费网络"。2026-10-08 加，与下面 metered 那条判据的改动配对。
     *
     * 只在**还没下载**时才可能为 true：模型已在本地时推理不产生流量（[OnDeviceAiPolicy] 的
     * `onMeteredNetwork` 注释就是这条口径），那时候提醒是噪音。
     */
    val meteredWarning: Boolean = false
) {

    /**
     * - [READY] 已装且已开：什么都不用提示
     * - [OFF] 已装但用户关着：只在设置里露出开关，不弹推荐
     * - [SUGGEST_INSTALL] 机型能跑、还没装：**首次进入要推荐的那个状态**
     * - [NEEDS_DOWNLOAD] 用户已同意但还没装好（下到一半/被删了）：直接给下载入口，不再问一遍
     * - [WAIT] 条件暂时不满足（电量/空闲内存）——等条件变了可以重试，别对用户说"不行"
     * - [UNSUPPORTED] 结论不随等待改变（机型内存不够、装不下、或本包没有 arm64 引擎）
     */
    enum class State { READY, OFF, SUGGEST_INSTALL, NEEDS_DOWNLOAD, WAIT, UNSUPPORTED }
}

object ModelAdvisor {

    private const val MIB = 1024L * 1024L

    /** [ModelAdvice.reasonKey] 的取值（UI 按这个键查文案；加新键要同时加资源，否则界面上是空白） */
    const val REASON_READY = "ready"
    const val REASON_OFF = "off"
    const val REASON_NOT_INSTALLED = "not-installed"
    const val REASON_BATTERY = "battery"
    const val REASON_FREE_RAM = "ram"

    /**
     * "排队是被计费网络挡的"这个标记。它**不会**出现在 [ModelAdvice.reasonKey] 里
     * （那条判据在本层会被降级成"给入口 + 提醒"，见 [advice]），只用来分辨
     * `QueueUntilIdle` 到底是被哪一条挡的——不另起一套判据顺序，避免两处各写一遍阈值。
     */
    const val REASON_METERED = "metered"

    const val REASON_ABI = "abi"

    fun advice(capability: DeviceCapability, modelDownloaded: Boolean, userEnabled: Boolean, cacheBudgetMb: Long): ModelAdvice {
        if (!capability.abiSupported) return ModelAdvice(ModelAdvice.State.UNSUPPORTED, REASON_ABI)
        // 预算的两种口径要说清：**没装时**问的是"如果用户同意，装得下吗"，
        // 所以拿"软上限"当假设预算 —— 否则全新安装（预算还是 0）会被判成"机型装不下"，
        // 而这恰恰是最该出现推荐卡的时刻。装好之后才按真实预算判。
        val effectiveBudget = if (modelDownloaded) cacheBudgetMb else maxOf(cacheBudgetMb, OnDeviceAiPolicy.ModelBytesSoftCap / MIB)
        val hypothetical = OnDeviceAiPolicy.eligible(
            freeRamMb = capability.freeRamMb,
            batteryPercent = capability.batteryPercent,
            // 真值照喂。这里不预先替策略把 metered 抹成 false：策略层那条判据的唯一生产调用方
            // 就是本函数，抹掉等于让它那条判据再也没被执行过（2026-10-08 的第一版改法就是这么错的）。
            // "用户点一下就能承担这条代价"这件事，表达在下面 QueueUntilIdle 的分支里。
            onMeteredNetwork = capability.onMeteredNetwork,
            modelDownloaded = modelDownloaded,
            userEnabled = true,
            deviceRamMb = capability.deviceRamMb,
            cacheBudgetMb = effectiveBudget
        )
        val (state, reason) = when (hypothetical) {
            AiDecision.Run -> when {
                !modelDownloaded && userEnabled -> ModelAdvice.State.NEEDS_DOWNLOAD to REASON_NOT_INSTALLED
                !modelDownloaded -> ModelAdvice.State.SUGGEST_INSTALL to REASON_NOT_INSTALLED
                userEnabled -> ModelAdvice.State.READY to REASON_READY
                else -> ModelAdvice.State.OFF to REASON_OFF
            }
            AiDecision.QueueUntilIdle -> {
                val why = waitReason(capability, modelDownloaded)
                if (why == REASON_METERED) {
                    // 三条排队原因里，只有计费网络是"用户点一下、代价他自己认"就能继续的那种：
                    // 策略那条判据的语义是"App 别偷偷用流量下 397MB"，不是"用户不许下"。
                    // 电量/空闲内存不一样——那两条是"下完也跑不动"，用户点确认也变不出内存，
                    // 所以它们仍然走 WAIT，不许被这次降级一起放过去。
                    // （为什么这里不必再判一遍电量内存：策略的判据顺序是 电量→内存→计费，
                    // 能走到 metered 就说明前两条已经过了；顺序由 OnDeviceAiPolicyTest 钉着。）
                    (if (userEnabled) ModelAdvice.State.NEEDS_DOWNLOAD else ModelAdvice.State.SUGGEST_INSTALL) to REASON_NOT_INSTALLED
                } else {
                    ModelAdvice.State.WAIT to why
                }
            }
            is AiDecision.Refused -> ModelAdvice.State.UNSUPPORTED to hypothetical.reason
        }
        return ModelAdvice(state, reason, meteredWarning = capability.onMeteredNetwork && !modelDownloaded)
    }

    /** 排队的原因（按策略的判据顺序取第一个不满足的；顺序与 [OnDeviceAiPolicy.eligible] 保持一致） */
    private fun waitReason(capability: DeviceCapability, modelDownloaded: Boolean): String = when {
        capability.batteryPercent < OnDeviceAiPolicy.MinBatteryPercent -> REASON_BATTERY
        capability.freeRamMb < OnDeviceAiPolicy.MinFreeRamMb -> REASON_FREE_RAM
        !modelDownloaded && capability.onMeteredNetwork -> REASON_METERED
        else -> REASON_FREE_RAM
    }
}
