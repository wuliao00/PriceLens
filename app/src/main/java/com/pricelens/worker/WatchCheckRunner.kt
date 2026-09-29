package com.pricelens.worker

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
import com.pricelens.data.local.entity.PriceTargetEntity
import com.pricelens.data.remote.JdApi
import com.pricelens.domain.PriceSample
import com.pricelens.domain.PriceSampling
import com.pricelens.domain.PriceSource
import com.pricelens.domain.SkipReason
import com.pricelens.domain.WatchRoundReport
import com.pricelens.domain.WatchSkipCounts
import com.pricelens.domain.WatchTargetPolicy
import com.pricelens.domain.WatchTargetRef
import com.pricelens.util.LogT
import com.pricelens.util.PriceFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 盯价检查共享执行器：PriceCheckWorker（WorkManager 兜底）与
 * WatchForegroundService（常驻通知栏）共用同一套查价 + 降价通知逻辑，
 * 避免两处实现漂移。
 *
 * 2026-09 盯价链路修复（A3）：旧实现把"拿不到价格"和"没在盯"混成同一种静默 `continue`
 * （p.3.cn 域名已不可达 → 返回空 Map → 既通知也不计数，用户看到的是"盯了没反应"）。
 * 现在：
 *  - 账目由纯函数 [WatchTargetPolicy.classifyRound] 计算，逐目标区分
 *    「平台无通道 / 主键非法 / 本轮取不到现价」；
 *  - 每轮结果写入 [lastRound]（盯价页直接展示，不再只有日志）；
 *  - 连续多轮零进展时，发一条**可点开**的说明通知（每进程只发一次，恢复进展后重新允许）。
 *
 * 2026-09-29 F1：星罗好货接口是「历史低价榜」，旧实现把它的 `real_money`（券后历史低价）
 * 当成现价塞进无标记的 `Map<String, Double>`，于是"价格没降也发降价通知"，同一个值还被
 * 写成"今天的曲线点"污染历史表。现在每轮的现价是带来源的 [PriceSample]，取哪个字段、
 * 能不能通知、能不能进曲线一律收口在 [PriceSampling]；本轮只有参考值的目标计入
 * `skipped.noPrice`（并另记 [com.pricelens.domain.WatchSkipCounts.referenceOnly] 供盯价页脚注）。
 */
