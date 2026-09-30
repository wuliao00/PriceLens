package com.pricelens.data.repository

import com.pricelens.data.cache.TLRUCache
import com.pricelens.data.local.AppDatabase
import com.pricelens.data.local.CacheTTL
import com.pricelens.data.local.DayCurve
import com.pricelens.data.local.dao.SourceDayCount
import com.pricelens.data.local.entity.PriceHistoryEntity
import com.pricelens.data.local.entity.PriceTargetEntity
import com.pricelens.data.local.entity.ProductEntity
import com.pricelens.data.local.entity.SearchRecordEntity
import com.pricelens.data.remote.BiliApi
import com.pricelens.data.remote.DangdangApi
import com.pricelens.data.remote.GwdangApi
import com.pricelens.data.remote.JdApi
import com.pricelens.data.remote.ManmanbuyApi
import com.pricelens.data.remote.ShihuoApi
import com.pricelens.data.remote.SmzdmApi
import com.pricelens.domain.PriceSampling
import com.pricelens.domain.PriceSource
import com.pricelens.util.LogT
import com.pricelens.util.QueryRelevance
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow

/**
 * §4.1 三级缓存编排（阶段3 声明式重构）：
 *   L1 TLRUCache 内存（8MB，0ms）→ L2 Room（结构化商品 + 通用 cache_entries）→ L3 网络
 *
 * 每个缓存方法仅声明 [CachedSource]（key/TTL/编解码器/源名/取数与 L2 钩子），
 * 「L1 → L2 → L3 → 写回 + 失败降级返回旧快照」由模板统一执行。
 * 公开方法签名与返回值语义保持不变（上游 ViewModel 依赖）。
 *
 * 附加能力：
 *  - [staleKeys]：降级返回旧数据的 key 集合（旁路观察，UI 可提示"旧数据"）
 *  - 仓储层 singleflight：同 key 并发取数共享一次网络请求
 *  - [SourceHealth]：源连续失败超阈值暂时跳过，直接回退旧快照
 */
