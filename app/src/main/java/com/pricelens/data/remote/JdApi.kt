package com.pricelens.data.remote

import javax.inject.Inject
import javax.inject.Singleton

/**
 * §7.2 京东：p.3.cn 公开批量查价（无需登录）+ item.jd.com SSR 页面解析标题/图片。
 * 与桌面版（pricelens Electron）保持同一套接口与降级策略。
 */
@Singleton
class JdApi @Inject constructor(private val client: ApiClient) {

    data class JdProduct(
        val skuId: String,
        val title: String,
        val price: Double,
        val originalPrice: Double?,
        val image: String,
        val url: String
    )

    /** p.3.cn 批量查价：J_100012043978,J_... → 顶层数组 [{id,p,op,m}] */
    suspend fun getPrices(skuIds: List<String>): Map<String, Pair<Double, Double?>> {
        if (skuIds.isEmpty()) return emptyMap()
        val q = skuIds.joinToString(",") { "J_$it" }
        // ApiClient 返回的是 JSONObject，p.3.cn 是顶层数组 → 这里单独走 HTML 通道拿原文
        val body = client.getHtml(
            "https://p.3.cn/prices/mgets?skuIds=${java.net.URLEncoder.encode(q, "UTF-8")}",
            referer = "https://item.jd.com/"
        ) ?: return emptyMap()
        val arr = try {
            org.json.JSONArray(body)
        } catch (_: Exception) {
            return emptyMap()
        }
        val result = mutableMapOf<String, Pair<Double, Double?>>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val id = item.optString("id").removePrefix("J_")
            val p = item.optDouble("p", 0.0)
            val op = item.optDouble("op", 0.0).takeIf { it > 0 }
            if (p > 0) result[id] = p to op
        }
        return result
    }

    /** item.jd.com SSR 页 → 标题 + 主图（尽力解析，失败给占位） */
    suspend fun getProduct(skuId: String): JdProduct? {
        val prices = getPrices(listOf(skuId))[skuId] ?: return null
        val html = client.getHtml("https://item.jd.com/$skuId.html", referer = "https://www.jd.com/")
        var title = "京东商品 $skuId"
        var image = ""
        if (html != null) {
            val doc = org.jsoup.Jsoup.parse(html)
            doc.selectFirst("div.sku-name, .itemInfo-wrap .sku-name, title")?.let {
                title = it.text().trim().ifEmpty { title }
            }
            doc.selectFirst("img#spec-img, #J-m-img img, .main-img img")?.let {
                image = it.absUrl("data-origin").ifEmpty { it.absUrl("src") }
            }
        }
        return JdProduct(
            skuId = skuId,
            title = title.take(80),
            price = prices.first,
            originalPrice = prices.second,
            image = image,
            url = "https://item.jd.com/$skuId.html"
        )
    }
}
