package com.pricelens.coupon.ai

/**
 * 端侧 AI 的准入结论。三档语义互斥，且**可恢复性不同**（这是分档的唯一理由）：
 *  - [Run]：现在就跑。
 *  - [QueueUntilIdle]：条件是可变的（电量、空闲内存、网络），等到位再跑，别对用户说不行。
 *  - [Refused]：结论不随等待改变（用户没开、机器不达标、预算装不下），要么放弃要么改配置。
 *
 * 两档"不跑"必须区分开：把"电量低"报成"你的设备不支持"，用户会以为功能对他是关着的，
 * 于是要么不再重试、要么去反馈"功能坏了"；把"机型不达标"报成"稍后再试"，则会让上层
 * 无休止地排队。[Refused.reason] 是给日志与设置页文案的键，取值见本文件的 Reason 常量。
 */
sealed interface AiDecision {

    /** 允许下载（在 Wi-Fi 上）并允许推理 */
    object Run : AiDecision

    /** 本次不跑，但结论会随电量/内存/网络变化；上层应挂起并等条件满足后重试 */
    object QueueUntilIdle : AiDecision

    /** 本次不跑，且等待不会改变结论 */
    data class Refused(val reason: String) : AiDecision
}

/**
 * "能不能上机"的纯策略。全部判据只吃 7 个标量，不 import android.*，
 * 所以采集侧（`ActivityManager.getMemoryInfo`、`BatteryManager`、`ConnectivityManager`）
 * 可以留给后续任务，而判据本身今天就能被单测钉死。
 *
 * 判据顺序（顺序本身就是行为规格，改动要连带改测试）：
 *  1. 用户开关 → 2. 机型总内存 → 3. 缓存预算 → 4. 电量 → 5. 空闲内存 → 6. 计费网络。
 * 永久性拒绝排在暂时性排队**之前**：机型不达标或预算装不下时，排队到什么时候结论都一样，
 * 先把话说死，免得上层挂一个永远等不到的队列。
 *
 * 为什么这一版本机只能交付策略、交付不了模型：没有可编译的 .so、没有能验机的设备，
 * 任何 `System.loadLibrary` 与权重资产写进来都是"把没验过的东西写成完成"（详见 docs/端侧AI兜底.md）。
 */
object OnDeviceAiPolicy {

    /**
     * 模型字节软上限：600MB。
     *
     * 量级推算：Q4_K_M 每权重约 4.8bit ≈ 0.6 字节，600MB ≈ **1B 参数**那一档
     * （Qwen/Llama 的 0.5B~1.5B 小模型）。选它而不是 3B（约 1.8GB）的理由是任务形状：
     * 兜底要的是"从一句券文案里抽 2~3 个金额槽位 + 状态词"，且输出被 coupon_schema.gbnf
     * 逐token约束死 —— 窄任务 + 硬语法约束，小模型的边际收益本来就低，
     * 而 3B 多出来的 1.2GB 要同时付 APK 之外的存储、下载流量、以及常驻内存三笔账。
     * 等 golden set 大到能量化"1B vs 3B 的槽位准确率差"时再抬这个上限。
     */
    const val ModelBytesSoftCap = 600L * 1024 * 1024

    /** 机型总内存下限 6GB：低于它的中长尾机型**永久**降级回规则（这是特性不是缺陷） */
    const val MinDeviceRamMb = 6144L

    /** 空闲内存下限 2GB：用绝对值而不是比例，理由见 [eligible] 的 freeRamMb 注释 */
    const val MinFreeRamMb = 2048L

    /** 电量下限 20%：比 Android 省电模式默认的 15% 更保守（prefill 是持续多核高负载） */
    const val MinBatteryPercent = 20

    /** 判据 1：用户没开（默认关） */
    const val ReasonOff = "off"

    /** 判据 2：机型总内存不达标，永久 */
    const val ReasonDeviceRam = "device-ram"

    /** 判据 3：AI 缓存预算装不下模型，永久（需用户改预算或清理，等待无效） */
    const val ReasonCacheBudget = "cache-budget"

    /** MB→字节，仅用于把 [ModelBytesSoftCap] 换算成预算档位，避免两处各写一遍 1024*1024 */
    private const val BytesPerMb = 1024L * 1024L

