package com.pricelens.worker

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.updateAll
import com.pricelens.R
import com.pricelens.data.local.AppDatabase
import com.pricelens.data.local.DayCurve
import com.pricelens.data.local.entity.PriceTargetEntity
import com.pricelens.data.remote.JdApi
import com.pricelens.data.repository.KeywordPriceSearch
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
import com.pricelens.widget.WatchWidget
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 *
 * 2026-09-30 盯价自采进曲线：每轮的 live 价过去只是用来发通知，**从不落库**，
 * 于是没有慢慢买 Cookie 的用户永远攒不出历史曲线。现在 [recordDailyCurvePoints]
 * 在分类之后把"今日的曲线点"写进 `price_history`：一天一行、当日最后一个 live 样本作收盘点、
 * 另存当日至低；资格判定依旧只在 [PriceSampling.curveWorthy]，取不到价的目标不写。
 *
 * 2026-10-02 桌面小组件快照（用户优化文档 §七）：每轮收尾由 [publishWidgetState] 把
 * watching（当轮活跃目标数）/ dropped（累计发出降价提醒数）/ last_check_at 落成
 * SharedPreferences("watch_state") 快照并刷新 [WatchWidget]；目标清空轮只归零 watching。
 *
 * 2026-11 自动追价（关键词同款搜索）：京东 p.3.cn 域名不可达 + 非京东平台没有直查通道，
 * 每轮在直查之后由 [KeywordPriceSearch] 对"直查没拿到真现价的目标"做关键词同款搜索补缺：
 *  - 候选必须过严格相关性 + 价格比值（见 `domain/AutoTrackPolicy`），选中样本带
 *    [PriceSource.KEYWORD_SEARCH]（label「同款搜索价」）；
 *  - 与直查样本合并时**直查优先**；参考值（`referenceOnly`）依旧不许当现价（F1 纪律不变）；
 *  - 曲线落点 / 降价通知闸门 / 跳过记账全部沿用既有语义，只把"同款搜索"计入统计并如实上报失败源。
 *
 * 轮次唯一性（真机「同一轮重复写曲线/重复发通知」的防线）：[roundMutex] 保证本进程内同时
 * 最多一轮；[MIN_ROUND_GAP_MS] 内的再次触发直接合并（返回上一轮结果）。调用方（前台服务循环 /
 * 兜底 Worker / 磁贴 / 自动追价链）再各自做"链在跑就让路"的判断，见
 * [PriceCheckWorker] / [WatchForegroundService] / [AutoTrackScheduler.isChainActive]。
 */