@Singleton
class PriceRepository @Inject constructor(
    private val db: AppDatabase,
    // 与 CacheCleanupWorker 共享同一单例
    private val memoryCache: TLRUCache<String>,
    private val jdApi: JdApi,
    private val manmanbuyApi: ManmanbuyApi,
    private val biliApi: BiliApi,
    private val gwdangApi: GwdangApi,
    private val smzdmApi: SmzdmApi,
    private val dangdangApi: DangdangApi,
    private val shihuoApi: ShihuoApi,
    private val linkstarsApi: com.pricelens.data.remote.LinkstarsApi,
    private val settingsRepository: SettingsRepository,
    private val health: SourceHealth,
    private val hub: RevalidateHub,
    private val applicationScope: CoroutineScope
) {
    private val tracker = StaleTracker()

    /** 降级返回旧数据的缓存 key 集合（站点改版/断网时先展示旧数据的标记） */
    val staleKeys: StateFlow<Set<String>> get() = tracker.stale

    /** 仓储层 singleflight：同 key 并发取数合并为一次网络请求 */
    private val inflight = ConcurrentHashMap<String, Deferred<Any?>>()

    // ---------- 商品（L1 → L2 结构化表 → L3，写回） ----------

    suspend fun getJdProduct(skuId: String): JdApi.JdProduct? = CachedSource(
        cache = memoryCache, health = health, tracker = tracker, hub = hub,
        key = "jd:product:$skuId", ttlMs = CacheTTL.PRODUCT_INFO,
        codec = JdProductCodec, source = SOURCE_JD,
        fetch = { singleflight("jd:product:$skuId") { jdApi.getProduct(skuId) } },
        l2Load = {
            // 2026-09 数据源准确性修复：旧版本可能把风控页标题（"京东验证"）写进 Room。
            // 早于修复时刻写入的 JD 商品行不再复用（收藏标记保留，重新拉取会覆盖旧内容）。
            db.productDao().getById("jd:$skuId")
                ?.takeIf { e -> e.cachedAt >= JD_PRODUCT_CACHE_FLOOR }
                ?.let { e -> JdProductCodec.fromEntity(e) to e.cachedAt }
        },
        l2Save = { p -> db.productDao().upsert(productEntity(skuId, p)) },
        // 评审修复：恢复旧语义——Room 有行即返回、从不陈旧重验证，避免放大 403 与熔断
        revalidateOnStale = false
    ).get()

    // ---------- 历史价格（L1 → L2 写回 → L3；Room 采样点另见 persistHistory） ----------

    suspend fun getPriceHistory(productUrl: String): ManmanbuyApi.History? = kvSource(
        key = "mmb:history:$productUrl",
        ttlMs = CacheTTL.PRICE_HISTORY,
        codec = HistoryCodec,
        source = SOURCE_MMB,
        fetch = { buildHistory(productUrl) }
    ).get()

    /**
     * 三源合并（2026-09 慢慢买公开接口下线）：
     *  1. 慢慢买：公开 JSON + 用户自填 Cookie 的 SSR 通道
     *  2. 自建曲线：Room price_history 每日采样点
     *  3. 星罗好货：按京东 SKU 命中榜单时补点——但只有**在售价**（goods_list_money）够格；
     *     券后历史低价（real_money）是历史位置，写进曲线等于自己造历史（F1，2026-09-29），
     *     资格判定在 `domain/PriceSampling.curveWorthy`。
     * 任一有数据即返回，并把结果写回自建曲线库（只在外源真给了点的时候回写，
     * 并带上这批点的实际出处 —— 见 [persistHistory] 的 source 参数）。
     *
     * 读 Room 侧一律先过 [DayCurve.collapse]：一天一个点，收盘点画线、当日至低算历史最低。
     */
    private suspend fun buildHistory(productUrl: String): ManmanbuyApi.History? {
        val sku = Regex("item(?:\\.m)?\\.jd\\.com/(?:product/)?(\\d{6,})").find(productUrl)?.groupValues?.get(1)
        val cookie = settingsRepository.manmanbuyCookie.ifBlank { null }
        val mmb = runCatching { manmanbuyApi.getHistory(productUrl, cookie) }.getOrNull()

        // 自建曲线：Room 的点先按日去重再读 —— v2 的 REPLACE 是假的（自增主键永不冲突），
        // 老库同一天有多行；同时把「当日至低」读出来，历史最低要用它，不用收盘点。
        val selfDays = if (sku != null) DayCurve.collapse(db.priceHistoryDao().getByProduct("jd:$sku")) else emptyList()
        val points = mutableListOf<ManmanbuyApi.PricePoint>()
        mmb?.points?.let { points += it }
        if (points.isEmpty()) {
            points += selfDays.map { ManmanbuyApi.PricePoint(it.date, it.close) }
        }
        // 这批点的实际出处：慢慢买给了点就标 MANMANBUY，只有星罗补点就标 LINKSTARS_LIST。
        // 全是本机自采回流时不回写 —— 写回去只会把「来源未记录」的老行编成自采的功劳。
        var writeSource: PriceSource? = if (mmb != null && mmb.points.isNotEmpty()) PriceSource.MANMANBUY else null
        if (sku != null && settingsRepository.linkstarsApiKey.isNotBlank()) {
            val deal = runCatching {
                linkstarsApi.lookupSku(sku, settingsRepository.linkstarsApiKey)
            }.getOrNull()
            // 星罗补点：取榜单的哪个字段、这个值有没有资格当"今日的曲线点"，一律由 PriceSampling 判
            val sample = PriceSampling.curveWorthy(
                deal?.let { PriceSampling.linkstarsSample(it.listPrice, it.couponPrice) }
            )
            if (sample != null) {
                val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                    .format(java.util.Date())
                if (points.none { it.date == today }) {
                    points += ManmanbuyApi.PricePoint(today, sample.price)
                    if (writeSource == null) writeSource = PriceSource.LINKSTARS_LIST
                }
            }
        }
        if (points.isEmpty()) return null
        val merged = points.sortedBy { it.date }
        val prices = merged.map { it.price }
        // 「历史最低」看**当日至低**（dayLow）：自采行带着盘中真见过的低点，
        // 收盘点不能冒充它；外部源只给一个日值，那就只有它自己。
        val lowest = listOfNotNull(prices.min(), mmb?.lowest, selfDays.minOfOrNull { it.dayLow }).min()
        val history = ManmanbuyApi.History(
            current = prices.last(),
            lowest = lowest,
            highest = maxOf(mmb?.highest ?: prices.max(), prices.max()),
            points = merged
        )
        // 自建曲线积累：只在真拿到外源点时落库（每天 1 点，靠 (productId, date) 唯一索引按日覆盖）
        val source = writeSource
        if (sku != null && source != null) {
            runCatching { persistHistory("jd:$sku", history, source) }
                .onFailure { e -> LogT.w("历史曲线回写失败（不影响展示）：${e.javaClass.simpleName} ${e.message}") }
        }
        return history
    }

    // ---------- B站 / 优惠券 / 值得买 / 当当 / 识货（L1 → L2 → L3） ----------
    //
    // F4（2026-09-29）口径变更：这五个关键词通道改用 [CachedSource.getList]，
    // 返回的**空表只有一种含义 = "够着了该源、过滤后确实 0 条"**；
    // 断网 / 被反爬拦 / 源在失败冷却期 → 抛出类型化异常（由 CachedSource 冒泡），
    // 上游 SearchViewModel 写成 AsyncValue.Error、徽标写「反爬/失败」、空态写「取不到数据」。
    // 以前这里是 `?: emptyList()`，四种结局全被压成空表 ⇒ 徽标「正常」+ 文案「未匹配到关键词」。

    suspend fun searchVideos(keyword: String): List<BiliApi.BiliVideo> = kvSource(
        key = "bili:search:$keyword",
        ttlMs = CacheTTL.BILI_SEARCH,
        codec = BiliVideosCodec,
        source = SOURCE_BILI,
        fetch = { biliApi.searchVideos(keyword) },
        cacheable = { it.isNotEmpty() }
    ).getList()

    suspend fun searchCoupons(keyword: String): List<GwdangApi.Coupon> = kvSource(
        key = "gwd:coupon:$keyword",
        ttlMs = CacheTTL.COUPON,
        codec = CouponsCodec,
        source = SOURCE_GWD,
        fetch = { gwdangApi.searchCoupons(keyword) },
        cacheable = { it.isNotEmpty() }
    ).getList()

    suspend fun searchSmzdm(keyword: String): List<SmzdmApi.SmzdmPost> = kvSource(
        key = "smz:search:$keyword",
        ttlMs = CacheTTL.SMZDM_FEED,
        codec = SmzdmPostsCodec,
        source = SOURCE_SMZDM,
        fetch = { smzdmApi.searchPosts(keyword) },
        cacheable = { it.isNotEmpty() }
    ).getList()

    /** 关键词搜索商品候选：当当搜索（SSR 稳定，主数据源） */
    suspend fun searchDangdang(keyword: String): List<DangdangApi.DangdangItem> = kvSource(
        key = "dd:search:$keyword",
        ttlMs = CacheTTL.SMZDM_FEED,
        codec = DangdangItemsCodec,
        source = SOURCE_DD,
        fetch = { dangdangApi.searchProducts(keyword) },
        cacheable = { it.isNotEmpty() }
    ).getList()

    /** 识货搜索（社区页补充源：鞋服/数码等当当覆盖不到的品类，含国补标记） */
    suspend fun searchShihuo(keyword: String): List<ShihuoApi.ShihuoItem> = kvSource(
        key = "sh:search:$keyword",
        ttlMs = CacheTTL.SMZDM_FEED,
        codec = ShihuoItemsCodec,
        source = SOURCE_SH,
        fetch = { shihuoApi.searchProducts(keyword) },
        cacheable = { it.isNotEmpty() }
    ).getList()

    // ---------- 搜索记录 / 收藏 ----------

    suspend fun recordSearch(keyword: String) {
        if (keyword.isNotBlank()) {
            db.searchRecordDao().insert(
                SearchRecordEntity(keyword = keyword.trim(), searchedAt = System.currentTimeMillis())
            )
        }
    }

    fun recentSearches() = db.searchRecordDao().observeRecentKeywords()

    suspend fun pinProduct(id: String, pinned: Boolean) {
        db.productDao().setPinned(id, pinned)
        if (pinned) {
            memoryCache.pin("jd:product:${id.removePrefix("jd:")}")
        } else {
            memoryCache.unpin("jd:product:${id.removePrefix("jd:")}")
        }
    }

    fun recentProducts() = db.productDao().observeRecent()

    /** L1 内存缓存占用（个人页/设置页统计用） */
    fun memoryCacheSizeBytes(): Long = memoryCache.sizeBytes()

    // ---------- 浮窗聚合（A2 新增：仅供无障碍比价浮窗，不影响既有方法与上游 ViewModel） ----------

    /**
     * 浮窗展开面板的聚合数据：历史价位置 / 优惠券 / 多平台同款报价。
     *
     * 防错配约定（与浮窗硬规则一致）：
     *  - [jdSku] 仅在浮窗拿到**确定性商品 ID**（DetectionBasis.ITEM_ID）时传入；
     *    为 null 时历史价与多平台报价返回空——绝不把别的 SKU 的历史价当当前商品价；
     *  - 券/当当/识货是关键词通道，返回前逐条过 [QueryRelevance.isRelevant] 过滤，
     *    UI 侧仍需标注"不同店铺/规格，仅供参考"；
     *  - [OverlayBundle.stale]=true 表示本次用到的缓存 key 命中 [staleKeys]（降级回吐旧数据）；
     *  - [OverlayBundle.fetchedAtMs] 是**本次 bundle 里最老那份数据的抓取时刻**（多来源取 min，见
     *    [bundleFetchedAtMs]），不是 bundle 的组装时刻——命中缓存时脚注据此说"N 天前"，
     *    问不到时刻则 null（脚注改说"数据抓取时间未知"），绝不冒充"刚刚"（F3 缺陷二）。
     *
     * 全部经既有 [CachedSource] 通道取数：与 SearchViewModel 的同 key 搜索共享 singleflight，
     * 不新增额外网络放大。
     */
    suspend fun overlayBundle(keyword: String, jdSku: String?): OverlayBundle {
        val historyUrl = jdSku?.let { "https://item.jd.com/$it.html" }
        val historyKey = historyUrl?.let { "mmb:history:$it" }
        val couponKey = "gwd:coupon:$keyword"
        val dangdangKey = "dd:search:$keyword"
        val shihuoKey = "sh:search:$keyword"
        val history = historyUrl?.let { url -> runCatching { getPriceHistory(url) }.getOrNull() }
        val coupons = runCatching { searchCoupons(keyword) }.getOrNull()
            ?.sortedByDescending { it.amount }
            ?.take(3)
            ?: emptyList()
        // 真正往 bundle 里放了值的 key 才参与"数据时刻"计算（空源不拖后腿）
        val contributedKeys = ArrayList<String>(4)
        if (history != null && historyKey != null) contributedKeys.add(historyKey)
        if (coupons.isNotEmpty()) contributedKeys.add(couponKey)
        val platforms = ArrayList<PlatformQuote>(2)
        if (jdSku != null) {
            runCatching { searchDangdang(keyword) }.getOrNull()
                ?.firstOrNull { it.price > 0 && QueryRelevance.isRelevant(keyword, it.title) }
                ?.let {
                    platforms.add(PlatformQuote(platform = "当当", price = it.price))
                    contributedKeys.add(dangdangKey)
                }
            runCatching { searchShihuo(keyword) }.getOrNull()
                ?.firstOrNull { it.price > 0 && QueryRelevance.isRelevant(keyword, it.title) }
                ?.let {
                    platforms.add(PlatformQuote(platform = "识货", price = it.price))
                    contributedKeys.add(shihuoKey)
                }
        }
        val usedKeys = listOfNotNull(
            historyKey,
            couponKey,
            if (jdSku != null) dangdangKey else null,
            if (jdSku != null) shihuoKey else null
        )
        val stale = usedKeys.any { it in staleKeys.value }
        return OverlayBundle(
            history = history,
            coupons = coupons,
            platforms = platforms,
            sourceLabel = if (history != null) "慢慢买" else "什么值得买",
            fetchedAtMs = bundleFetchedAtMs(contributedKeys, keyAge = ::dataAgeCandidates),
            stale = stale
        )
    }

    /**
     * 某个缓存 key 的数据抓取时刻候选：
     *  - L1 内存条目 `createdAt`（网络写回=本轮抓取时刻；L2 提升/降级兜底=快照自己的时刻，见 [CachedSource]）
     *  - L2 Room `cache_entries.cachedAt`
     * 两处都可能问不到（被淘汰/被清理/写回失败），缺失就不给。
     */
    private suspend fun dataAgeCandidates(key: String): List<Long?> = listOfNotNull(
        memoryCache.peek(key)?.createdAt,
        db.cacheEntryDao().get(key)?.cachedAt
    )

    // ---------- 个人页 / 设置页 ----------

    fun observePinned() = db.productDao().observePinned()

    fun observeTargets() = db.priceTargetDao().observeActive()

    suspend fun setTarget(target: PriceTargetEntity) {
        db.priceTargetDao().upsert(target)
    }

    suspend fun deactivateTarget(productId: String) {
        db.priceTargetDao().deactivate(productId)
    }

    /** 立即清缓存：内存全清、Room 保留收藏、其余按非收藏淘汰；通用 L2 条目一并清空 */
    suspend fun clearCaches() {
        memoryCache.clear()
        hub.clearAll() // 同步清空重验证注册表，防注册动作指向已清空的缓存/旧闭包
        val now = System.currentTimeMillis()
        db.productDao().deleteNonPinnedOlderThan(now + 1) // 阈值取未来时刻 → 清掉全部非收藏
        db.cacheEntryDao().deleteAll()
    }

    /**
     * 外部来源给的历史点写入 Room（每天 1 点：`(productId, date)` 唯一索引负责按日覆盖）。
     *
     * @param source 这批点的**实际出处**，由调用方按本轮真实拿到的东西传：
     *  慢慢买给了点 → [PriceSource.MANMANBUY]；只有星罗补点 → [PriceSource.LINKSTARS_LIST]。
     *  盯价轮次自采的点**不走这里**（收盘/至低语义在 [DayCurve.upsertFor]）。
     *
     * 同日已有本机自采行时**保留更低的 dayLow**：外部源一天只给一个数，
     * 它没有资格把今天盘中真实见过的低点抬掉（收盘点按外源覆盖，这是既定的合并口径）。
     */
    suspend fun persistHistory(productId: String, history: ManmanbuyApi.History, source: PriceSource) {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        val now = System.currentTimeMillis()
        val stored = DayCurve.collapse(db.priceHistoryDao().getByProduct(productId)).associateBy { it.date }

        fun pointFor(date: String, price: Double): PriceHistoryEntity {
            val day = stored[date]
            val dayLow = minOf(price, day?.dayLow ?: price)
            return PriceHistoryEntity(
                id = day?.id ?: 0L,
                productId = productId,
                date = date,
                price = price,
                isLowest = dayLow <= history.lowest,
                isHighest = price >= history.highest,
                source = source.name,
                dayLow = dayLow,
                recordedAt = day?.recordedAt?.takeIf { it > 0L } ?: now
            )
        }

        val points = history.points.map { pointFor(it.date, it.price) }
            .filter { it.date < today } + pointFor(today, history.current)
        db.priceHistoryDao().insertAll(points)
    }

    /**
     * 曲线出处统计（盯价页脚注）：这条线是本机盯价自采长出来的还是慢慢买给的、各自多少天。
     *
     * 读的是 `price_history` 的 `source` 列 —— 那是写入库时就定下的事实，
     * 不做事后推断（事后也推不出来，见 [PriceSource.UNRECORDED_NAME]）。
     */
    suspend fun curveProvenance(productId: String): CurveProvenance = CurveProvenance.of(db.priceHistoryDao().countDaysBySource(productId))

    // ---------- 内部：通用 kv 源构造 / singleflight ----------

    /** 非结构化结果统一走 cache_entries 表（L2 读 + 写回） */
    private fun <T : Any> kvSource(
        key: String,
        ttlMs: Long,
        codec: CacheCodec<T>,
        source: String,
        fetch: suspend () -> T?,
        cacheable: (T) -> Boolean = { true }
    ): CachedSource<T> = CachedSource(
        cache = memoryCache, health = health, tracker = tracker, hub = hub,
        key = key, ttlMs = ttlMs, codec = codec, source = source,
        fetch = { singleflight(key, fetch) },
        l2Load = {
            val entry = db.cacheEntryDao().get(key)
            val value = entry?.let { codec.decode(it.value) }
            if (entry != null && value != null) value to entry.cachedAt else null
        },
        l2Save = { value ->
            db.cacheEntryDao().upsert(
                com.pricelens.data.local.entity.CacheEntryEntity(
                    entryKey = key,
                    value = codec.encode(value),
                    cachedAt = System.currentTimeMillis(),
                    ttl = ttlMs,
                    source = source
                )
            )
        },
        cacheable = cacheable
    )

    /** 同 key 并发取数合并：胜者执行、败者 await；结束即撤槽 */
    private suspend fun <T> singleflight(key: String, block: suspend () -> T?): T? {
        while (true) {
            inflight[key]?.let { existing ->
                @Suppress("UNCHECKED_CAST")
                return runCatching { existing.await() as T? }.getOrNull()
            }
            val deferred = applicationScope.async { block() }
            if (inflight.putIfAbsent(key, deferred) == null) {
                return try {
                    deferred.await()
                } finally {
                    inflight.remove(key, deferred)
                }
            }
            deferred.cancel() // 竞态落败：已有在途航班，取消自建重试循环
        }
    }

    private fun productEntity(skuId: String, p: JdApi.JdProduct): ProductEntity {
        val now = System.currentTimeMillis()
        return ProductEntity(
            id = "jd:$skuId",
            title = p.title,
            currentPrice = p.price,
            originalPrice = p.originalPrice,
            platform = "jd",
            imageUrl = p.image,
            cachedAt = now,
            lastAccessedAt = now,
            ttl = CacheTTL.PRODUCT_INFO
        )
    }

    companion object {
        const val SOURCE_JD = "jd"
        const val SOURCE_MMB = "mmb"
        const val SOURCE_BILI = "bili"
        const val SOURCE_GWD = "gwd"
        const val SOURCE_SMZDM = "smz"
        const val SOURCE_DD = "dd"
        const val SOURCE_SH = "sh"

        /**
         * JD 商品 Room 行的最短可信写入时间（2026-09-26 00:00 +08:00）。
         * 数据源准确性修复（m 站标题解析 / 查价如实降级）之前的行可能含风控页标题，不再复用。
         */
        const val JD_PRODUCT_CACHE_FLOOR = 1_790_352_000_000L
    }
}

