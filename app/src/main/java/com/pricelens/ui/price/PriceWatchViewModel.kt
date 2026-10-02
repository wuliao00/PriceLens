package com.pricelens.ui.price

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pricelens.data.local.entity.PriceTargetEntity
import com.pricelens.data.local.entity.WatchIdentityEntity
import com.pricelens.data.repository.PriceRepository
import com.pricelens.data.repository.SettingsRepository
import com.pricelens.domain.ExistingTarget
import com.pricelens.domain.SavePlan
import com.pricelens.domain.WatchDecision
import com.pricelens.domain.WatchTargetPolicy
import com.pricelens.worker.WatchCheckRunner
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 盯价目标 ViewModel（阶段2：从上帝 MainViewModel 拆出）。
 *
 * 2026-09 盯价链路修复（A3）：
 *  - 不再硬编码 `platform="jd"` / `"jd:"+sku`：入库的 platform + productId 全部由
 *    [WatchTargetPolicy] 从候选的 SKU/链接推导，非京东候选不可能再被写成京东目标。
 *  - 入库前校验 productId（拒绝 `"jd:"` 这类空 SKU 主键），杜绝第二个目标 REPLACE
 *    静默覆盖第一个目标——即用户反馈的"跟踪错误"。
 *  - 同 ID 不同标题时不静默覆盖：先产出 [pendingOverwrite]，由 UI 让用户确认。
 *  - 暴露 [lastRound]（含"因无查价通道/取不到价被跳过的目标数"），UI 如实反馈而非永远静默。
 */
