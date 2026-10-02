package com.pricelens.worker

import com.pricelens.accessibility.PriceEvents
import com.pricelens.data.local.DayCurve
import com.pricelens.data.local.dao.PriceHistoryDao
import com.pricelens.data.local.dao.WatchIdentityDao
import com.pricelens.data.local.entity.WatchIdentityEntity
import com.pricelens.domain.OverlayIdentityPolicy
import com.pricelens.util.LogT
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 免凭证曲线：浮窗侧的「读价 → 今日日点」记录器。
 * 判定全在 OverlayIdentityPolicy（纯函数），这里只取数与落库。
 * 未确认身份的读价一律丢弃：没有键就没法记，写进哪个 productId 都是猜（设计 §2.5）。
 *
 * 不走 WatchCheckRunner：它按 price_targets 遍历（那些目标有查价通道），
 * 确认身份没有 SKU。日点同样过 DayCurve.upsertFor + (productId,date) 唯一索引，
 * 收盘/dayLow/出处语义与盯价轮次完全一致。
 *
 * 写失败只记日志不抛（不许静默：LogT.e 进 release）：浮窗绝不能因记点失败而崩。
 *
 * 必须是 @Singleton：去重状态（[lastDedupKey]）存在实例字段里，而 OverlayManager 每次
 * 都经 Hilt EntryPoint 取记录器 —— 没这个注解时 EntryPoint 每调一次就 new 一个新实例，
 * lastDedupKey 永远从 null 开始，去重形同虚设（同一个签名密集重发就写一整天同价点）。
 */
