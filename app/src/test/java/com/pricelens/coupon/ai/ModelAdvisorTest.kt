package com.pricelens.coupon.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ModelAdvisor] 的钉子：它决定**首次进入时给不给推荐**，而推荐错了两个方向都难看 ——
 * 该推荐时不说（用户永远不知道有这个能力），不该推荐时乱说（用户下完发现跑不动）。
 *
 * 与 [OnDeviceAiPolicy] 的分工：策略回答"现在能不能跑"，这里回答"该给用户看什么"。
 * 最要紧的一条是**全新安装**：预算还是 0、开关还是关，也必须给出 SUGGEST_INSTALL ——
 * 那正是检测+推荐的时刻；第一版实现把预算原样喂给策略，结果全新安装被判成"机型装不下"，
 * 测试里那条用例就是为了不再犯。
 */
class ModelAdvisorTest {

    /** 一台"够格"的手机：8GB 内存、4GB 空闲、电量 80%、Wi-Fi、arm64 */
    private fun capable(
        deviceRamMb: Long = 8192,
        freeRamMb: Long = 4096,
        battery: Int = 80,
        metered: Boolean = false,
        abi: Boolean = true
    ) = DeviceCapability(deviceRamMb, freeRamMb, battery, metered, abi, 35)

    @Test
    fun `全新安装且机型够格时要推荐`() {
        // 预算 0、开关关 —— 这正是"首次进入检测"那一刻的状态
        val advice = ModelAdvisor.advice(capable(), modelDownloaded = false, userEnabled = false, cacheBudgetMb = 0L)
        assertEquals(ModelAdvice.State.SUGGEST_INSTALL, advice.state)
        assertEquals(ModelAdvisor.REASON_NOT_INSTALLED, advice.reasonKey)
    }

    @Test
    fun `已同意但还没下完时给下载入口而不是再问一遍`() {
        val advice = ModelAdvisor.advice(capable(), modelDownloaded = false, userEnabled = true, cacheBudgetMb = 0L)
        assertEquals(ModelAdvice.State.NEEDS_DOWNLOAD, advice.state)
    }

    @Test
    fun `装好且开着是 READY 装好但用户关着是 OFF`() {
        assertEquals(ModelAdvice.State.READY, ModelAdvisor.advice(capable(), true, true, 700L).state)
        assertEquals(ModelAdvice.State.OFF, ModelAdvisor.advice(capable(), true, false, 700L).state)
    }

    @Test
    fun `4GB 机型是永久不支持而不是等等就好`() {
        val advice = ModelAdvisor.advice(capable(deviceRamMb = 4096), false, false, 0L)
        assertEquals(ModelAdvice.State.UNSUPPORTED, advice.state)
        assertEquals(OnDeviceAiPolicy.ReasonDeviceRam, advice.reasonKey)
    }

    @Test
    fun `低电量与低空闲内存是等一下而不是不行`() {
        val battery = ModelAdvisor.advice(capable(battery = 15), false, false, 0L)
        assertEquals(ModelAdvice.State.WAIT, battery.state)
        assertEquals(ModelAdvisor.REASON_BATTERY, battery.reasonKey)

        val ram = ModelAdvisor.advice(capable(freeRamMb = 1024), false, false, 0L)
        assertEquals(ModelAdvice.State.WAIT, ram.state)
        assertEquals(ModelAdvisor.REASON_FREE_RAM, ram.reasonKey)
    }

    @Test
    fun `计费网络只挡下载不挡已经装好的模型`() {
        val notDownloaded = ModelAdvisor.advice(capable(metered = true), false, false, 0L)
        assertEquals(ModelAdvice.State.WAIT, notDownloaded.state)
        assertEquals(ModelAdvisor.REASON_METERED, notDownloaded.reasonKey)

        // 正例对照：同样的计费网络，模型已经装好了 ⇒ 照样能用（推理不产生流量）
        val downloaded = ModelAdvisor.advice(capable(metered = true), true, true, 700L)
        assertEquals(ModelAdvice.State.READY, downloaded.state)
    }

    @Test
    fun `没有 arm64 引擎时连机型都不用看`() {
        val advice = ModelAdvisor.advice(capable(abi = false), false, false, 0L)
        assertEquals(ModelAdvice.State.UNSUPPORTED, advice.state)
        assertEquals(ModelAdvisor.REASON_ABI, advice.reasonKey)
    }
}