@HiltViewModel
class PriceWatchViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: PriceRepository,
    private val settings: SettingsRepository,
    private val runner: WatchCheckRunner
) : ViewModel() {

    private val targets = MutableStateFlow<List<PriceTargetEntity>>(emptyList())

    /**
     * 进行中的盯价目标。VM 存活期间持续订阅 Room 流，
     * 因此保存前读到的主键占用情况一定是最新的（旧实现依赖 Lazy 共享，
     * 用户没开过"我的"页时快照为空，防撞会失效）。
     */
    val watchTargets: StateFlow<List<PriceTargetEntity>> = targets

    private val _identities = MutableStateFlow<List<WatchIdentityEntity>>(emptyList())

    /** §免凭证曲线：浮窗「就是这个商品」确认过的身份列表（盯价页管理入口） */
    val identities: StateFlow<List<WatchIdentityEntity>> = _identities

    private val _identityDays = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** productId → 已记天数；随身份表变更重算（小表，本机查询） */
    val identityDays: StateFlow<Map<String, Int>> = _identityDays

    init {
        viewModelScope.launch {
            repository.observeTargets().collect { targets.value = it }
        }
        // C1：两个订阅必须是两条独立协程。observeTargets() 是 Room 的实时流，collect 永不返回，
        // 与它串在同一条协程里的 observeIdentities().collect{} 因此是死代码 ——
        // 后果：盯价页的「浮窗确认的本机身份」区永远不出现（身份表其实有数据）。
        // 声明也必须挪到 init 之前：ViewModel 的属性按顺序初始化，init 里启动的协程
        // 可能先于 _identities 赋值就被调度到。
        viewModelScope.launch {
            repository.observeIdentities().collect { list ->
                _identities.value = list
                _identityDays.value = list.associate { it.productId to repository.identityDays(it.productId) }
            }
        }
    }

    /** 取消确认：删身份连带删它的日点（口径见 OverlayCurveRecorder.cancel） */
    fun cancelIdentity(productId: String) {
        viewModelScope.launch { repository.deleteIdentity(productId) }
    }

    /** 最近一轮盯价检查（total / checked / 达标 / 各类跳过数） */
    val lastRound: StateFlow<WatchCheckRunner.RoundSummary?> = runner.lastRound

    /** 永远不会被检查到的历史遗留目标（空 SKU 主键或无查价通道） */
    val untrackableTargets: StateFlow<List<PriceTargetEntity>> = targets
        .map { list -> list.filter { e -> !WatchTargetPolicy.isTrackableTarget(e.productId, e.platform) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _feedback = MutableStateFlow<WatchFeedback?>(null)

    /** 保存结果（UI 弹 Toast 后调 [acknowledgeFeedback] 清空，避免重复提示） */
    val feedback: StateFlow<WatchFeedback?> = _feedback

    private val _pendingOverwrite = MutableStateFlow<PendingOverwrite?>(null)

    /** 同 ID 不同标题：等待用户确认的覆盖动作 */
    val pendingOverwrite: StateFlow<PendingOverwrite?> = _pendingOverwrite

    /** 手动检查进行中（"立即检查一次"按钮的忙态） */
    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking

    /** 京东查价是否只剩星罗 API Key 这一条路（p.3.cn 公开域名已下线） */
    fun jdCredentialMissing(): Boolean = settings.linkstarsApiKey.isBlank()

    fun removeTarget(productId: String) {
        viewModelScope.launch { repository.deactivateTarget(productId) }
    }

    /** 立即跑一轮检查：把"盯了没反应"变成用户能当场验证的动作 */
    fun checkNow() {
        if (_checking.value) return
        _checking.value = true
        viewModelScope.launch {
            try {
                runner.runOnce(appContext)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // 取消透传：不把 VM 销毁写成检查失败
            } catch (e: Exception) {
                com.pricelens.util.LogT.w("手动盯价检查失败：${e.javaClass.simpleName}")
            } finally {
                _checking.value = false
            }
        }
    }

    /**
     * 设定/更新盯价目标。[decision] 必须由 UI 用
     * [WatchTargetPolicy.decide] 得到——不可盯的候选在这里也会被再次拒绝，
     * 保证没有任何入口能绕过校验。
     */
    fun setTarget(decision: WatchDecision, title: String, targetPrice: Double) {
        if (targetPrice.isNaN() || targetPrice.isInfinite() || targetPrice <= 0) {
            _feedback.value = WatchFeedback.InvalidTargetPrice
            return
        }
        when (decision) {
            is WatchDecision.Rejected -> {
                _feedback.value = WatchFeedback.Rejected(decision)
                return
            }
            is WatchDecision.Watchable -> {
                val plan = WatchTargetPolicy.planSave(existingSnapshot(), decision, title)
                when (plan) {
                    is SavePlan.Create -> write(decision, title, targetPrice, updated = false)
                    is SavePlan.UpdateSameProduct -> write(decision, title, targetPrice, updated = true)
                    is SavePlan.NeedConfirm -> _pendingOverwrite.value = PendingOverwrite(
                        decision = decision,
                        title = title,
                        targetPrice = targetPrice,
                        existingTitle = plan.existingTitle
                    )
                    is SavePlan.Blocked -> _feedback.value = WatchFeedback.BlockedByInvalidId
                }
            }
        }
    }

    /** 用户确认覆盖同 ID 的旧目标 */
    fun confirmOverwrite() {
        val pending = _pendingOverwrite.value ?: return
        _pendingOverwrite.value = null
        write(pending.decision, pending.title, pending.targetPrice, updated = true)
    }

    fun dismissOverwrite() {
        _pendingOverwrite.value = null
    }

    fun acknowledgeFeedback() {
        _feedback.value = null
    }

    // ---------- 内部 ----------

    private fun write(decision: WatchDecision.Watchable, title: String, targetPrice: Double, updated: Boolean) {
        // 双重保险：主键形态非法一律不入库（不让 "jd:" 这种 id 再进 REPLACE 分支）
        if (!WatchTargetPolicy.isValidTargetId(decision.productId)) {
            _feedback.value = WatchFeedback.BlockedByInvalidId
            return
        }
        val previous = targets.value.firstOrNull { it.productId == decision.productId }
        val entity = PriceTargetEntity(
            productId = decision.productId,
            title = title.ifBlank { decision.productId },
            platform = decision.platform,
            targetPrice = targetPrice,
            active = true,
            createdAt = previous?.createdAt ?: System.currentTimeMillis()
        )
        viewModelScope.launch {
            repository.setTarget(entity)
            _feedback.value = WatchFeedback.Saved(
                productId = entity.productId,
                updated = updated,
                targetPrice = targetPrice
            )
        }
    }

    private fun existingSnapshot(): List<ExistingTarget> = targets.value.map {
        ExistingTarget(productId = it.productId, title = it.title, active = it.active)
    }

    /** 待确认的覆盖动作：同 productId 但标题像是两个不同商品 */
    data class PendingOverwrite(
        val decision: WatchDecision.Watchable,
        val title: String,
        val targetPrice: Double,
        val existingTitle: String
    )
}

/** 保存盯价目标的结果（UI 侧映射成具体文案） */
sealed interface WatchFeedback {
    data class Saved(val productId: String, val updated: Boolean, val targetPrice: Double) : WatchFeedback

    /** 候选不可盯：带上被拒绝的原因与平台，UI 出具体说明 */
    data class Rejected(val rejection: WatchDecision.Rejected) : WatchFeedback

    /** productId 形态非法（旧版本遗留路径 / 手工调用） */
    data object BlockedByInvalidId : WatchFeedback

    data object InvalidTargetPrice : WatchFeedback
}
