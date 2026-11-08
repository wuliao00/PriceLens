package com.pricelens.ui.autotrack

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pricelens.data.local.entity.AutoTrackRunEntity
import com.pricelens.data.local.entity.PurchaseRecordEntity
import com.pricelens.data.repository.AutoTrackRepository
import com.pricelens.data.repository.AutoTrackState
import com.pricelens.data.repository.PriceProtectionRepository
import com.pricelens.data.repository.ProtectionState
import com.pricelens.data.repository.PurchaseDraft
import com.pricelens.data.repository.SettingsRepository
import com.pricelens.util.LogT
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 自动化中心 / 价保助手的 ViewModel（2026-11 UI 大改）。
 *
 * 职责边界：只做**状态映射与动作转发**，不复制任何业务规则——
 * 调度、查价、通知、价保金额全在 [AutoTrackRepository] / [PriceProtectionRepository]；
 * 本类把仓库的 StateFlow/Flow 暴露给 UI，并把操作串成协程。
 *
 * 为什么由 UI 自建 VM（而不是回填到 SearchViewModel）：自动追价与搜索是两条独立链路，
 * 它们共享的只是数据（目标/购买记录），不共享状态机；塞进 SearchViewModel 会让
 * "搜索的生命周期"和"自动追价的生命周期"互相牵连（验收里已经踩过同类坑）。
 */
@HiltViewModel
class AutoTrackViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val autoTrackRepository: AutoTrackRepository,
    private val protectionRepository: PriceProtectionRepository,
    settings: SettingsRepository
) : ViewModel() {

    /** 自动追价实时状态（开关/频率/上次/下次/目标/命中/运行中） */
    val autoTrack: StateFlow<AutoTrackState> = autoTrackRepository.state

    /** 价保助手实时状态（在盯笔数/可申请笔数与金额/最近检查/下次检查） */
    val protection: StateFlow<ProtectionState> = protectionRepository.state

    /** 最近自动追价轮次（最多 30 条；只留最近 500 条由 DAO 保留策略控制） */
    val runs: StateFlow<List<AutoTrackRunEntity>> = autoTrackRepository.observeRuns(30)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 全部购买记录（新买的在前） */
    val purchases: StateFlow<List<PurchaseRecordEntity>> = protectionRepository.observePurchases()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 仅 WiFi / 夜间暂停：仓库状态里没有这两项（它们只影响调度约束，不进 UI 状态契约），
     * 所以从设置里读一次、之后由本类维护本地镜像；写入仍然只经仓库（它会顺手重排下一轮）。
     */
    private val _wifiOnly = MutableStateFlow(settings.autoTrackWifiOnly)
    val wifiOnly: StateFlow<Boolean> = _wifiOnly

    private val _quietPause = MutableStateFlow(settings.autoTrackQuietPause)
    val quietPause: StateFlow<Boolean> = _quietPause

    /** 手动「立即运行」/「立即检查价保」的忙态（按钮禁用 + 进度条） */
    private val _working = MutableStateFlow(false)
    val working: StateFlow<Boolean> = _working

    /** 价保检查忙态：一次 checkAll 会对每条购买记录做同款搜索，可能持续数秒 */
    private val _protectionChecking = MutableStateFlow(false)
    val protectionChecking: StateFlow<Boolean> = _protectionChecking

    /** 记录购买的结果（UI 出 Toast 后调 [acknowledgePurchaseEvent] 清空） */
    private val _purchaseEvent = MutableStateFlow<PurchaseEvent?>(null)
    val purchaseEvent: StateFlow<PurchaseEvent?> = _purchaseEvent

    private var scheduledEnsured = false

    /**
     * 应用运行时补登记自动追价链 + 价保周期任务（幂等）。
     * 只在 VM 首次创建时调一次：MainActivity 每次重建 VM 都重登记没有意义，也浪费 WorkManager 查询。
     */
    fun ensureScheduled() {
        if (scheduledEnsured) return
        scheduledEnsured = true
        runCatching { autoTrackRepository.ensureScheduled() }
            .onFailure { LogT.w("自动追价：补登记调度失败：${it.javaClass.simpleName}") }
    }

    // ---------- 自动追价动作 ----------

    fun setEnabled(enabled: Boolean) = autoTrackRepository.setEnabled(enabled)

    fun setIntervalMinutes(minutes: Int) = autoTrackRepository.setIntervalMinutes(minutes)

    fun setWifiOnly(enabled: Boolean) {
        autoTrackRepository.setWifiOnly(enabled)
        _wifiOnly.value = enabled
    }

    fun setQuietPause(enabled: Boolean) {
        autoTrackRepository.setQuietPause(enabled)
        _quietPause.value = enabled
    }

    /** 「立即运行」：真跑一轮并重排下一轮；正在跑时由仓库合并（不排队重跑） */
    fun runNow() {
        if (_working.value) return
        _working.value = true
        viewModelScope.launch {
            try {
                autoTrackRepository.requestImmediateRun(appContext)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogT.w("自动追价：立即运行失败：${e.javaClass.simpleName}")
            } finally {
                _working.value = false
            }
        }
    }

    // ---------- 价保动作 ----------

    fun recordPurchase(draft: PurchaseDraft) {
        viewModelScope.launch {
            val id = try {
                protectionRepository.recordPurchase(draft)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogT.w("价保：记录购买失败：${e.javaClass.simpleName}")
                -1L
            }
            _purchaseEvent.value = if (id > 0) PurchaseEvent.Saved(id) else PurchaseEvent.Rejected
        }
    }

    fun markClaimed(id: Long) {
        viewModelScope.launch {
            runCatching { protectionRepository.markClaimed(id) }
                .onFailure { LogT.w("价保：标记已申请失败：${it.javaClass.simpleName}") }
        }
    }

    fun deletePurchase(id: Long) {
        viewModelScope.launch {
            runCatching { protectionRepository.deletePurchase(id) }
                .onFailure { LogT.w("价保：删除购买记录失败：${it.javaClass.simpleName}") }
        }
    }

    /** 「立即检查」：逐条拿同款最低可信价并重算可申请金额；拿不到价如实跳过（不编造） */
    fun checkProtectionNow() {
        if (_protectionChecking.value) return
        _protectionChecking.value = true
        viewModelScope.launch {
            try {
                protectionRepository.checkAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogT.w("价保：检查失败：${e.javaClass.simpleName}")
            } finally {
                _protectionChecking.value = false
            }
        }
    }

    fun acknowledgePurchaseEvent() {
        _purchaseEvent.value = null
    }
}

/** 记录购买的结果（UI 侧映射成 Toast/清空表单） */
sealed interface PurchaseEvent {
    data class Saved(val id: Long) : PurchaseEvent

    /** 参数非法或落库失败：如实说"没记上"，不假装成功 */
    data object Rejected : PurchaseEvent
}