@Singleton
class WatchCheckRunner @Inject constructor(
    private val db: AppDatabase,
    private val jdApi: JdApi,
    private val linkstarsApi: com.pricelens.data.remote.LinkstarsApi,
    private val settings: com.pricelens.data.repository.SettingsRepository
) {

    /**
     * @param total 活跃目标数；checked 成功查到现价的目标数；triggered 触发降价通知数；
     * failedPlatformCount > 0 表示有平台整轮失败（Worker 据此走退避重试）；
     * skipped 是按原因分类的跳过计数（不计入 checked）
     */
    data class Outcome(
        val total: Int,
        val checked: Int,
        val triggered: Int,
        val failedPlatformCount: Int,
        val skipped: WatchSkipCounts = WatchSkipCounts()
    ) {
        /** 本轮一个目标都没查到价 */
        val stalled: Boolean get() = total > 0 && checked == 0
    }

    /** 一轮检查的对外快照（盯价页展示用；含被跳过的目标数与原因） */
    data class RoundSummary(
        val atMillis: Long,
        val total: Int,
        val checked: Int,
        val triggered: Int,
        val failedPlatformCount: Int,
        val skipped: WatchSkipCounts
    ) {
        val stalled: Boolean get() = total > 0 && checked == 0

        fun lastCheckText(): String = TIME_FORMAT.format(Date(atMillis))
    }

    private val _lastRound = MutableStateFlow<RoundSummary?>(null)

    /** 最近一轮检查结论（null = 本进程还没跑过） */
    val lastRound: StateFlow<RoundSummary?> = _lastRound

    /** 连续零进展轮次计数与"说明通知只发一次"的闸门（同进程内 Worker/Service 共享本单例） */
    private var stalledRounds = 0
    private var stallNoticePosted = false

    suspend fun runOnce(context: Context): Outcome {
        val entities = db.priceTargetDao().getAllActive()
        if (entities.isEmpty()) {
            // 目标清空：不该继续被视作"卡住"
            stalledRounds = 0
            stallNoticePosted = false
            _lastRound.value = null
            return Outcome(0, 0, 0, 0)
        }
        val targets = entities.map { WatchTargetRef(it.productId, it.platform, it.targetPrice) }
        val byPlatform = targets.groupBy { it.platform }

        val pricesByPlatform = mutableMapOf<String, Map<String, PriceSample>>()
        val failedPlatforms = mutableSetOf<String>()
        for ((platform, group) in byPlatform) {
            if (!WatchTargetPolicy.isTrackablePlatform(platform)) continue
            val trackable = group.filter { e -> WatchTargetPolicy.isTrackableTarget(e.productId, e.platform) }
            if (trackable.isEmpty()) continue
            val fetched = fetchPlatformPrices(platform, entitiesOf(entities, trackable))
            if (fetched == null) failedPlatforms += platform else pricesByPlatform[platform] = fetched
        }

        val report: WatchRoundReport = WatchTargetPolicy.classifyRound(targets, pricesByPlatform, failedPlatforms)
        var triggered = 0
        for (productId in report.triggeredProductIds) {
            val target = entities.firstOrNull { it.productId == productId } ?: continue
            val platform = target.platform
            val sku = WatchTargetPolicy.externalIdOf(productId, platform) ?: continue
            val sample = pricesByPlatform[platform]?.get(sku) ?: continue
            sendNotification(context, target, sample)
            triggered++
        }

        val outcome = Outcome(
            total = targets.size,
            checked = report.checked,
            triggered = triggered,
            failedPlatformCount = failedPlatforms.size,
            skipped = report.skipped
        )
        _lastRound.value = RoundSummary(
            atMillis = System.currentTimeMillis(),
            total = outcome.total,
            checked = outcome.checked,
            triggered = outcome.triggered,
            failedPlatformCount = outcome.failedPlatformCount,
            skipped = outcome.skipped
        )
        if (report.stalled) {
            stalledRounds++
            LogT.w(
                "盯价本轮零进展：${outcome.total} 个目标，" +
                    "无通道 ${outcome.skipped.noChannel}、主键非法 ${outcome.skipped.badTargetId}、" +
                    "取不到现价 ${outcome.skipped.noPrice}" +
                    "（其中只有星罗历史低价参考值 ${outcome.skipped.referenceOnly}）"
            )
        } else {
            stalledRounds = 0
            stallNoticePosted = false
        }
        if (stalledRounds >= STALLED_ROUNDS_BEFORE_NOTICE && !stallNoticePosted) {
            stallNoticePosted = true
            sendStalledNotification(context, outcome)
        }
        return outcome
    }

    /** 按 platform 分发查价；null = 该平台本轮失败（应重试），空 Map = 通道在但一个价都没拿到 */
    private suspend fun fetchPlatformPrices(platform: String, targets: List<PriceTargetEntity>): Map<String, PriceSample>? {
        if (platform != WatchTargetPolicy.PLATFORM_JD) return emptyMap()
        val skus = targets.mapNotNull { WatchTargetPolicy.externalIdOf(it.productId, platform) }
        val base = runCatching {
            jdApi.getPrices(skus).mapValues { (_, price) -> price.first }
        }.onFailure { e ->
            LogT.w("盯价：京东查价失败：${e.javaClass.simpleName}")
        }.getOrNull()
        // 星罗兜底：p.3.cn 整轮失败或缺价 SKU 时补样本。
        // 取榜单的哪个字段、补进来的值算不算"现价"，一律由 PriceSampling 决定（F1 收口点）。
        val p3cn = p3cnSamples(base)
        val missing = skus.filter { (base?.get(it) ?: 0.0) <= 0 }
        val apikey = settings.linkstarsApiKey
        if (missing.isEmpty() || apikey.isBlank()) {
            return PriceSampling.composeJd(p3cn, emptyMap(), p3cnFailed = base == null)
        }
        val references = LinkedHashMap<String, PriceSample>()
        for (sku in missing) {
            val deal = runCatching { linkstarsApi.lookupSku(sku, apikey) }.getOrNull() ?: continue
            val sample = PriceSampling.linkstarsSample(deal.listPrice, deal.couponPrice) ?: continue
            references[sku] = sample
        }
        return PriceSampling.composeJd(p3cn, references, p3cnFailed = base == null)
    }

    /** p.3.cn 的裸价表 → 带来源标记的样本表（京东在售价） */
    private fun p3cnSamples(base: Map<String, Double>?): Map<String, PriceSample> =
        base?.mapValues { (_, price) -> PriceSample(price, PriceSource.JD_P3CN) }.orEmpty()

    private fun entitiesOf(entities: List<PriceTargetEntity>, refs: List<WatchTargetRef>): List<PriceTargetEntity> {
        val ids = refs.map { it.productId }.toSet()
        return entities.filter { it.productId in ids }
    }

    /**
     * 达标通知，文案跟着现价的**来源**走（F1，2026-09-29）。
     *
     *  - [PriceSource.JD_P3CN]（京东在售价）：沿用「¥X 降价了！／当前 ¥X ≤ 目标 ¥Y」；
     *  - [PriceSource.LINKSTARS_LIST]（星罗榜单里的在售价）：标题只说"已达目标价"——
     *    我们并没有观察到它降价，只是这个非实时的标价低于目标；正文写明来源与"非实时"；
     *  - [PriceSource.LINKSTARS_HISTORY_LOW]（券后历史低价）：走不到这里，
     *    `WatchTargetPolicy.skipReasonFor` 已经把它判成"本轮取不到现价"，
     *    既不计 checked 也不进 [WatchRoundReport.triggeredProductIds]。
     */
    private fun sendNotification(context: Context, target: PriceTargetEntity, sample: PriceSample) {
        if (!notificationsAllowed(context)) return
        val price = PriceFormatter.formatRaw(sample.price)
        val targetPrice = PriceFormatter.formatRaw(target.targetPrice)
        val liveFromJd = sample.source == PriceSource.JD_P3CN
        val title = if (liveFromJd) {
            context.getString(R.string.notification_price_drop_title, target.title)
        } else {
            context.getString(R.string.notification_watch_below_target_title, target.title)
        }
        val text = if (liveFromJd) {
            context.getString(R.string.notification_price_drop_text, price, targetPrice)
        } else {
            context.getString(R.string.notification_price_drop_sourced, price, sample.source.label, targetPrice)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_PRICE_ALERT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context)
            .notify(target.productId.hashCode(), notification)
    }

    /**
     * 连续多轮零进展 → 一条可点开的说明通知。
     * 旧行为是永远静默：用户既不知道目标没在被查，也不知道为什么。
     */
    private fun sendStalledNotification(context: Context, outcome: Outcome) {
        if (!notificationsAllowed(context)) return
        val reason = dominantReason(outcome.skipped) ?: SkipReason.PRICE_UNAVAILABLE
        val text = when (reason) {
            SkipReason.NO_PRICE_CHANNEL -> context.getString(R.string.watch_stale_notify_no_channel)
            SkipReason.INVALID_TARGET_ID -> context.getString(R.string.watch_stale_notify_bad_id)
            SkipReason.PRICE_UNAVAILABLE -> context.getString(R.string.watch_stale_notify_no_price)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_PRICE_ALERT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.watch_stale_notify_title, outcome.total))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    NOTIFICATION_STALLED,
                    Intent(context, com.pricelens.ui.main.MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_STALLED, notification)
    }

    /** 跳过计数里最该先告诉用户的那一条 */
    private fun dominantReason(counts: WatchSkipCounts): SkipReason? = counts.reasons().firstOrNull()

    private fun notificationsAllowed(context: Context): Boolean = // Android 13 以下没有 POST_NOTIFICATIONS 运行时权限：旧实现只查这一个权限，
        // 结果 API 23~32 设备上所有盯价通知（含降价提醒）被永久跳过
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val CHANNEL_PRICE_ALERT = "price_alert"
        const val CHANNEL_WATCH_STATUS = "watch_status"

        /** 连续这么多轮零进展才提示一次（30 分钟一轮 → 约 1 小时） */
        private const val STALLED_ROUNDS_BEFORE_NOTICE = 2
        private const val NOTIFICATION_STALLED = 2002
    }
}

/** 轮次时间戳展示格式（HH:mm），供盯价页与通知复用 */
private val TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())
