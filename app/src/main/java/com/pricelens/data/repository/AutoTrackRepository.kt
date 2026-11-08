package com.pricelens.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.pricelens.data.local.AppDatabase
import com.pricelens.data.local.entity.AutoTrackRunEntity
import com.pricelens.domain.AutoTrackDecision
import com.pricelens.domain.AutoTrackPlanInput
import com.pricelens.domain.AutoTrackPolicy
import com.pricelens.util.LogT
import com.pricelens.worker.AutoTrackScheduler
import com.pricelens.worker.PriceProtectionWorker
import com.pricelens.worker.WatchCheckRunner
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * 自动追价中心对外状态（UI 直接 collect；字段名/含义是对 UI 的契约，不要改）。
 *
 * @param enabled 用户是否开启自动追价
 * @param intervalMinutes 用户选择的巡检间隔档位（30/60/120/180/360）
 * @param lastRunAtMs 最近一轮完成时刻；0 = 从没跑过
 * @param nextRunAtMs 下一轮预计时刻（链登记时写入；0 = 链未登记/已暂停；UI 文案须带「约」）
 * @param lastChecked 最近一轮拿到真现价的目标数
 * @param lastHits 最近一轮现价 ≤ 目标价的目标数
 * @param running 当前是否有一轮正在跑（UI 据此禁用「立即运行」按钮）
 * @param lastNote 最近一轮的一句话说明（失败源/暂停原因）；null = 还没有
 */
data class AutoTrackState(
    val enabled: Boolean,
    val intervalMinutes: Int,
    val lastRunAtMs: Long,
    val nextRunAtMs: Long,
    val lastChecked: Int,
    val lastHits: Int,
    val running: Boolean,
    val lastNote: String?
)

/**
 * 一轮自动追价的结果（对 UI/Worker 的契约）。
 *
 * @param checked 拿到真现价的目标数（直查 live + 同款搜索价；参考值不算）
 * @param hits 现价 ≤ 目标价的目标数（与通知闸门是否抑制无关）
 * @param failedSources 本轮失败/不可达的来源（人读标签，如 `["当当", "京东"]`）
 * @param note 一句话总结（UI 直接展示）
 */
data class AutoTrackOutcome(
    val checked: Int,
    val hits: Int,
    val failedSources: List<String>,
    val note: String
)

/**
 * 自动追价仓储（2026-11 自动追价 / 价保助手的数据层入口）。
 *
 * 职责边界：
 *  - **取数与通知不在这里**：一轮检查（直查 + 关键词同款搜索 + 曲线落点 + 降价通知闸门）
 *    全部复用 [WatchCheckRunner]，避免第二套查价/通知逻辑漂移（F1 的教训）；
 *  - 本类负责：轮次去重（[Mutex] + 运行中合并）、落轮次历史（`auto_track_runs`）、
 *    汇总状态（[AutoTrackState]）、自适应调度决策（[planNext]）与链登记（[applyPlan]/[ensureScheduled]）；
 *  - 设置读写都经 [SettingsRepository]（`auto_` 前缀），不裸取 SharedPreferences。
 *
 * 轮次互斥（真机「同一轮重复写曲线/重复发通知」的防线，共两层）：
 *  1. [WatchCheckRunner] 自己的 `roundMutex` + 60 秒内合并（服务/磁贴/兜底 Worker 都走它）；
 *  2. 本类的 [runMutex]：`tryLock` 失败时**合并**而不是排队重跑，保证 UI 连点/Worker 与手动
 *     同时触发时最多只有一轮真正执行。
 */