/**
 * [OverlayBundle.fetchedAtMs] 的口径：本次 bundle 中**最老那份数据**的抓取时刻（多来源取 min，保守）。
 *
 * @param keys 真正往 bundle 里放了数据的缓存 key（没贡献数据的 key 不参与，空表 → null）
 * @param keyAge 某个 key 已知的抓取时刻候选（L1 条目写入时刻 / L2 行 cachedAt；拿不到就给 null）
 * @return 最老的那个候选；任一参与 key 完全没有时间信息 → null = 时刻不可知，
 *         绝不退回 [nowMs] 假装"刚抓的"（F3 缺陷二：打包时刻冒充数据时刻）。
 */
internal suspend fun bundleFetchedAtMs(
    keys: List<String>,
    keyAge: suspend (String) -> List<Long?>,
    nowMs: Long = System.currentTimeMillis()
): Long? {
    if (keys.isEmpty()) return null
    // 未来时刻（设备时钟被往回调过）不是可信的年龄证据，按"问不到"处理
    val oldestPerKey = keys.map { key -> keyAge(key).filterNotNull().filter { it <= nowMs }.minOrNull() }
    val known = oldestPerKey.filterNotNull()
    // 任一参与 key 问不到时刻 → 整包时间不可知；不退回 nowMs（那等于继续说"刚刚"）
    if (known.size != keys.size) return null
    return known.min()
}