@Singleton
class WatchCheckRunner @Inject constructor(
    private val db: AppDatabase,
    private val jdApi: JdApi,
    private val linkstarsApi: com.pricelens.data.remote.LinkstarsApi,
    private val settings: com.pricelens.data.repository.SettingsRepository,
    private val keywordPriceSearch: KeywordPriceSearch
) {

    /**
     * @param total 活跃目标数；checked 成功查到现价的目标数；triggered 触发降价通知数；
     * failedPlatformCount > 0 表示有平台整轮失败（Worker 据此走退避重试）；
     * skipped 是按原因分类的跳过计数（不计入 checked）；
     * hits 现价 ≤ 目标价的目标数（与通知闸门是否抑制无关）；keywordSearched 本轮真正发起
     * 关键词同款搜索的目标数；keywordFailedSources 本轮失败过的搜索源（人读标签）；
     * failedPlatforms 整轮失败的平台名（人读映射由调用方做）
     */
    data class Outcome(
        val total: Int,
        val checked: Int,
        val triggered: Int,
        val failedPlatformCount: Int,
        val skipped: WatchSkipCounts = WatchSkipCounts(),
        val hits: Int = 0,
        val keywordSearched: Int = 0,
        val keywordFailedSources: List<String> = emptyList(),
        val failedPlatforms: List<String> = emptyList()
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
        val skipped: WatchSkipCounts,
        val hits: Int = 0,
        val keywordSearched: Int = 0,
        val keywordFailedSources: List<String> = emptyList()
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

    /** 轮次互斥：本进程内同一时刻最多执行一轮（服务循环/兜底 Worker/磁贴/自动追价链共用） */
    private val roundMutex = Mutex()

    /** 上一轮结束时刻与结果：用于 [MIN_ROUND_GAP_MS] 内的触发合并 */
    private var lastRoundFinishedAtMs = 0L
    private var lastOutcome: Outcome? = null

    /**
     * 执行一轮盯价检查（对外唯一入口）。
     *
     * 互斥 + 短时间去重（[MIN_ROUND_GAP_MS]，60 秒）：自动追价链与前台服务/兜底 Worker 可能
     * 几乎同时触发；若串行排两轮，会重复写当日曲线点、重复发降价通知（真机事故模式）。
     * 因此：
     *  - 已有一轮在跑 → 等它结束再判断，不会并行；
     *  - 刚结束不到 60 秒 → **合并**：直接返回上一轮结果，不再查一遍（数据是新鲜的）；
     *  - 真正的错误/异常不在这里吞掉，交给调用方（与既有语义一致）。
     */
    suspend fun runOnce(context: Context): Outcome = roundMutex.withLock {
        val now = System.currentTimeMillis()
        if (lastRoundFinishedAtMs > 0L && now - lastRoundFinishedAtMs < MIN_ROUND_GAP_MS) {
            LogT.i(
                "盯价轮次合并：上一轮刚结束 ${now - lastRoundFinishedAtMs}ms（<${MIN_ROUND_GAP_MS}ms），" +
                    "本次触发不再重复查价/写曲线/发通知"
            )
            return@withLock lastOutcome ?: Outcome(0, 0, 0, 0)
        }
        val outcome = runRound(context)
        // 目标清空轮（total=0）也记录结束时刻：避免目标刚删又被服务循环立刻跑一轮空转
        lastRoundFinishedAtMs = System.currentTimeMillis()
        lastOutcome = outcome
        outcome
    }

    private suspend fun runRound(context: Context): Outcome {
        val entities = db.priceTargetDao().getAllActive()
        if (entities.isEmpty()) {
            // 目标清空：不该继续被视作"卡住"
            stalledRounds = 0
            stallNoticePosted = false
            _lastRound.value = null
            // 小组件收尾：只把 watching 归零；目标清空轮没有"完成时刻"（传 0），
            // 保留上次检查时间与累计降价（都是历史事实，不随清零动作抹掉）
            publishWidgetState(context, watching = 0, triggeredThisRound = 0, atMillis = 0L)
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

        // 关键词同款搜索补缺（2026-11）：直查没拿到真现价的目标（含全部非京东平台）走搜索通道。
        // 直查优先：这里只补"空位/参考值位"，绝不覆盖直查 live 样本（合并条件见 enrichWithKeywordSearch）。
        val keywordInfo = enrichWithKeywordSearch(entities, pricesByPlatform)

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

        // 盯价轮次自采：本轮拿到 live 价的目标，把"今日的曲线点"写进历史表。
        // 这是没有慢慢买 Cookie 时曲线唯一的来源；写失败只记日志，不参与通知与本轮统计。
        recordDailyCurvePoints(entities, pricesByPlatform, System.currentTimeMillis())

        val outcome = Outcome(
            total = targets.size,
            checked = report.checked,
            triggered = triggered,
            failedPlatformCount = failedPlatforms.size,
            skipped = report.skipped,
            // 「命中」看价格是否 ≤ 目标价（与通知闸门是否抑制无关），与 triggered（发出通知数）分开记
            hits = report.triggeredProductIds.size,
            keywordSearched = keywordInfo.searched,
            keywordFailedSources = keywordInfo.failedSources,
            failedPlatforms = failedPlatforms.toList()
        )
        _lastRound.value = RoundSummary(
            atMillis = System.currentTimeMillis(),
            total = outcome.total,
            checked = outcome.checked,
            triggered = outcome.triggered,
            failedPlatformCount = outcome.failedPlatformCount,
            skipped = outcome.skipped,
            hits = outcome.hits,
            keywordSearched = outcome.keywordSearched,
            keywordFailedSources = outcome.keywordFailedSources
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
        // 收尾：把小组件要的三个数落成持久快照并刷新桌面小组件（文档 §七）
        publishWidgetState(
            context = context,
            watching = targets.size,
            triggeredThisRound = triggered,
            atMillis = System.currentTimeMillis()
        )
        return outcome
    }

    /** 本轮关键词同款搜索的补充统计（searched = 实际发起搜索的目标数；failedSources = 失败源人读标签） */
    private data class KeywordRoundInfo(val searched: Int, val failedSources: List<String>)

    /**
     * 关键词同款搜索补缺（2026-11 自动追价）：只给「直查没拿到真现价」的目标搜同款。
     *
     * 目标集合 = 活跃目标里：平台可盯 + 主键合法 + 该目标在 [pricesByPlatform] 里没有 live 样本。
     * 这覆盖两类真实情况：
     *  1. 非京东平台（淘宝/拼多多/当当/识货/值得买）根本没有直查通道；
     *  2. 京东目标本轮 p.3.cn 不可达且星罗只给到「券后历史低价」参考值（`referenceOnly`）。
     *
     * 合并规则：搜索样本只写"空位/参考值位"，**直查 live 优先**——直查已经拿到新鲜在售价时，
     * 搜索价不得覆盖（宁可不用搜索价，也不让两个来源在同一轮互相打架）。
     * 选品资格全部在 [KeywordPriceSearch] / `domain/AutoTrackPolicy` 里，这里不另写一套 if。
     *
     * 目标数上限 [MAX_KEYWORD_TARGETS_PER_ROUND]：搜索源均带 1 小时缓存，超出的目标下一轮看；
     * 这一轮它们仍按"取不到现价"如实计数（不发通知、不写曲线），绝不拿旧价/0 充数。
     */
    private suspend fun enrichWithKeywordSearch(
        entities: List<PriceTargetEntity>,
        pricesByPlatform: MutableMap<String, Map<String, PriceSample>>
    ): KeywordRoundInfo {
        val pending = entities.filter { target ->
            if (!WatchTargetPolicy.isTrackableTarget(target.productId, target.platform)) return@filter false
            val externalId = WatchTargetPolicy.externalIdOf(target.productId, target.platform) ?: return@filter false
            val direct = pricesByPlatform[target.platform]?.get(externalId)
            direct == null || !direct.isLive
        }
        if (pending.isEmpty()) return KeywordRoundInfo(0, emptyList())
        val searchTargets = pending.map { entity ->
            KeywordPriceSearch.SearchTarget(
                key = entity.productId,
                platform = entity.platform,
                productId = entity.productId,
                title = entity.title
            )
        }
        val report = keywordPriceSearch.searchAll(searchTargets, MAX_KEYWORD_TARGETS_PER_ROUND)
        var merged = 0
        for ((productId, sample) in report.samples) {
            val target = entities.firstOrNull { it.productId == productId } ?: continue
            val externalId = WatchTargetPolicy.externalIdOf(target.productId, target.platform) ?: continue
            val existing = pricesByPlatform[target.platform]?.get(externalId)
            if (existing != null && existing.isLive) continue // 直查优先：不覆盖
            val platformPrices = LinkedHashMap(pricesByPlatform[target.platform].orEmpty())
            platformPrices[externalId] = sample
            pricesByPlatform[target.platform] = platformPrices
            merged++
        }
        if (report.searched > 0 || report.failedSources.isNotEmpty()) {
            LogT.i(
                "同款搜索补缺：待搜 ${pending.size}（上限 $MAX_KEYWORD_TARGETS_PER_ROUND），" +
                    "实际搜索 ${report.searched}，补上 $merged，失败源 ${report.failedSources.ifEmpty { listOf("无") }}"
            )
        }
        return KeywordRoundInfo(report.searched, report.failedSources)
    }

    /**
     * 小组件快照收尾（用户优化文档 §七）：watching = 本轮活跃目标数；
     * dropped 累加本轮**发出降价提醒**的次数（口径 = 累计发出降价提醒的次数，见 [WidgetStats.nextDropped]，
     * 与轮次统计的 [Outcome.triggered] 同源）；last_check_at = 本轮完成时刻（<=0 表示没有完成时刻，保留原值）。
     * 快照写盘或小组件刷新失败都不影响盯价轮次本身，只记日志。
     */
    private suspend fun publishWidgetState(context: Context, watching: Int, triggeredThisRound: Int, atMillis: Long) {
        runCatching {
            WidgetStateStore(context).recordRound(watching, triggeredThisRound, atMillis)
            WatchWidget().updateAll(context)
        }.onFailure { e ->
            LogT.w("小组件状态更新失败（不影响本轮盯价）：${e.javaClass.simpleName}")
        }
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

    /**
     * 每轮把 live 样本落成「今日的曲线点」：让没有慢慢买 Cookie 时历史曲线也能自己长出来。
     *
     * 规则（全部收口在 [DayCurve]，这里只管取数与落库）：
     *  - 只有过了 [PriceSampling.curveWorthy] 的样本才写：星罗「券后历史低价」这类
     *    `referenceOnly` 来源一律不写（否则等于把 F1 那个 bug 放回来），0/负价也不写；
     *  - 取不到价的目标**跳过**：不写 0，也不拿上一轮的旧价冒充今天；
     *  - `productId` 直接用 [PriceTargetEntity.productId]（已是 `jd:<sku>` 形态，
     *    与曲线读取侧 `getByProduct("jd:$sku")` 同一个 key 空间，不需要映射）；
     *  - 写失败只记日志（不静默），既不影响降价通知，也不改动本轮统计。
     *
     * @return 本轮写入的目标数（仅用于日志）
     */
    private suspend fun recordDailyCurvePoints(
        targets: List<PriceTargetEntity>,
        pricesByPlatform: Map<String, Map<String, PriceSample>>,
        nowMs: Long
    ): Int {
        val date = DAY_FORMAT.format(Date(nowMs))
        var written = 0
        var failed = 0
        for (target in targets) {
            if (!WatchTargetPolicy.isTrackableTarget(target.productId, target.platform)) continue
            val sku = WatchTargetPolicy.externalIdOf(target.productId, target.platform) ?: continue
            // 资格判定只有这一处闸门：不许在这里另写一套 if 去绕开 curveWorthy
            val sample = PriceSampling.curveWorthy(pricesByPlatform[target.platform]?.get(sku)) ?: continue
            val result = runCatching {
                val existing = db.priceHistoryDao().getDayPoint(target.productId, date)
                val point = DayCurve.upsertFor(target.productId, existing, sample, date, nowMs) ?: return@runCatching false
                db.priceHistoryDao().upsertDay(point)
                true
            }
            result.onSuccess { if (it) written++ }
                .onFailure { e ->
                    failed++
                    // 用 e 级而不是 w：LogT.w 只在 DEBUG 输出，release 包里"不许静默"要求的就是这一条
                    LogT.e(
                        "盯价自采写曲线失败（不影响通知与本轮统计）：${target.productId} $date " +
                            "${e.javaClass.simpleName} ${e.message}"
                    )
                }
        }
        if (written > 0) LogT.i("盯价自采：本轮为 $written 个目标写下 $date 的曲线点")
        if (failed > 0) LogT.e("盯价自采：本轮有 $failed 个目标写曲线失败，历史曲线可能缺今天的点")
        return written
    }

    private fun entitiesOf(entities: List<PriceTargetEntity>, refs: List<WatchTargetRef>): List<PriceTargetEntity> {
        val ids = refs.map { it.productId }.toSet()
        return entities.filter { it.productId in ids }
    }

    /**
     * 达标通知，文案跟着现价的**来源**走（F1，2026-09-29）。
     *
     *  - [PriceSource.JD_P3CN]（京东在售价）：沿用「¥X 降价了！／当前 ¥X ≤ 目标 ¥Y」；
     *  - [PriceSource.KEYWORD_SEARCH]（关键词搜到的同款在售价，2026-11）：标题/正文改用
     *    `strings_auto.xml` 的「同款搜索价 ¥X ≤ 目标 ¥Y」，正文提醒点开核对商品页 ——
     *    它不是目标平台自己给的实时价，不能伪装成平台降价；
     *  - [PriceSource.LINKSTARS_LIST]（星罗榜单里的在售价）：标题只说"已达目标价"——
     *    我们并没有观察到它降价，只是这个非实时的标价低于目标；正文写明来源与"非实时"；
     *  - [PriceSource.LINKSTARS_HISTORY_LOW]（券后历史低价）：走不到这里，
     *    `WatchTargetPolicy.skipReasonFor` 已经把它判成"本轮取不到现价"，
     *    既不计 checked 也不进 [WatchRoundReport.triggeredProductIds]。
     *
     * 闸门不变：通知权限 → [NotificationGate]（免打扰/仅 WiFi）→ [NotifyIgnoreStore]（用户忽略记忆）。
     * 通知 ID 用 `target.productId.hashCode()`：同一商品只会更新同一条通知，不会堆两条。
     */
    private suspend fun sendNotification(context: Context, target: PriceTargetEntity, sample: PriceSample) {
        if (!notificationsAllowed(context)) return
        // 富通知闸门（文档 UX）：免打扰时段 / 仅 WiFi；被抑制只是"这一轮不发"，下一轮重新评估
        val nowMinute = Calendar.getInstance().let {
            it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
        }
        val gate = NotificationGate.decide(
            nowMinuteOfDay = nowMinute,
            quietHoursEnabled = settings.quietHoursEnabled,
            quietStartMinute = settings.quietStartMinute,
            quietEndMinute = settings.quietEndMinute,
            wifiOnlyEnabled = settings.notifyWifiOnly,
            onWifi = onWifi(context)
        )
        if (!gate.allowed) {
            LogT.i("降价通知被闸门抑制（${gate.reason}）：${target.productId}")
            return
        }
        // 「忽略」记忆：忽略时的价格之上不再提醒；创新低自动解除（按了按钮就必须有感）
        val ignoreStore = NotifyIgnoreStore(context)
        if (ignoreStore.suppress(target.productId, sample.price)) return
        ignoreStore.clearIfPriceLower(target.productId, sample.price)
        val price = PriceFormatter.formatRaw(sample.price)
        val targetPrice = PriceFormatter.formatRaw(target.targetPrice)
        val source = sample.source
        val title: String
        val text: String
        when (source) {
            PriceSource.JD_P3CN -> {
                title = context.getString(R.string.notification_price_drop_title, target.title)
                text = context.getString(R.string.notification_price_drop_text, price, targetPrice)
            }
            PriceSource.KEYWORD_SEARCH -> {
                title = context.getString(R.string.auto_track_notify_title, target.title)
                text = context.getString(R.string.auto_track_notify_text, price, targetPrice)
            }
            else -> {
                title = context.getString(R.string.notification_watch_below_target_title, target.title)
                text = context.getString(R.string.notification_price_drop_sourced, price, source.label, targetPrice)
            }
        }
        val viewIntent = PendingIntent.getActivity(
            context,
            target.productId.hashCode(),
            Intent(context, com.pricelens.ui.main.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val ignoreIntent = PendingIntent.getBroadcast(
            context,
            target.productId.hashCode() + 1,
            Intent(context, NotifyIgnoreReceiver::class.java)
                .setAction(NotifyIgnoreReceiver.ACTION_NOTIFY_IGNORE)
                .putExtra(NotifyIgnoreReceiver.EXTRA_PRODUCT_ID, target.productId)
                .putExtra(NotifyIgnoreReceiver.EXTRA_PRICE, sample.price),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_PRICE_ALERT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(curveStyle(target.productId, text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(viewIntent)
            .addAction(0, context.getString(R.string.notification_action_view), viewIntent)
            .addAction(0, context.getString(R.string.notification_action_ignore), ignoreIntent)
            .build()
        NotificationManagerCompat.from(context)
            .notify(target.productId.hashCode(), notification)
    }

    /** 有曲线（≥2 个日点）就给迷你曲线缩略图；取数与绘制全程 runCatching，失败回落纯文本 */
    private suspend fun curveStyle(productId: String, text: String): NotificationCompat.Style {
        val picture = runCatching {
            val values = db.priceHistoryDao().getByProduct(productId)
                .sortedBy { it.date }
                .map { it.price }
                .filter { it > 0 }
            val points = NotifyCurveGeometry.pathPoints(values, CURVE_WIDTH, CURVE_HEIGHT, CURVE_PAD)
                ?: return@runCatching null
            NotifyCurveBitmap.render(points, CURVE_WIDTH.toInt(), CURVE_HEIGHT.toInt(), CURVE_COLOR)
        }.getOrNull()
        return if (picture != null) {
            NotificationCompat.BigPictureStyle()
                .bigPicture(picture)
                .bigLargeIcon(null as android.graphics.Bitmap?)
        } else {
            NotificationCompat.BigTextStyle().bigText(text)
        }
    }

    /** 是否 WiFi；查询异常按"放行"（宁多提醒一次），无网络按非 WiFi 处理（仅 WiFi 选项下抑制） */
    private fun onWifi(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false
    }.getOrDefault(true)

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

        /** 通知迷你曲线画布：尺寸够清楚又不至于把通知撑爆；线色是通知栏深浅底都可辨认的中绿 */
        private const val CURVE_WIDTH = 512f
        private const val CURVE_HEIGHT = 128f
        private const val CURVE_PAD = 12f
        private val CURVE_COLOR = 0xFF4CAF50.toInt()

        /** 连续这么多轮零进展才提示一次（30 分钟一轮 → 约 1 小时） */
        private const val STALLED_ROUNDS_BEFORE_NOTICE = 2
        private const val NOTIFICATION_STALLED = 2002

        /**
         * 轮次最短间隔（毫秒）：上一轮结束不到 60 秒的再次触发直接合并。
         * 自动追价链、前台服务循环、兜底 Worker、磁贴、手动「立即检查」可能撞在一起，
         * 这条与 [roundMutex] 一起保证"同一轮数据不会重复写曲线/重复发通知"。
         */
        private const val MIN_ROUND_GAP_MS = 60_000L

        /**
         * 每轮最多对多少个目标发起关键词同款搜索（搜索源均带 1 小时缓存，超出的下一轮命中缓存）。
         * 目的：把单轮网络请求量钉在有界范围（12 × 最多 3 源 = 36 次，且失败源整轮闩锁）。
         */
        private const val MAX_KEYWORD_TARGETS_PER_ROUND = 12
    }
}

/** 轮次时间戳展示格式（HH:mm），供盯价页与通知复用 */
private val TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())

/** 曲线点的日粒度 key（与 price_history.date、PriceRepository 的 today 同口径：yyyy-MM-dd + US locale） */
private val DAY_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
