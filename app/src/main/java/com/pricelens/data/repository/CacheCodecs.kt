package com.pricelens.data.repository

import com.pricelens.data.local.entity.ProductEntity
import com.pricelens.data.remote.BiliApi
import com.pricelens.data.remote.DangdangApi
import com.pricelens.data.remote.GwdangApi
import com.pricelens.data.remote.JdApi
import com.pricelens.data.remote.ManmanbuyApi
import com.pricelens.data.remote.ShihuoApi
import com.pricelens.data.remote.SmzdmApi
import com.pricelens.util.ContentRisk
import org.json.JSONArray
import org.json.JSONObject

/**
 * §阶段3 七个数据源的 JSON 编解码实现（org.json 手写，避免序列化框架体积）。
 * 由 PriceRepository 原私有编解码函数 1:1 移植，字段与容错策略完全一致。
 */

/** 京东商品：L1 文本与 L2 [ProductEntity] 之间的双向桥 */
object JdProductCodec : CacheCodec<JdApi.JdProduct> {
    override fun encode(value: JdApi.JdProduct): String = JSONObject().apply {
        put("id", "jd:${value.skuId}")
        put("title", value.title)
        put("price", value.price)
        put("originalPrice", value.originalPrice ?: JSONObject.NULL)
        put("platform", "jd")
        put("image", value.image)
        put("url", value.url)
    }.toString()

    override fun decode(raw: String): JdApi.JdProduct? = try {
        val o = JSONObject(raw)
        JdApi.JdProduct(
            skuId = o.optString("id").removePrefix("jd:"),
            title = o.optString("title"),
            price = o.optDouble("price"),
            originalPrice = if (o.isNull("originalPrice")) null else o.optDouble("originalPrice"),
            image = o.optString("image"),
            url = o.optString("url")
        )
    } catch (_: Exception) {
        null
    }

    /** L2 结构化实体 → 领域对象（替代原 productJson→parseProduct 两段转换） */
    fun fromEntity(e: ProductEntity): JdApi.JdProduct = JdApi.JdProduct(
        skuId = e.id.removePrefix("jd:"),
        title = e.title,
        price = e.currentPrice,
        originalPrice = e.originalPrice,
        image = e.imageUrl,
        url = if (e.platform == "jd") "https://item.jd.com/${e.id.removePrefix("jd:")}.html" else ""
    )
}

/** 慢慢买历史价曲线 */
object HistoryCodec : CacheCodec<ManmanbuyApi.History> {
    override fun encode(value: ManmanbuyApi.History): String = JSONObject().apply {
        put("current", value.current)
        put("lowest", value.lowest)
        put("highest", value.highest)
        put(
            "points",
            JSONArray().apply {
                value.points.forEach { p -> put(JSONObject().put("date", p.date).put("price", p.price)) }
            }
        )
    }.toString()

    override fun decode(raw: String): ManmanbuyApi.History? = try {
        val o = JSONObject(raw)
        val arr = o.optJSONArray("points") ?: return null
        val points = (0 until arr.length()).map {
            val p = arr.optJSONObject(it)!!
            ManmanbuyApi.PricePoint(p.optString("date"), p.optDouble("price"))
        }
        ManmanbuyApi.History(
            o.optDouble("current"),
            o.optDouble("lowest"),
            o.optDouble("highest"),
            points
        )
    } catch (_: Exception) {
        null
    }
}

/** B站视频列表（含商单/夸大宣传风险标记） */
object BiliVideosCodec : CacheCodec<List<BiliApi.BiliVideo>> {
    override fun encode(value: List<BiliApi.BiliVideo>): String = JSONArray().apply {
        value.forEach { v ->
            put(
                JSONObject().put("title", v.title).put("author", v.author)
                    .put("play", v.play).put("pic", v.pic).put("bvid", v.bvid)
                    .put("sponsored", v.risk.sponsored).put("hype", v.risk.hype)
                    .put("sw", v.risk.sponsorWord ?: JSONObject.NULL)
                    .put("hw", v.risk.hypeWord ?: JSONObject.NULL)
            )
        }
    }.toString()

