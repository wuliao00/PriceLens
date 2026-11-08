package com.pricelens.data.repository

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.pricelens.R
import com.pricelens.data.local.AppDatabase
import com.pricelens.data.local.entity.PurchaseRecordEntity
import com.pricelens.domain.PriceProtectionPolicy
import com.pricelens.domain.ProtectionPlatform
import com.pricelens.domain.ProtectionStatus
import com.pricelens.domain.ProtectionWindowState
import com.pricelens.util.LogT
import com.pricelens.util.PriceFormatter
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 价保助手实时状态（UI 直接 collect；字段名/含义是对 UI 的契约，不要改）。
 *
 * @param watchCount 正在盯的购买记录数（未点「已申请」的全部记录，含无价保平台）
 * @param claimableCount 当前可申请价保的笔数
 * @param claimableYuan 当前可申请金额合计（元）
 * @param lastCheckAtMs 最近一次价保检查完成时刻；0 = 从没查过
 * @param nextCheckAtMs 下次预计检查时刻（周期任务按 [PRICE_PROTECTION_PERIOD_MS] 估算）；0 = 未排期
 */
data class ProtectionState(
    val watchCount: Int,
    val claimableCount: Int,
    val claimableYuan: Double,
    val lastCheckAtMs: Long,
    val nextCheckAtMs: Long
)

/**
 * 购买记录草稿（UI 录入表单 → [PriceProtectionRepository.recordPurchase]）。
 *
 * @param platform 平台名（jd/jd_third/taobao/pdd/vipshop/dangdang/…；也接受"京东/天猫/京东第三方"这类别名，落库前归一）
 * @param productId 平台商品号（可空：只凭标题也能做关键词搜索）
 * @param title 商品标题（价保列表展示 + 关键词搜索来源）
 * @param price 实付价（元，>0）
 * @param purchasedAtMs 购买时刻（毫秒；≤0 时按录入时刻算）；也是价保窗口的默认起算点
 * @param url 商品链接（可空）
 * @param windowDays 价保窗口覆盖（2026-11 规则表允许 UI 选择 7/15/30/90/180/365 等档位）：
 *   null = 用平台默认档；0 = 用户明确"该商品无价保"；>0 = 指定天数
 */
data class PurchaseDraft(
    val platform: String,
    val productId: String?,
    val title: String,
    val price: Double,
    val purchasedAtMs: Long,
    val url: String?,
    val windowDays: Int? = null
)

/**
 * 一次价保检查的结果。
 *
 * @param checked 真正拿到可信现价的记录数
 * @param newClaimable 本次**新**判定为可申请（且已发通知）的笔数
 * @param details 人读明细（取不到价/无价保/已提醒过等；≤8 条，UI 可原样展示）
 */
data class ProtectionCheckResult(
    val checked: Int,
    val newClaimable: Int,
    val details: List<String>
)

/**
 * 价保助手仓储（2026-11 自动追价大更新 · 买后链路）。
 *
 * 职责：
 *  - [recordPurchase] 记录购买（平台/标题/实付价/购买时间/可选窗口覆盖），价保窗口按
 *    [ProtectionPlatform] 规则表计算（京东自营 7/15/30/90/180/365、第三方 168/360/720/2160 小时、
 *    天猫 15/30、拼多多 7/30/72h、唯品会 10、其他 7 天并提示以平台为准）；
 *  - [checkAll] 用自动搜索拿「当前同款最低可信价」（[KeywordPriceSearch.findLowestTrustedPrice]，
 *    拿不到就如实"未知"，绝不落 0/旧价），套 [PriceProtectionPolicy.evaluate] 算预计可退金额/截止日；
 *  - 可申请时发**纯本机**通知（可关闭：设置 `auto_protection_enabled` 或系统通知渠道）：
 *    同一购买记录同一「降价档位」只提醒一次（`notifiedYuan` 记档），价格继续跌到更高档且间隔
 *    ≥12h 才再提醒（多档可多次申请）；窗口剩余 ≤24h 时通知升级为高优先级（"最后一天"）；
 *  - 无价保平台（窗口被覆盖为 0）仍展示价格变化，但结论明确写「无价保」；
 *  - 金额是**诚实简化版**：名义差额 = 实付 − 当前同款最低可信价，文案写明
 *    "实际以平台扣除已退/赠品/返卡后为准"，不编造平台逐项公式。
 *
 * 状态 [ProtectionState] 由购买记录 Flow + 上次检查时刻聚合，UI collect 即自动刷新。
 */
