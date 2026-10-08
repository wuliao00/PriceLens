package com.pricelens.coupon.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    /*
     * 计费网络这一组（2026-10-08 改契约）：
     *
     * 改前这里是 `WAIT + REASON_METERED` —— 移动数据下**连下载按钮都不给**。那条 metered 判据
     * 在 `OnDeviceAiPolicy` 里的语义是"App 自己别偷偷用流量下 397MB"（策略层那条测试一字未改，
     * 依旧 queue），但本层回答的是"该给用户看什么"，于是把一个"要提醒"的事做成了"不许"：
     * 用户站在设置页里，看着一句"等条件满足再来"，什么也点不了。
     *
     * 现在的契约：入口照给、按钮照点，代价是必须把流量说清楚（`meteredWarning` 交给界面出确认弹窗）。
     * 决定权回到用户手里，而不是被一条为自动行为写的阈值替用户决定。
     */
    @Test
    fun `移动数据下仍给下载入口，但要带流量提醒`() {
        val notDownloaded = ModelAdvisor.advice(capable(metered = true), false, false, 0L)
        assertEquals(ModelAdvice.State.SUGGEST_INSTALL, notDownloaded.state)
        assertEquals(ModelAdvisor.REASON_NOT_INSTALLED, notDownloaded.reasonKey)
        assertTrue("移动数据 + 还没下载 ⇒ 必须提醒流量", notDownloaded.meteredWarning)
    }

    @Test
    fun `已同意但没下完时移动数据下也是给下载入口并提醒`() {
        val advice = ModelAdvisor.advice(capable(metered = true), false, true, 0L)
        assertEquals(ModelAdvice.State.NEEDS_DOWNLOAD, advice.state)
        assertTrue(advice.meteredWarning)
    }

    @Test
    fun `Wi-Fi 下不提醒流量`() {
        val advice = ModelAdvisor.advice(capable(metered = false), false, false, 0L)
        assertEquals(ModelAdvice.State.SUGGEST_INSTALL, advice.state)
        assertFalse("非计费网络却提醒流量 = 狼来了，用户很快就不信这句提醒", advice.meteredWarning)
    }

    @Test
    fun `模型已在本地时移动数据不构成任何提醒（推理不产生流量）`() {
        val downloaded = ModelAdvisor.advice(capable(metered = true), true, true, 700L)
        assertEquals(ModelAdvice.State.READY, downloaded.state)
        assertFalse(downloaded.meteredWarning)
    }

    @Test
    fun `流量提醒不许把电量和内存这两道闸一起放过`() {
        // metered 只放开"下载入口"这一件事；电量/空闲内存不达标仍然 WAIT——
        // 否则这条改动会把真正跑不动的机器也放行（下载完跑不起来比不下更糟）
        val battery = ModelAdvisor.advice(capable(metered = true, battery = 15), false, false, 0L)
        assertEquals(ModelAdvice.State.WAIT, battery.state)
        assertEquals(ModelAdvisor.REASON_BATTERY, battery.reasonKey)

        val ram = ModelAdvisor.advice(capable(metered = true, freeRamMb = 1024), false, false, 0L)
        assertEquals(ModelAdvice.State.WAIT, ram.state)
        assertEquals(ModelAdvisor.REASON_FREE_RAM, ram.reasonKey)
    }

    @Test
    fun `没有 arm64 引擎时连机型都不用看`() {
        val advice = ModelAdvisor.advice(capable(abi = false), false, false, 0L)
        assertEquals(ModelAdvice.State.UNSUPPORTED, advice.state)
        assertEquals(ModelAdvisor.REASON_ABI, advice.reasonKey)
    }
}