@Singleton
class OverlayCurveRecorder @Inject constructor(
    private val identityDao: WatchIdentityDao,
    private val historyDao: PriceHistoryDao
) {
    /**
     * 同签名去重（设计 §5.6）：浮窗 collect 侧没有节流，TYPE_WINDOW_CONTENT_CHANGED 可密集触发。
     * 键里必须带日期（M2）：签名只包 平台|标题|价格文本，隔天同价仍是同一签名，
     * 不带日期的去重会「今天读到的价与昨天完全一样 → 今天不再涨点」，曲线整天缺一天。
     */
    private var lastDedupKey: String? = null

    /** 当前 detection 对应的身份行（null = 未确认）。确认态每次现查表：内存态扛不了服务重启（设计 §2.4） */
    suspend fun matchedIdentity(detected: PriceEvents.Detected): WatchIdentityEntity? {
        val platform = OverlayIdentityPolicy.platformKey(detected.platform) ?: return null
        val title = detected.title?.takeIf { it.isNotBlank() } ?: return null
        val ref = OverlayIdentityPolicy.match(allRefs(), platform, title) ?: return null
        return identityDao.getByProduct(ref.productId)
    }

    /** 每次 detection：已确认身份的读价写成``今日点``；未确认 → 一次 DAO 写都没有 */
    suspend fun onDetected(detected: PriceEvents.Detected) {
        val key = dedupKey(detected, today())
        if (key == lastDedupKey) return
        lastDedupKey = key
        val identity = matchedIdentity(detected) ?: return
        writeDayPoint(identity, detected)
    }

    /** 用户按下``就是这个商品``的那一刻：建/认领身份，并立即把本次读价写成第一点 */
    suspend fun confirm(detected: PriceEvents.Detected): ConfirmOutcome {
        val title = detected.title ?: return ConfirmOutcome.NotEligible
        lastDedupKey = dedupKey(detected, today()) // 本次 detection 已被确认动作消费，紧随其后的重复事件不再写第二遍
        return when (val plan = OverlayIdentityPolicy.planConfirm(allRefs(), detected.platform, title)) {
            is OverlayIdentityPolicy.ConfirmPlan.Create -> {
                val now = System.currentTimeMillis()
                identityDao.upsert(
                    WatchIdentityEntity(
                        productId = plan.productId,
                        platform = plan.platform,
                        title = plan.title,
                        normalizedTitle = plan.normalizedTitle,
                        confirmedAt = now,
                        lastSeenAt = now,
                        lastPrice = detected.price.coerceAtLeast(0.0),
                        lastBasis = detected.priceBasis.name
                    )
                )
                val row = identityDao.getByProduct(plan.productId)
                if (row != null) writeDayPoint(row, detected)
                ConfirmOutcome.Confirmed(plan.productId)
            }
            is OverlayIdentityPolicy.ConfirmPlan.Already -> {
                val row = identityDao.getByProduct(plan.identity.productId)
                if (row != null) writeDayPoint(row, detected)
                ConfirmOutcome.Already(plan.identity.productId)
            }
            OverlayIdentityPolicy.ConfirmPlan.Full -> ConfirmOutcome.Full
            OverlayIdentityPolicy.ConfirmPlan.NotEligible -> ConfirmOutcome.NotEligible
        }
    }

    /**
     * 取消确认：删身份行必须连带删它的日点（§5.4）——留孤儿点 = 库里点无处显示，脚注还把出处虚报成``本机自采``。
     *
     * M5（无事务前提下的顺序防护，如实写明）：Recorder 只吃两个 DAO（JVM 假 DAO 可测），
     * 没有 RoomDatabase 可包 `withTransaction`，两步删除天生不是原子的。能做的只有：
     *  1. **先删身份行、再删日点**（旧序反了：身份还在、点已空 = 把已确认商品显示成"零天"，
     *     而任何在途写点都会重新插行成为无主孤儿）；
     *  2. [writeDayPoint] 写前复核身份行仍在（见那里）。
     * 两条合起来把窗口期从"必产孤儿"缩到"写前复核已挡住"：同一协程调度下 Room 写串行了，
     * 写路径读到身份已删就不写，不会在取消之后复活。
     */
    suspend fun cancel(productId: String) {
        identityDao.delete(productId)
        historyDao.deleteByProduct(productId)
    }

    /** 已记天数（浮窗/盯价页的「已记 N 天」） */
    suspend fun recordedDays(productId: String): Int = historyDao.countDays(productId)

    /**
     * 这条身份在本机曲线上的最低日点（浮窗「本机最低」）：口径与读侧一致 ——
     * 按 [DayCurve.lowOf] 取当日至低（收盘点不能冒充盘中低点），价格 <= 0 的脏行丢掉。
     * 一个可用点都没有 → null（UI 退化到只显天数，绝不把 0 当最低价）。
     */
    suspend fun lowestRecorded(productId: String): Double? =
        historyDao.getByProduct(productId).filter { it.price > 0 }.minOfOrNull { DayCurve.lowOf(it) }

    // ---------- 内部 ----------

    /** 去重键 = 签名|当日日期（抽成 internal 是为了直接断言"隔一天同一签名必须能重新涨点"） */
    internal fun dedupKey(detected: PriceEvents.Detected, date: String): String = detected.signature + "|" + date

    /**
     * 今日日期：yyyy-MM-dd，与盯价轮次（WatchCheckRunner.DAY_FORMAT）同一口径、字典序 = 时间序。
     *
     * m1：旧实现用 SimpleDateFormat（非线程安全，共享实例在并发格式下会抛 AIOOBE/输出脏值），
     * 换成 java.time.LocalDate.now().toString()：minSdk 26 可用，输出形式相同且与 Locale 无关。
     */
    private fun today(): String = LocalDate.now().toString()

    private suspend fun allRefs(): List<OverlayIdentityPolicy.IdentityRef> =
        identityDao.getAllOnce().map { OverlayIdentityPolicy.IdentityRef(it.productId, it.platform, it.title) }

    private suspend fun writeDayPoint(identity: WatchIdentityEntity, detected: PriceEvents.Detected) {
        // M5 写前复核：拿到 identity 对象与真正落库之间，用户可能已经点了「取消」。
        // 没有事务可依赖，就只能自己确认那条身份行还在 —— 不然在途写点会把刚删的行
        // 以孤儿点的形式写回来（库里有点、库里无身份，脚注把出处虚报成本机自采）。
        if (identityDao.getByProduct(identity.productId) == null) return
        val sample = OverlayIdentityPolicy.sampleFor(detected.price, detected.priceBasis, detected.title, detected.platform) ?: return
        val now = System.currentTimeMillis()
        val date = today()
        val point = DayCurve.upsertFor(identity.productId, historyDao.getDayPoint(identity.productId, date), sample, date, now) ?: return
        runCatching { historyDao.upsertDay(point) }
            .onFailure { e -> LogT.e("浮窗自采日点写入失败：" + e.javaClass.simpleName + " " + e.message) }
            .onSuccess {
                runCatching { identityDao.touchLastSeen(identity.productId, now, detected.price, detected.priceBasis.name) }
                    .onFailure { e -> LogT.e("浮窗身份观测时刻更新失败：" + e.javaClass.simpleName + " " + e.message) }
            }
    }
}

/** 确认动作的结果（UI 映射成具体文案；Full 提示先清理，NotEligible 说明素材不足） */
sealed interface ConfirmOutcome {
    data class Confirmed(val productId: String) : ConfirmOutcome

    data class Already(val productId: String) : ConfirmOutcome

    data object Full : ConfirmOutcome

    data object NotEligible : ConfirmOutcome
}
