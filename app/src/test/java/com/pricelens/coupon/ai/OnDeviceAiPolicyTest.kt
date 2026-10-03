package com.pricelens.coupon.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OnDeviceAiPolicy] 的表驱动基线：七入一出的每条判据都要有"该放行的真放行"。
 *
 * 三条阈值边界（机型总内存 6144MB、电量 20%、AI 预算 600MB、空闲内存 2048MB）各有一条
 * **恰好等于阈值应放行**的独立用例，而下面的表格里刻意不出现这些取值 ——
 * 这样"把 `>=` 改成 `>`"的变异探针只会让对应那一条用例变红，归因不含混（任务书 D 第 4 条）。
 */
class OnDeviceAiPolicyTest {

    private val run: AiDecision = AiDecision.Run
    private val queue: AiDecision = AiDecision.QueueUntilIdle
    private val off: AiDecision = AiDecision.Refused(OnDeviceAiPolicy.ReasonOff)
    private val deviceRam: AiDecision = AiDecision.Refused(OnDeviceAiPolicy.ReasonDeviceRam)
    private val budget: AiDecision = AiDecision.Refused(OnDeviceAiPolicy.ReasonCacheBudget)

    /** 全绿输入：除了被考察的那一条，其余判据都留足余量 */
    private data class Args(
        val freeRamMb: Long = 4096,
        val batteryPercent: Int = 80,
        val onMeteredNetwork: Boolean = false,
        val modelDownloaded: Boolean = true,
        val userEnabled: Boolean = true,
        val deviceRamMb: Long = 12288,
        val cacheBudgetMb: Long = 1024
    )

    private data class Case(val name: String, val args: Args, val expected: AiDecision)

    private fun decide(a: Args): AiDecision = OnDeviceAiPolicy.eligible(
        freeRamMb = a.freeRamMb,
        batteryPercent = a.batteryPercent,
        onMeteredNetwork = a.onMeteredNetwork,
        modelDownloaded = a.modelDownloaded,
        userEnabled = a.userEnabled,
        deviceRamMb = a.deviceRamMb,
        cacheBudgetMb = a.cacheBudgetMb
    )

    @Test
    fun `nominal device with model on disk runs`() {
        // positive control：什么都没踩线时必须真的放行，否则下面所有拒绝用例都可能是假的。
        // userEnabled 这一条的放行正例就是这里（默认 true），表格不再重复一遍。
        assertEquals(run, decide(Args()))
    }

    @Test
    fun `each criterion maps to its documented decision`() {
        val cases = listOf(
            Case("用户开关默认关", Args(userEnabled = false), off),
            Case("4GB 机型永久拒绝", Args(deviceRamMb = 4096), deviceRam),
            Case("预算 30MB 装不下模型", Args(cacheBudgetMb = 30, modelDownloaded = false), budget),
            Case("预算为 0 等同没分配", Args(cacheBudgetMb = 0, modelDownloaded = false), budget),
            Case("电量 19% 等回血", Args(batteryPercent = 19), queue),
            Case("空闲内存 2047MB 等回血", Args(freeRamMb = 2047), queue),
            Case("计费网络且没模型等非计费网络", Args(modelDownloaded = false, onMeteredNetwork = true), queue),
            Case("开关关时不看电量", Args(userEnabled = false, batteryPercent = 3), off),
            Case("机型不达标时预算说了不算", Args(deviceRamMb = 3072, cacheBudgetMb = 0), deviceRam),
            Case("预算不够时电量再高也不下", Args(cacheBudgetMb = 599, batteryPercent = 99, modelDownloaded = false), budget),
            Case("低电量优先于计费网络", Args(batteryPercent = 8, modelDownloaded = false, onMeteredNetwork = true), queue),
            Case("8GB 机型放行", Args(deviceRamMb = 8192), run),
            Case("预算 601MB 够放模型", Args(cacheBudgetMb = 601, modelDownloaded = false), run),
            Case("电量 21% 放行", Args(batteryPercent = 21), run),
            Case("空闲 2049MB 放行", Args(freeRamMb = 2049), run),
            Case("计费网络但模型已在本地照样跑", Args(onMeteredNetwork = true, modelDownloaded = true), run),
            Case("非计费网络可以下模型", Args(onMeteredNetwork = false, modelDownloaded = false), run)
        )
        for (case in cases) {
            assertEquals("用例[${case.name}] 结论不符", case.expected, decide(case.args))
        }
    }

    @Test
    fun `battery exactly at the threshold is eligible`() {
        assertEquals(run, decide(Args(batteryPercent = OnDeviceAiPolicy.MinBatteryPercent)))
    }

    @Test
    fun `device ram exactly at the six gigabyte floor is eligible`() {
        assertEquals(run, decide(Args(deviceRamMb = OnDeviceAiPolicy.MinDeviceRamMb)))
    }

    @Test
    fun `ai budget exactly at the model soft cap is eligible for the download`() {
        val floorMb = OnDeviceAiPolicy.ModelBytesSoftCap / 1024 / 1024
        assertEquals(run, decide(Args(modelDownloaded = false, cacheBudgetMb = floorMb)))
    }

    @Test
    fun `free ram exactly at the floor is eligible`() {
        assertEquals(run, decide(Args(freeRamMb = OnDeviceAiPolicy.MinFreeRamMb)))
    }

    @Test
    fun `one below every floor is never a run`() {
        assertNotEquals(run, decide(Args(batteryPercent = 19)))
        assertNotEquals(run, decide(Args(deviceRamMb = 6143)))
        assertNotEquals(run, decide(Args(freeRamMb = 2047)))
        assertNotEquals(run, decide(Args(cacheBudgetMb = 599, modelDownloaded = false)))
    }

    @Test
    fun `cache budget only gates the download not an already installed model`() {
        // 已经下好的模型遇到预算被调小，不该把功能整个关掉（用户会看见"AI 突然坏了"）
        assertEquals(run, decide(Args(modelDownloaded = true, cacheBudgetMb = 30)))
        assertNotEquals(run, decide(Args(modelDownloaded = false, cacheBudgetMb = 30)))
    }

    @Test
    fun `permanent conditions are never expressed as a queue`() {
        // 排队语义的唯一用途是"等得到"：机型与用户开关都不随等待改变
        val fromWaiting = listOf(decide(Args(userEnabled = false, deviceRamMb = 2048)), decide(Args(deviceRamMb = 2048)))
        fromWaiting.forEach { assertTrue("永久性条件不许排队：$it", it is AiDecision.Refused) }
    }

    @Test
    fun `refused reasons are the documented wire strings`() {
        // 这三个字符串会被设置页文案与日志按字面匹配，改动等于改契约
        val reasons = listOf(
            decide(Args(userEnabled = false)),
            decide(Args(deviceRamMb = 1024)),
            decide(Args(modelDownloaded = false, cacheBudgetMb = 1))
        ).map { (it as AiDecision.Refused).reason }
        assertEquals(listOf("off", "device-ram", "cache-budget"), reasons)
    }

    @Test
    fun `out of range battery and ram values do not crash the gate`() {
        // 采集侧没 clamp 的越界值：只按"够不够"判定，不抛不吞
        assertEquals(queue, decide(Args(batteryPercent = -1)))
        assertEquals(run, decide(Args(batteryPercent = 200)))
        assertEquals(queue, decide(Args(freeRamMb = -1)))
    }
}