    override fun decode(raw: String): List<BiliApi.BiliVideo> = try {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            BiliApi.BiliVideo(
                o.optString("title"),
                o.optString("title"),
                o.optString("author"),
                o.optLong("play"),
                o.optString("pic"),
                o.optString("bvid"),
                risk = ContentRisk(
                    sponsored = o.optBoolean("sponsored"),
                    hype = o.optBoolean("hype"),
                    sponsorWord = if (o.isNull("sw")) null else o.optString("sw"),
                    hypeWord = if (o.isNull("hw")) null else o.optString("hw")
                )
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

/** 购物党优惠券列表 */
object CouponsCodec : CacheCodec<List<GwdangApi.Coupon>> {
    override fun encode(value: List<GwdangApi.Coupon>): String = JSONArray().apply {
        value.forEach { c ->
            put(
                JSONObject().put("amount", c.amount).put("threshold", c.threshold)
                    .put("title", c.title).put("url", c.url)
            )
        }
    }.toString()

    override fun decode(raw: String): List<GwdangApi.Coupon> = try {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            GwdangApi.Coupon(
                o.optDouble("amount"),
                o.optDouble("threshold"),
                o.optString("title"),
                o.optString("url")
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

/** 值得买爆料列表 */
object SmzdmPostsCodec : CacheCodec<List<SmzdmApi.SmzdmPost>> {
    override fun encode(value: List<SmzdmApi.SmzdmPost>): String = JSONArray().apply {
        value.forEach { p ->
            put(
                JSONObject().put("title", p.title).put("price", p.price ?: JSONObject.NULL)
                    .put("url", p.url).put("image", p.image).put("mall", p.mall)
                    .put("positive", p.positive).put("negative", p.negative)
            )
        }
    }.toString()

    override fun decode(raw: String): List<SmzdmApi.SmzdmPost> = try {
        val arr = JSONArray(raw)
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
    } catch (_: Exception) {
        emptyList()
    }
}

/** 当当搜索候选（SSR 主数据源） */
object DangdangItemsCodec : CacheCodec<List<DangdangApi.DangdangItem>> {
    override fun encode(value: List<DangdangApi.DangdangItem>): String = JSONArray().apply {
        value.forEach { d ->
            put(
                JSONObject().put("sku", d.skuId).put("title", d.title)
                    .put("price", d.price).put("originalPrice", d.originalPrice ?: JSONObject.NULL)
                    .put("image", d.image).put("url", d.url)
            )
        }
    }.toString()

    override fun decode(raw: String): List<DangdangApi.DangdangItem> = try {
        val arr = JSONArray(raw)
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
    } catch (_: Exception) {
        emptyList()
    }
}

/** 识货搜索（社区页补充源，含国补标记） */
object ShihuoItemsCodec : CacheCodec<List<ShihuoApi.ShihuoItem>> {
    override fun encode(value: List<ShihuoApi.ShihuoItem>): String = JSONArray().apply {
        value.forEach { s ->
            put(
                JSONObject().put("id", s.goodsId).put("title", s.title)
                    .put("price", s.price).put("image", s.image)
                    .put("sales", s.salesInfo).put("brand", s.brand)
                    .put("subsidy", s.hasSubsidy).put("url", s.url)
            )
        }
    }.toString()

    override fun decode(raw: String): List<ShihuoApi.ShihuoItem> = try {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            ShihuoApi.ShihuoItem(
                goodsId = o.optLong("id"),
                title = o.optString("title"),
                price = o.optDouble("price"),
                image = o.optString("image"),
                salesInfo = o.optString("sales"),
                brand = o.optString("brand"),
                hasSubsidy = o.optBoolean("subsidy"),
                url = o.optString("url")
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}