/**
 * [PriceRepository.overlayBundle] 的返回结构（A2 新增，仅浮窗消费）。
 * 空字段=缺数据，浮窗"缺什么隐什么"，不做占位。
 */
data class OverlayBundle(
    /** 确定性 ID 命中时的历史价曲线；TITLE_ONLY 恒为 null */
    val history: ManmanbuyApi.History?,
    /** 关键词券（已过相关性/门槛约束，UI 标注"仅供参考"），面额降序 ≤3 条 */
    val coupons: List<GwdangApi.Coupon>,
    /** 多平台同款报价；TITLE_ONLY 恒为空表 */
    val platforms: List<PlatformQuote>,
    /** 人读来源（"慢慢买"/"什么值得买"），浮窗灰脚注必须项 */
    val sourceLabel: String?,
    /** 本次 bundle 里最老那份数据的抓取时刻（口径见 [bundleFetchedAtMs]）；null=时刻不可知，脚注不许说"刚刚" */
    val fetchedAtMs: Long?,
    /** 本次用到的缓存 key 是否有任一处于 staleKeys 降级态 */
    val stale: Boolean
)

/** 多平台比价胶囊的一枚：平台名 + 报价 */
data class PlatformQuote(val platform: String, val price: Double)

/**
 * 历史曲线的**出处统计**（盯价页脚注用；语义细节见 DayCurve 的 KDoc）。
 *
 * 盯价页据此说明这条线是**本机盯价自采**长出来的、还是**慢慢买**给的。
 *
 * 为什么单独一个结构而不塞进 `ManmanbuyApi.History`：那个类型是对外签名
 * （[PriceRepository.getPriceHistory] / [overlayBundle] 都在用），改它会牵连编解码器。
 */
