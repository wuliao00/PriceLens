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
import com.pricelens.data.remote.SmzdmApi
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject

/**
 * §4.1 三级缓存编排：
 *   L1 TLRUCache 内存（8MB，0ms）→ L2 Room（10MB，<5ms）→ L3 网络（OkHttp 5MB 缓存兜底）
 * 断网时 stale 数据直接返回（验收：断网重启仍可查看上次缓存数据）。
 */
@Singleton
class PriceRepository @Inject constructor(
    private val db: AppDatabase,
    private val memoryCache: TLRUCache<String>,   // 与 CacheCleanupWorker 共享同一单例
    private val jdApi: JdApi,
    private val manmanbuyApi: ManmanbuyApi,
    private val biliApi: BiliApi,
    private val gwdangApi: GwdangApi,
    private val smzdmApi: SmzdmApi,
    private val dangdangApi: DangdangApi,
) {

    // ---------- 商品（L1 → L2 → L3，写回） ----------

    suspend fun getJdProduct(skuId: String): JdApi.JdProduct? {
        val key = "jd:product:$skuId"
        memoryCache.get(key)?.let { return parseProduct(it) }
        val entity = db.productDao().getById("jd:$skuId")?.let { fresh(it) ?: it }
        if (entity != null) {
            memoryCache.put(key, productJson(entity), CacheTTL.PRODUCT_INFO)
            return parseProduct(productJson(entity))
        }
        val remote = jdApi.getProduct(skuId) ?: return null
        val now = System.currentTimeMillis()
        val newEntity = ProductEntity(
            id = "jd:$skuId",
            title = remote.title,
            currentPrice = remote.price,
            originalPrice = remote.originalPrice,
            platform = "jd",
            imageUrl = remote.image,
            cachedAt = now,
            lastAccessedAt = now,
            ttl = CacheTTL.PRODUCT_INFO
        )
        db.productDao().upsert(newEntity)
        memoryCache.put(key, productJson(newEntity), CacheTTL.PRODUCT_INFO)
        return remote
    }

    // ---------- 历史价格（写回 Room 采样点） ----------

    suspend fun getPriceHistory(productUrl: String): ManmanbuyApi.History? {
        val key = "mmb:history:${productUrl.hashCode()}"
        memoryCache.get(key)?.let { return parseHistory(it) }
        val remote = manmanbuyApi.getHistory(productUrl) ?: return null
        memoryCache.put(key, historyJson(remote), CacheTTL.PRICE_HISTORY)
        return remote
    }

    // ---------- B站 / 优惠券 / 值得买（L1 + L3，短 TTL） ----------

    suspend fun searchVideos(keyword: String): List<BiliApi.BiliVideo> {
        val key = "bili:search:$keyword"
        memoryCache.get(key)?.let { return parseVideos(it) }
        val videos = biliApi.searchVideos(keyword)
        if (videos.isNotEmpty()) {
            memoryCache.put(key, videosJson(videos), CacheTTL.BILI_SEARCH)
        }
        return videos
    }

    suspend fun searchCoupons(keyword: String): List<GwdangApi.Coupon> {
        val key = "gwd:coupon:$keyword"
        memoryCache.get(key)?.let { return parseCoupons(it) }
        val coupons = gwdangApi.searchCoupons(keyword)
        if (coupons.isNotEmpty()) {
            memoryCache.put(key, couponsJson(coupons), CacheTTL.COUPON)
        }
        return coupons
    }

    suspend fun searchSmzdm(keyword: String): List<SmzdmApi.SmzdmPost> {
        val key = "smz:search:$keyword"
        memoryCache.get(key)?.let { return parsePosts(it) }
        val posts = smzdmApi.searchPosts(keyword)
        if (posts.isNotEmpty()) {
            memoryCache.put(key, postsJson(posts), CacheTTL.SMZDM_FEED)
        }
        return posts
    }

    /** 关键词搜索商品候选：当当搜索（SSR 稳定，主数据源） */
    suspend fun searchDangdang(keyword: String): List<DangdangApi.DangdangItem> {
        val key = "dd:search:$keyword"
        memoryCache.get(key)?.let { return parseDangdang(it) }
        val items = dangdangApi.searchProducts(keyword)
        if (items.isNotEmpty()) {
            memoryCache.put(key, dangdangJson(items), CacheTTL.SMZDM_FEED)
        }
        return items
    }

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
        if (pinned) memoryCache.pin("jd:product:${id.removePrefix("jd:")}")
        else memoryCache.unpin("jd:product:${id.removePrefix("jd:")}")
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

    /** 立即清缓存：内存全清、Room 保留收藏、其余按非收藏淘汰 */
    suspend fun clearCaches() {
        memoryCache.clear()
        val now = System.currentTimeMillis()
        db.productDao().deleteNonPinnedOlderThan(now + 1)   // 阈值取未来时刻 → 清掉全部非收藏
    }

    // ---------- JSON 编解码（org.json 手写，避免序列化框架体积） ----------

    private fun fresh(e: ProductEntity): ProductEntity? =
        if (System.currentTimeMillis() - e.cachedAt <= e.ttl) e else null

    private fun productJson(e: ProductEntity): String = JSONObject().apply {
        put("id", e.id); put("title", e.title); put("price", e.currentPrice)
        put("originalPrice", e.originalPrice ?: JSONObject.NULL)
        put("platform", e.platform); put("image", e.imageUrl); put("url",
            if (e.platform == "jd") "https://item.jd.com/${e.id.removePrefix("jd:")}.html" else "")
    }.toString()

    private fun parseProduct(json: String): JdApi.JdProduct? = try {
        val o = JSONObject(json)
        JdApi.JdProduct(
            skuId = o.optString("id").removePrefix("jd:"),
            title = o.optString("title"),
            price = o.optDouble("price"),
            originalPrice = if (o.isNull("originalPrice")) null else o.optDouble("originalPrice"),
            image = o.optString("image"),
            url = o.optString("url")
        )
    } catch (_: Exception) { null }

    private fun historyJson(h: ManmanbuyApi.History): String = JSONObject().apply {
        put("current", h.current); put("lowest", h.lowest); put("highest", h.highest)
        put("points", JSONArray().apply {
            h.points.forEach { p -> put(JSONObject().put("date", p.date).put("price", p.price)) }
        })
    }.toString()

    private fun parseHistory(json: String): ManmanbuyApi.History? = try {
        val o = JSONObject(json)
        val arr = o.optJSONArray("points") ?: return null
        val points = (0 until arr.length()).map {
            val p = arr.optJSONObject(it)!!
            ManmanbuyApi.PricePoint(p.optString("date"), p.optDouble("price"))
        }
        ManmanbuyApi.History(
            o.optDouble("current"), o.optDouble("lowest"),
            o.optDouble("highest"), points
        )
    } catch (_: Exception) { null }

    private fun videosJson(list: List<BiliApi.BiliVideo>): String = JSONArray().apply {
        list.forEach { v ->
            put(JSONObject().put("title", v.title).put("author", v.author)
                .put("play", v.play).put("pic", v.pic).put("bvid", v.bvid))
        }
    }.toString()

    private fun parseVideos(json: String): List<BiliApi.BiliVideo> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            BiliApi.BiliVideo(
                o.optString("title"), o.optString("title"), o.optString("author"),
                o.optLong("play"), o.optString("pic"), o.optString("bvid")
            )
        }
    } catch (_: Exception) { emptyList() }

    private fun couponsJson(list: List<GwdangApi.Coupon>): String = JSONArray().apply {
        list.forEach { c ->
            put(JSONObject().put("amount", c.amount).put("threshold", c.threshold)
                .put("title", c.title).put("url", c.url))
        }
    }.toString()

    private fun parseCoupons(json: String): List<GwdangApi.Coupon> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            GwdangApi.Coupon(
                o.optDouble("amount"), o.optDouble("threshold"),
                o.optString("title"), o.optString("url")
            )
        }
    } catch (_: Exception) { emptyList() }

    private fun postsJson(list: List<SmzdmApi.SmzdmPost>): String = JSONArray().apply {
        list.forEach { p ->
            put(JSONObject().put("title", p.title).put("price", p.price ?: JSONObject.NULL)
                .put("url", p.url).put("image", p.image).put("mall", p.mall)
                .put("positive", p.positive).put("negative", p.negative))
        }
    }.toString()

    private fun dangdangJson(list: List<DangdangApi.DangdangItem>): String = JSONArray().apply {
        list.forEach { d ->
            put(JSONObject().put("sku", d.skuId).put("title", d.title)
                .put("price", d.price).put("originalPrice", d.originalPrice ?: JSONObject.NULL)
                .put("image", d.image).put("url", d.url))
        }
    }.toString()

    private fun parseDangdang(json: String): List<DangdangApi.DangdangItem> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            DangdangApi.DangdangItem(
                skuId = o.optString("sku"),
                title = o.optString("title"),
                price = o.optDouble("price"),
                originalPrice = if (o.isNull("originalPrice")) null else o.optDouble("originalPrice"),
                image = o.optString("image"),
                url = o.optString("url")
            )
        }
    } catch (_: Exception) { emptyList() }

    private fun parsePosts(json: String): List<SmzdmApi.SmzdmPost> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            SmzdmApi.SmzdmPost(
                title = o.optString("title"),
                price = if (o.isNull("price")) null else o.optDouble("price"),
                url = o.optString("url"),
                image = o.optString("image"),
                mall = o.optString("mall"),
                positive = o.optInt("positive"),
                negative = o.optInt("negative")
            )
        }
    } catch (_: Exception) { emptyList() }

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
}