@Singleton
class AutoTrackRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val runner: WatchCheckRunner,
    private val db: AppDatabase
) {

    /** 轮次互斥：同一时刻最多一轮。tryLock 失败 = 合并本次触发（不排队重跑，避免连续两轮） */
    private val runMutex = Mutex()

    /** 最近一轮的结果（合并触发时原样返回，避免调用方以为"什么都没发生"） */
    @Volatile
    private var lastOutcome: AutoTrackOutcome? = null

    private val _state = MutableStateFlow(buildState(running = false))

    /** 自动追价实时状态（UI collect） */
    val state: StateFlow<AutoTrackState> = _state

    /** 运行历史（时间倒序，最多 [limit] 条） */
    fun observeRuns(limit: Int = 30): Flow<List<AutoTrackRunEntity>> = db.autoTrackRunDao().observeRecent(limit)

    // ---------- 对外动作 ----------

    /**
     * 立即执行一轮（自动追价链、UI「立即运行」、测试都走这里）。
     *
     * 若已有一轮在跑，**不排队**：返回上一轮结果（或空结果），日志里说明合并原因。
     * 网络/发通知等耗时工作在 IO 线程执行。
     */
    suspend fun runOnce(): AutoTrackOutcome {
        if (!runMutex.tryLock()) {
            LogT.i("自动追价：已有一轮在跑，本次触发合并（不重复查价/通知）")
            return lastOutcome ?: AutoTrackOutcome(0, 0, emptyList(), "已有一轮正在运行")
        }
        return try {
            runRoundLocked()
        } finally {
            runMutex.unlock()
        }
    }

    /** 开关自动追价：打开后立刻排一轮（用户马上看到反馈）；关闭则取消链并清预计时刻 */
    fun setEnabled(enabled: Boolean) {
        settings.autoTrackEnabled = enabled
        if (enabled) {
            AutoTrackScheduler.runNow(context, settings)
        } else {
            AutoTrackScheduler.cancel(context, settings)
        }
        refreshState()
    }

    /** 修改巡检间隔档位（非法值由 [AutoTrackPolicy.normalizeInterval] 归一到合法档；立即重排下一轮） */
    fun setIntervalMinutes(minutes: Int) {
        val normalized = AutoTrackPolicy.normalizeInterval(minutes)
        settings.autoTrackIntervalMinutes = normalized
        if (settings.autoTrackEnabled) {
            AutoTrackScheduler.scheduleNext(context, settings, normalized)
        }
        refreshState()
    }

    /** 仅 WiFi 下追价（改约束后重排，让 WorkManager 立刻按新约束等待） */
    fun setWifiOnly(enabled: Boolean) {
        settings.autoTrackWifiOnly = enabled
        if (settings.autoTrackEnabled) {
            AutoTrackScheduler.scheduleNext(context, settings, settings.autoTrackIntervalMinutes)
        }
        refreshState()
    }

    /** 夜间（免打扰时段）是否暂停追价 */
    fun setQuietPause(enabled: Boolean) {
        settings.autoTrackQuietPause = enabled
        refreshState()
    }

    /**
     * UI「立即运行」：真跑一轮并重排下一轮。
     *
     * 正在跑时直接忽略（不排 WorkManager 立即任务，避免"跑完马上又跑一轮"）；
     * @param context 调用方 Context（WorkManager 需要；本类自己也持有 ApplicationContext，
     *                这里保留参数是契约的一部分，内部优先用它，便于测试时传入不同 Context）
     */
    suspend fun requestImmediateRun(context: Context) {
        if (_state.value.running) {
            LogT.i("自动追价：正在运行，忽略本次「立即运行」请求（避免双轮次）")
            return
        }
        runOnce()
        applyPlan(planNext())
        LogT.i("自动追价：手动立即运行完成，已重排下一轮")
    }

    /** 应用启动/开机补登记：自适应链 + 价保周期任务（都幂等） */
    fun ensureScheduled() {
        AutoTrackScheduler.ensureChain(context, settings)
        PriceProtectionWorker.schedule(context)
        refreshState()
    }

    // ---------- 调度决策 ----------

    /**
     * 组装 [AutoTrackPlanInput] 并给出决策（Worker 排下一轮、启动补登记都调它）。
     *
     * 「最近目标距离」来自一条 SQL：每个活跃目标的最近一天曲线收盘价 vs 目标价；
     * 没有任何曲线点时为 null（未知，不假装 0 —— 0 会被误判成"已达标"从而永久 30 分钟一轮）。
     */
    suspend fun planNext(): AutoTrackDecision = withContext(Dispatchers.IO) {
        val rows = runCatching { db.targetPlanDao().activePriceRows() }
            .onFailure { LogT.w("自动追价：读目标计划行失败：${it.javaClass.simpleName}") }
            .getOrDefault(emptyList())
        val nearest = rows.mapNotNull { row ->
            val last = row.lastPrice?.takeIf { it.isFinite() && it > 0.0 } ?: return@mapNotNull null
            if (row.targetPrice <= 0.0) return@mapNotNull null
            (last - row.targetPrice) / row.targetPrice
        }.minOrNull()
        val calendar = Calendar.getInstance()
        val input = AutoTrackPlanInput(
            enabled = settings.autoTrackEnabled,
            intervalMinutes = settings.autoTrackIntervalMinutes,
            activeTargets = rows.size,
            nearestDistanceRatio = nearest,
            nowMinuteOfDay = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE),
            quietHoursEnabled = settings.quietHoursEnabled,
            quietPauseEnabled = settings.autoTrackQuietPause,
            quietStartMinute = settings.quietStartMinute,
            quietEndMinute = settings.quietEndMinute,
            onWifi = isOnWifi(),
            wifiOnly = settings.autoTrackWifiOnly,
            batteryLow = isBatteryLow(),
            lastRunAtMs = settings.autoTrackLastRunAtMs
        )
        AutoTrackPolicy.decideNext(input, System.currentTimeMillis()).also { decision ->
            LogT.i(
                "自动追价调度：shouldRun=${decision.shouldRun}，下一轮 ${decision.nextDelayMinutes} 分钟" +
                    "（${decision.reason}）"
            )
        }
    }

    /**
     * 执行调度决策：`nextDelayMinutes <= 0` = 链结束（取消 unique work），否则 REPLACE 排下一轮。
     * 预计时刻由 [AutoTrackScheduler] 写回设置，UI 直接读 [AutoTrackState.nextRunAtMs]。
     */
    fun applyPlan(decision: AutoTrackDecision) {
        if (decision.nextDelayMinutes <= 0) {
            AutoTrackScheduler.cancel(context, settings)
        } else {
            AutoTrackScheduler.scheduleNext(context, settings, decision.nextDelayMinutes)
        }
        refreshState()
    }

    /**
     * 链式 Worker 的一次执行：先决策"此刻适不适合跑"，不适合就只重排不跑；
     * 适合则跑一轮，完成后按新输入重排下一轮。
     */
    suspend fun runScheduled(): AutoTrackOutcome {
        val plan = planNext()
        if (!plan.shouldRun) {
            settings.autoTrackLastNote = plan.reason
            applyPlan(plan)
            _state.value = buildState(_state.value.running)
            return AutoTrackOutcome(0, 0, emptyList(), plan.reason)
        }
        val outcome = runOnce()
        applyPlan(planNext())
        return outcome
    }

    // ---------- 内部 ----------

    /** 一轮的完整执行：复用 WatchCheckRunner → 落历史 → 更新状态与 prefs */
    private suspend fun runRoundLocked(): AutoTrackOutcome = withContext(Dispatchers.IO) {
        _state.value = buildState(running = true)
        val now = System.currentTimeMillis()
        val outcome = try {
            val round = runner.runOnce(context)
            val note = noteOf(round)
            val failed = failedSourcesOf(round)
            persistRun(round, note, now)
            settings.autoTrackLastRunAtMs = now
            settings.autoTrackLastChecked = round.checked
            settings.autoTrackLastHits = round.hits
            settings.autoTrackLastNote = note
            AutoTrackOutcome(
                checked = round.checked,
                hits = round.hits,
                failedSources = failed,
                note = note
            )
        } catch (e: CancellationException) {
            _state.value = buildState(running = false)
            throw e
        } catch (e: Exception) {
            // 取数/通知层整体异常：如实记失败，绝不伪造成"0 个目标/全部正常"
            LogT.e("自动追价轮次失败：${e.javaClass.simpleName} ${e.message}", e)
            val note = "本轮失败：${e.javaClass.simpleName}"
            settings.autoTrackLastNote = note
            AutoTrackOutcome(0, 0, listOf("自动追价轮次异常"), note)
        }
        lastOutcome = outcome
        _state.value = buildState(running = false)
        LogT.i("自动追价轮次：${outcome.note}")
        outcome
    }

    /** 落轮次历史 + 顺手裁剪到保留上限（失败只记日志，不影响本轮结果） */
    private suspend fun persistRun(round: WatchCheckRunner.Outcome, note: String, atMs: Long) {
        runCatching {
            db.autoTrackRunDao().insert(
                AutoTrackRunEntity(
                    atMs = atMs,
                    targets = round.total,
                    checked = round.checked,
                    hits = round.hits,
                    note = note
                )
            )
            db.autoTrackRunDao().trimToLatest(AutoTrackRunEntity.MAX_KEPT_RUNS)
        }.onFailure {
            LogT.e("自动追价轮次历史写入失败（不影响本轮结果）：${it.javaClass.simpleName}", it)
        }
    }

    /** 一轮的一句话总结（含失败源；UI 直接展示，中文短句） */
    private fun noteOf(round: WatchCheckRunner.Outcome): String {
        if (round.total <= 0) return "没有可追价的目标"
        val parts = mutableListOf<String>()
        parts += "目标 ${round.total}"
        parts += "查到价 ${round.checked}"
        parts += "命中 ${round.hits}"
        if (round.keywordSearched > 0) parts += "同款搜索 ${round.keywordSearched}"
        if (round.triggered > 0) parts += "已提醒 ${round.triggered}"
        val failed = failedSourcesOf(round)
        if (failed.isNotEmpty()) parts += "失败源 ${failed.joinToString("、")}"
        return parts.joinToString("，")
    }

    /** 失败来源的人读标签：直查平台失败 + 关键词搜索源失败（去重保序） */
    private fun failedSourcesOf(round: WatchCheckRunner.Outcome): List<String> {
        val labels = LinkedHashSet<String>()
        round.failedPlatforms.forEach { labels += platformLabel(it) }
        labels.addAll(round.keywordFailedSources)
        return labels.toList()
    }

    private fun platformLabel(platform: String): String = when (platform) {
        "jd" -> "京东"
        "taobao" -> "淘宝天猫"
        "pdd" -> "拼多多"
        "dangdang" -> "当当"
        "shihuo" -> "识货"
        "smzdm" -> "值得买"
        else -> platform
    }

    private fun refreshState() {
        _state.value = buildState(_state.value.running)
    }

    private fun buildState(running: Boolean): AutoTrackState = AutoTrackState(
        enabled = settings.autoTrackEnabled,
        intervalMinutes = settings.autoTrackIntervalMinutes,
        lastRunAtMs = settings.autoTrackLastRunAtMs,
        nextRunAtMs = settings.autoTrackNextRunAtMs,
        lastChecked = settings.autoTrackLastChecked,
        lastHits = settings.autoTrackLastHits,
        running = running,
        lastNote = settings.autoTrackLastNote.takeIf { it.isNotBlank() }
    )

    /** 是否 WiFi；查询异常按"是"（宁多跑一轮也不因权限/瞬时异常停掉追价，与 WatchCheckRunner 同口径） */
    private fun isOnWifi(): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false
    }.getOrDefault(true)

    /**
     * 是否低电量且未充电。
     *
     * 取不到电量（部分机型 BATTERY_PROPERTY_CAPACITY 返回 -1）时按"不低"处理：
     * 这只是降频优化，不能因为读不到电池就把自动追价永久停掉。
     */
    private fun isBatteryLow(): Boolean = runCatching {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return@runCatching false
        if (manager.isCharging) return@runCatching false
        val capacity = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        capacity in 0..LOW_BATTERY_PERCENT
    }.getOrDefault(false)

    private companion object {
        /** 低于这个电量（且未充电）就降频：30 分钟 → 至少 180 分钟（省电优先） */
        const val LOW_BATTERY_PERCENT = 20
    }
}