data class CurveProvenance(
    val selfWatchDays: Int = 0,
    val manmanbuyDays: Int = 0,
    val linkstarsDays: Int = 0,
    val unrecordedDays: Int = 0
) {
    /** 曲线上一共有几个采样日 */
    val totalDays: Int get() = selfWatchDays + manmanbuyDays + linkstarsDays + unrecordedDays

    /** 外部来源（慢慢买 + 星罗）给的采样日数 */
    val externalDays: Int get() = manmanbuyDays + linkstarsDays

    /**
     * true = 这条线**完全**是盯价轮次本机自采长出来的。
     *
     * 只要还有外部点或出处未记录的老行就不算：有慢慢买数据却宣称"本机自采"是假话，
     * 出处不明的老行也不能被算成自采的功劳。
     */
    val isSelfCollected: Boolean get() = selfWatchDays > 0 && externalDays == 0 && unrecordedDays == 0

    /** 脚注要列的「出处 → 天数」：按天数降序，认不出的出处一律标成"来源未记录" */
    fun dayPairs(): List<Pair<String, Int>> = buildList {
        if (manmanbuyDays > 0) add(PriceSource.MANMANBUY.label to manmanbuyDays)
        if (selfWatchDays > 0) add(PriceSource.SELF_WATCH.label to selfWatchDays)
        if (linkstarsDays > 0) add(PriceSource.LINKSTARS_LIST.label to linkstarsDays)
        if (unrecordedDays > 0) add(PriceSource.UNRECORDED_LABEL to unrecordedDays)
    }.sortedByDescending { it.second }

    /** 脚注正文（"本机盯价自采 4 天 · 慢慢买 12 天"）；没有点就是空串，UI 据此不渲染 */
    fun dayPairsText(): String = dayPairs().joinToString(" · ") { "${it.first} ${it.second} 天" }

    companion object {
        /**
         * DAO 投影 → 统计。
         *
         * 认不出的 source（含 [PriceSource.UNRECORDED_NAME] 与未来版本的枚举名）都进
         * [unrecordedDays]：宁可说"不知道出处"，也不要猜一个出处给用户看。
         */
        fun of(counts: List<SourceDayCount>): CurveProvenance {
            var self = 0
            var mmb = 0
            var linkstars = 0
            var unknown = 0
            for (row in counts) {
                when (PriceSource.fromName(row.source)) {
                    PriceSource.SELF_WATCH -> self += row.days
                    PriceSource.MANMANBUY -> mmb += row.days
                    PriceSource.LINKSTARS_LIST -> linkstars += row.days
                    else -> unknown += row.days
                }
            }
            return CurveProvenance(
                selfWatchDays = self,
                manmanbuyDays = mmb,
                linkstarsDays = linkstars,
                unrecordedDays = unknown
            )
        }
    }
}