    /**
     * 唯一判定入口。参数不做默认值：调用方（控制面）必须显式交出七个量，
     * 少交一个都会让"这条判据到底生效没有"变成不可判定。
     *
     * @param freeRamMb 当前**空闲**内存（MB）。低于 2GB 时 mmap 的权重页会被低内存 killer
     *   反复回收，表现是 prefill 从秒级退化到几十秒并把前台应用挤掉；这一条是暂时性的，
     *   所以走 [QueueUntilIdle] 而不是拒绝。
     * @param batteryPercent 0..100。采集侧不必预先 clamp：本判据只关心"是否低于阈值"，
     *   越界值（-1 / 200）落在哪一侧都由阈值语义直接决定，不做二次加工。
     * @param onMeteredNetwork 按流量计费的网络（移动数据、热点）。**只挡下载，不挡推理**：
     *   模型已在本地时，跑一次抽取不产生任何流量，所以这条判据带 [modelDownloaded] 前缀。
     *   这条判据的语义是"App 不许自己偷偷用用户的流量下 397MB"，**不是**"用户不许下"：
     *   设置页那层（`ModelAdvisor.advice`）在其余条件都够时会把它降级成"给下载入口 + 明说走流量、
     *   点确认才下"。所以这里保持返回 [AiDecision.QueueUntilIdle]，把"要不要替用户决定"
     *   留给唯一有权决定的那一层（用户本人），而不是在本层替他否掉。
     * @param modelDownloaded 权重是否已完整落盘。false 时还需要预算与网络两个条件放行下载。
     * @param userEnabled 设置页开关。默认关：端侧模型是本 App 第一次在用户手机上跑别人的权重，
     *   收益没量化、体积不小、且"凭证绝不出库"之外还要回答"商品文案出库到哪张表"——
     *   这些没定论之前，默认关是唯一诚实的取值（任务书 D 第 2 条）。
     * @param deviceRamMb 机型**总**内存（MB）。低于 6GB 永久拒绝：600MB 权重 + KV/运行时
     *   峰值约 1.2~1.5GB，再扣掉系统与前台应用，在 4GB 机型上是"每次用完必掉进程"，
     *   那种体验比没有 AI 更糟。降级回规则的结果对用户仍然可用。
     * @param cacheBudgetMb 用户为 **AI 模型**单独划的下载预算（MB），不是既有的 30MB 分级缓存上限。
     *   取舍写在这里，别让人误读成"30MB 上限被突破了"：
     *   30MB 那档管的是**可丢弃的用户数据缓存**（商品快照、搜索记录），它的价值在数据本身，
     *   所以必须严控；模型权重是**可再生的下载物**，删了随时能再下，里面没有任何用户数据。
     *   因此本版把两者拆成两个预算：AI 预算独立、默认 0（等于不开）、只在用户显式同意后分配，
     *   并且约定三件事 —— 权重绝不进 Room、绝不进备份 payload（对齐 BackupPayload 的白名单红线）、
     *   EMERGENCY 清理时优先删除。代价说清楚：多一个用户要理解的数字，换来"缓存涨到 600MB"
     *   这件事不再由 App 替用户决定。
     */
    fun eligible(
        freeRamMb: Long,
        batteryPercent: Int,
        onMeteredNetwork: Boolean,
        modelDownloaded: Boolean,
        userEnabled: Boolean,
        deviceRamMb: Long,
        cacheBudgetMb: Long
    ): AiDecision {
        if (!userEnabled) return AiDecision.Refused(ReasonOff)
        if (!deviceRamSupported(deviceRamMb)) return AiDecision.Refused(ReasonDeviceRam)
        if (!modelDownloaded && !budgetFitsModel(cacheBudgetMb)) return AiDecision.Refused(ReasonCacheBudget)
        if (!batterySupported(batteryPercent)) return AiDecision.QueueUntilIdle
        if (!freeRamSupported(freeRamMb)) return AiDecision.QueueUntilIdle
        if (!modelDownloaded && onMeteredNetwork) return AiDecision.QueueUntilIdle
        return AiDecision.Run
    }

    // 下面四条判据都写成"正向支持"谓词：阈值边界是 `>=`，变异探针（把 >= 改成 >）
    // 只会让"恰好等于阈值应当放行"那一条用例变红，归因不含混。

    /** 判据 2 的实现：6144MB 本身算达标（6GB 机型是本 App 支持端侧推理的最低配） */
    private fun deviceRamSupported(deviceRamMb: Long): Boolean = deviceRamMb >= MinDeviceRamMb

    /** 判据 3 的实现：预算至少要能放下软上限的模型，否则下完就是半截文件 */
    private fun budgetFitsModel(cacheBudgetMb: Long): Boolean = cacheBudgetMb >= ModelBytesSoftCap / BytesPerMb

    /** 判据 4 的实现：20% 本身算够 */
    private fun batterySupported(batteryPercent: Int): Boolean = batteryPercent >= MinBatteryPercent

    /** 判据 5 的实现：2048MB 本身算够 */
    private fun freeRamSupported(freeRamMb: Long): Boolean = freeRamMb >= MinFreeRamMb
}
