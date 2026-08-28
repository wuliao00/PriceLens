package com.pricelens.data.repository

import com.pricelens.data.cache.TLRUCache
import com.pricelens.data.local.AppDatabase
import com.pricelens.data.local.CacheTTL
import com.pricelens.data.local.entity.PriceHistoryEntity
import com.pricelens.data.local.entity.ProductEntity
import com.pricelens.data.local.entity.SearchRecordEntity
import com.pricelens.data.remote.BiliApi
import com.pricelens.data.remote.DangdangApi
import com.pricelens.data.remote.GwdangApi
import com.pricelens.data.remote.JdApi
import com.pricelens.data.remote.ManmanbuyApi
import com.pricelens.data.remote.ShihuoApi
import com.pricelens.data.remote.SmzdmApi
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
            db.productDao().getById("jd:$skuId")
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
        fetch = { manmanbuyApi.getHistory(productUrl) }
    ).get()

    // ---------- B站 / 优惠券 / 值得买 / 当当 / 识货（L1 → L2 → L3） ----------

    suspend fun searchVideos(keyword: String): List<BiliApi.BiliVideo> = kvSource(
        key = "bili:search:$keyword", ttlMs = CacheTTL.BILI_SEARCH,
        codec = BiliVideosCodec, source = SOURCE_BILI,
        fetch = { biliApi.searchVideos(keyword) },
        cacheable = { it.isNotEmpty() }
    ).get() ?: emptyList()

    suspend fun searchCoupons(keyword: String): List<GwdangApi.Coupon> = kvSource(
        key = "gwd:coupon:$keyword", ttlMs = CacheTTL.COUPON,
        codec = CouponsCodec, source = SOURCE_GWD,
        fetch = { gwdangApi.searchCoupons(keyword) },
        cacheable = { it.isNotEmpty() }
    ).get() ?: emptyList()

    suspend fun searchSmzdm(keyword: String): List<SmzdmApi.SmzdmPost> = kvSource(
        key = "smz:search:$keyword", ttlMs = CacheTTL.SMZDM_FEED,
        codec = SmzdmPostsCodec, source = SOURCE_SMZDM,
        fetch = { smzdmApi.searchPosts(keyword) },
        cacheable = { it.isNotEmpty() }
    ).get() ?: emptyList()

    /** 关键词搜索商品候选：当当搜索（SSR 稳定，主数据源） */
    suspend fun searchDangdang(keyword: String): List<DangdangApi.DangdangItem> = kvSource(
        key = "dd:search:$keyword", ttlMs = CacheTTL.SMZDM_FEED,
        codec = DangdangItemsCodec, source = SOURCE_DD,
        fetch = { dangdangApi.searchProducts(keyword) },
        cacheable = { it.isNotEmpty() }
    ).get() ?: emptyList()

    /** 识货搜索（社区页补充源：鞋服/数码等当当覆盖不到的品类，含国补标记） */
    suspend fun searchShihuo(keyword: String): List<ShihuoApi.ShihuoItem> = kvSource(
        key = "sh:search:$keyword", ttlMs = CacheTTL.SMZDM_FEED,
        codec = ShihuoItemsCodec, source = SOURCE_SH,
        fetch = { shihuoApi.searchProducts(keyword) },
        cacheable = { it.isNotEmpty() }
    ).get() ?: emptyList()

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

    // ---------- 个人页 / 设置页 ----------

    fun observePinned() = db.productDao().observePinned()

    fun observeTargets() = db.priceTargetDao().observeActive()

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

    /** 价格历史采样点写入 Room（每天 1 点） */
    suspend fun persistHistory(productId: String, history: ManmanbuyApi.History) {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        val points = history.points.map {
            PriceHistoryEntity(
                productId = productId, date = it.date, price = it.price,
                isLowest = it.price == history.lowest,
                isHighest = it.price == history.highest
            )
        }.filter { it.date < today } + PriceHistoryEntity(
            productId = productId, date = today,
            price = history.current,
            isLowest = history.current <= history.lowest,
            isHighest = history.current >= history.highest
        )
        db.priceHistoryDao().insertAll(points)
    }

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
    }
}