@Singleton
class PriceProtectionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val keywordPriceSearch: KeywordPriceSearch,
    private val db: AppDatabase,
    private val applicationScope: CoroutineScope
) {

    private val _state = MutableStateFlow(
        ProtectionState(
            watchCount = 0,
            claimableCount = 0,
            claimableYuan = 0.0,
            lastCheckAtMs = settings.autoProtectionLastCheckAtMs,
            nextCheckAtMs = nextCheckAt(settings.autoProtectionLastCheckAtMs)
        )
    )

    /** 价保助手实时状态（UI collect） */
    val state: StateFlow<ProtectionState> = _state

    init {
        // 购买记录一变（新增/标记已申请/删除），首页价保卡片的数字立即刷新
        applicationScope.launch {
            db.purchaseRecordDao().observeAll().collect { records ->
                _state.value = stateOf(records, System.currentTimeMillis())
            }
        }
    }

    /** 全部购买记录（新买的在前） */
    fun observePurchases(): Flow<List<PurchaseRecordEntity>> = db.purchaseRecordDao().observeAll()

    /** 价保助手开关（纯本机功能，可关闭）；关闭后 [checkAll] 立即返回且不发通知 */
    fun setEnabled(enabled: Boolean) {
        settings.autoProtectionEnabled = enabled
        refreshState()
    }

    /**
     * 记录一笔购买。
     *
     * @return 新记录主键；参数非法（标题空/价格非正）返回 -1 且不落库（UI 侧已拦截，这里是双保险）
     */
    suspend fun recordPurchase(draft: PurchaseDraft): Long = withContext(Dispatchers.IO) {
        val title = draft.title.trim().take(120)
        if (title.isEmpty() || !draft.price.isFinite() || draft.price <= 0.0) {
            LogT.w("价保：购买记录参数非法（title 空或价格非正），拒绝落库")
            return@withContext -1L
        }
        val platform = normalizePlatform(draft.platform)
        val purchasedAt = draft.purchasedAtMs.takeIf { it > 0L } ?: System.currentTimeMillis()
        val requestedDays = draft.windowDays
        val overrideDays = when {
            requestedDays == null -> 0 // 0 = 用平台默认档
            requestedDays <= 0 -> NO_PROTECTION_OVERRIDE // -1 = 用户明确"该商品无价保"
            else -> requestedDays.coerceIn(1, 365)
        }
        val ruleDays = PriceProtectionPolicy.windowDaysFor(platform, requestedDays)
        val ruleHours = PriceProtectionPolicy.windowHoursFor(platform, requestedDays)
        val deadline = PriceProtectionPolicy.deadlineOf(
            PriceProtectionPolicy.windowStartAtMs(platform, purchasedAt),
            ruleDays,
            ruleHours
        )
        val id = db.purchaseRecordDao().insert(
            PurchaseRecordEntity(
                platform = platform,
                productId = draft.productId?.trim()?.takeIf { it.isNotEmpty() },
                title = title,
                price = draft.price,
                purchasedAtMs = purchasedAt,
                url = draft.url?.trim()?.takeIf { it.isNotEmpty() },
                claimDeadlineMs = deadline,
                windowDaysOverride = overrideDays
            )
        )
        LogT.i(
            "价保：已记录购买「$title」¥${draft.price}（id=$id，平台=$platform，" +
                "窗口 ${ruleDays}天/${ruleHours}小时，截止 $deadline）"
        )
        id
    }

    /**
     * 检查全部未申请的购买记录：拿当前同款最低可信价 → 算价保 → 更新记录 → 可申请时发本机通知。
     *
     * 跳过：已点「已申请」（`claimedAtMs > 0`）的记录（不再查，省流量也不重复提醒）。
     * 幂等：`notifiedAtMs` + `notifiedYuan` 记「上次提醒档位」，同档不重复；更高档且间隔 ≥12h 才再提醒。
     */
    suspend fun checkAll(): ProtectionCheckResult = withContext(Dispatchers.IO) {
        if (!settings.autoProtectionEnabled) {
            return@withContext ProtectionCheckResult(0, 0, listOf("价保助手已关闭"))
        }
        val now = System.currentTimeMillis()
        val purchases = db.purchaseRecordDao().getAllOnce()
        if (purchases.isEmpty()) {
            settings.autoProtectionLastCheckAtMs = now
            refreshState()
            return@withContext ProtectionCheckResult(0, 0, listOf("还没有购买记录"))
        }
        var checked = 0
        var newClaimable = 0
        val details = mutableListOf<String>()
        for (record in purchases) {
            if (record.claimedAtMs > 0L) continue
            val outcome = checkOne(record, now, details)
            if (outcome.checked) checked++
            if (outcome.newClaimable) newClaimable++
        }
        settings.autoProtectionLastCheckAtMs = now
        refreshState()
        ProtectionCheckResult(checked, newClaimable, details.take(MAX_DETAILS))
    }

    /** 标记「已申请」：只改标记，保留金额与截止日（历史事实） */
    suspend fun markClaimed(id: Long) = withContext(Dispatchers.IO) {
        db.purchaseRecordDao().markClaimed(id, System.currentTimeMillis())
        refreshState()
    }

    /** 删除一条购买记录 */
    suspend fun deletePurchase(id: Long) = withContext(Dispatchers.IO) {
        db.purchaseRecordDao().delete(id)
        refreshState()
    }

    // ---------- 内部 ----------

    /** 单条记录的检查结果（checked = 拿到可信现价；newClaimable = 本轮新发了提醒） */
    private data class OneCheckOutcome(val checked: Boolean, val newClaimable: Boolean)

    /**
     * 检查一条记录。
     */
    private suspend fun checkOne(record: PurchaseRecordEntity, now: Long, details: MutableList<String>): OneCheckOutcome {
        val shortTitle = record.title.take(18)
        val rule = ProtectionPlatform.of(record.platform)
        // entity 的 windowDaysOverride：0 = 平台默认；-1 = 用户明确无价保；>0 = 指定天数
        val windowOverride: Int? = when {
            record.windowDaysOverride > 0 -> record.windowDaysOverride
            record.windowDaysOverride < 0 -> 0
            else -> null
        }
        val windowDays = PriceProtectionPolicy.windowDaysFor(record.platform, windowOverride)
        val windowHours = PriceProtectionPolicy.windowHoursFor(record.platform, windowOverride)
        val windowStart = PriceProtectionPolicy.windowStartAtMs(record.platform, record.purchasedAtMs)
        val trusted = keywordPriceSearch.findLowestTrustedPrice(
            platform = record.platform,
            productId = record.productId,
            title = record.title,
            referencePrice = record.price
        )
        if (trusted == null) {
            details += "「$shortTitle」暂取不到可信现价，本轮无法判断价保"
            return OneCheckOutcome(checked = false, newClaimable = false)
        }
        val status = PriceProtectionPolicy.evaluate(
            purchasePrice = record.price,
            nowPrice = trusted.sample.price,
            purchasedAtMs = record.purchasedAtMs,
            windowDays = windowDays,
            nowMs = now,
            alreadyNotified = record.notifiedAtMs > 0L,
            windowHours = windowHours,
            windowStartAtMs = windowStart,
            lastNotifiedYuan = record.notifiedYuan,
            lastNotifiedAtMs = record.notifiedAtMs
        )
        val notifiedAt = if (status.shouldNotify) now else record.notifiedAtMs
        val notifiedYuan = if (status.shouldNotify) status.claimableYuan else record.notifiedYuan
        runCatching {
            db.purchaseRecordDao().update(
                record.copy(
                    lastSeenPrice = trusted.sample.price,
                    lastCheckedAtMs = now,
                    claimableYuan = status.claimableYuan,
                    claimDeadlineMs = status.deadlineMs,
                    notifiedAtMs = notifiedAt,
                    notifiedYuan = notifiedYuan
                )
            )
        }.onFailure {
            // 写回失败仍可提醒（下一次会重算），但必须留下日志而不是静默
            LogT.e("价保：写回检查结果失败（不影响提醒）：id=${record.id} ${it.javaClass.simpleName}", it)
        }
        if (status.shouldNotify) {
            sendClaimableNotification(record, status, trusted.sourceLabel)
            details += "「$shortTitle」预计可退 ¥${formatYuan(status.claimableYuan)}" +
                "（剩余 ${status.remainingDays} 天${if (status.urgent) "，最后一天" else ""}）"
        } else if (status.claimable) {
            details += "「$shortTitle」预计可退 ¥${formatYuan(status.claimableYuan)}（已提醒过，不重复通知）"
        } else if (status.state == ProtectionWindowState.NO_PROTECTION && status.dropYuan > 0.0) {
            details += "「$shortTitle」无价保；较买入低 ¥${formatYuan(status.dropYuan)}（仅展示价格变化）"
        } else if (status.state == ProtectionWindowState.OUT_OF_WINDOW) {
            details += "「$shortTitle」已过价保期（${rule.label}，窗口 $windowDays 天）"
        }
        return OneCheckOutcome(checked = true, newClaimable = status.shouldNotify)
    }

    /**
     * 价保可申请通知（纯本机；渠道可在系统设置里单独关闭，也能被 [setEnabled] 关掉）。
     * 窗口剩余 ≤24h 且可申请 → 高优先级 + 「最后一天」文案（research P15）。
     */
    private fun sendClaimableNotification(record: PurchaseRecordEntity, status: ProtectionStatus, sourceLabel: String) {
        if (!notificationsAllowed()) {
            LogT.i("价保提醒被跳过：没有通知权限")
            return
        }
        val viewIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_REQUEST_BASE + record.id.toInt(),
            Intent(context, com.pricelens.ui.main.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val titleRes = if (status.urgent) R.string.protection_notify_last_day_title else R.string.protection_notify_title
        val textRes = if (status.urgent) R.string.protection_notify_last_day_text else R.string.protection_notify_text
        val text = context.getString(
            textRes,
            PriceFormatter.format(record.price),
            PriceFormatter.format(status.claimableYuan)
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_PRICE_PROTECTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(titleRes, record.title.take(20)))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text（来源：$sourceLabel；以平台审核为准）"))
            .setPriority(
                if (status.urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT
            )
            .setAutoCancel(true)
            .setContentIntent(viewIntent)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(record.id), notification)
    }

    private fun notificationsAllowed(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

    private fun notificationId(recordId: Long): Int = NOTIFICATION_REQUEST_BASE + (recordId % NOTIFICATION_ID_MOD).toInt()

    /** 平台名归一：已知平台用规则的 key，认不出的保留输入（小写）以便 UI 如实展示 */
    private fun normalizePlatform(platform: String): String {
        val rule = ProtectionPlatform.of(platform)
        return if (rule == ProtectionPlatform.OTHER) {
            platform.trim().lowercase().ifEmpty { ProtectionPlatform.OTHER.platformKey }
        } else {
            rule.platformKey
        }
    }

    private fun stateOf(records: List<PurchaseRecordEntity>, now: Long): ProtectionState {
        val watching = records.filter { it.claimedAtMs == 0L }
        val claimable = watching.filter { it.claimableYuan > 0.0 && (it.claimDeadlineMs == 0L || it.claimDeadlineMs >= now) }
        return ProtectionState(
            watchCount = watching.size,
            claimableCount = claimable.size,
            claimableYuan = PriceProtectionPolicy.roundYuan(claimable.sumOf { it.claimableYuan }),
            lastCheckAtMs = settings.autoProtectionLastCheckAtMs,
            nextCheckAtMs = nextCheckAt(settings.autoProtectionLastCheckAtMs)
        )
    }

    private fun refreshState() {
        applicationScope.launch { _state.value = stateOf(db.purchaseRecordDao().getAllOnce(), System.currentTimeMillis()) }
    }

    private fun nextCheckAt(lastCheckAtMs: Long): Long = if (lastCheckAtMs > 0L) lastCheckAtMs + PRICE_PROTECTION_PERIOD_MS else 0L

    private fun formatYuan(value: Double): String = PriceFormatter.formatRaw(PriceProtectionPolicy.roundYuan(value))

    companion object {
        /** 价保周期检查渠道（与价格提醒渠道分开：用户可以在系统设置里单独关闭价保提醒） */
        const val CHANNEL_PRICE_PROTECTION: String = "price_protection"

        /** 价保检查周期（与 [com.pricelens.worker.PriceProtectionWorker.schedule] 的 6 小时一致） */
        const val PRICE_PROTECTION_PERIOD_MS: Long = 6L * 3600 * 1000

        /** 详情行最多展示条数（避免一次检查把几百条写进 UI） */
        private const val MAX_DETAILS = 8
        private const val NOTIFICATION_REQUEST_BASE = 3000
        private const val NOTIFICATION_ID_MOD = 100_000L

        /** 购买记录里的窗口覆盖哨兵：用户明确标记"该商品无价保"（0 留给"用平台默认档"） */
        const val NO_PROTECTION_OVERRIDE: Int = -1
    }
}
