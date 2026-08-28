package com.pricelens.data.remote

import javax.inject.Inject
import javax.inject.Singleton

/**
 * §6.5 当当搜索：search.dangdang.com SSR 页（传统服务端渲染，无 JS 反爬）。
 * 纯关键词搜索的商品候选主数据源：标题 / 现价 / 原价 / 主图 / 链接。
 *
 * 注意：当当搜索链接要求关键词用 GBK 编码（UTF-8 编码会返回空结果）。
 * 什么值得买自 2026 年起对非浏览器请求返回 202 JS 探测页，故降级为兜底数据源。
 */
@Singleton
class DangdangApi @Inject constructor(private val client: ApiClient) {

    data class DangdangItem(
        val skuId: String,
        val title: String,
        val price: Double,
        val originalPrice: Double?,
        val image: String,
        val url: String
    )

    suspend fun searchProducts(keyword: String): List<DangdangItem> {
        val q = try {
            java.net.URLEncoder.encode(keyword, "GBK")
        } catch (_: Exception) {
            java.net.URLEncoder.encode(keyword, "UTF-8")
        }
        val html = client.getHtml(
            "https://search.dangdang.com/?key=$q&act=input",
            referer = "https://www.dangdang.com/"
        ) ?: return emptyList()
        val doc = org.jsoup.Jsoup.parse(html)

        val items = mutableListOf<DangdangItem>()
        // 列表结构：<ul class="bigimg"> 下每个 <li id="skuId">，含 title/price/img
        for (li in doc.select("#search_nature_rg li, ul.bigimg li")) {
            val link = li.selectFirst("p.name a[name=itemlist-title]")
                ?: li.selectFirst("a.pic") ?: continue
            val rawUrl = link.attr("href")
            if (!rawUrl.contains("product.dangdang.com")) continue
            val url = if (rawUrl.startsWith("//")) "https:$rawUrl" else rawUrl

            val title = link.attr("title").ifEmpty { link.text() }
                .replace(Regex("\\s+"), " ").trim()
            if (title.isEmpty()) continue

            // 现价 .price_n（"¥62.40"），原价 .price_r；拿不到现价的条目跳过
            val price = li.selectFirst("p.price .price_n")?.text()
                ?.replace(Regex("[^\\d.]"), "")?.toDoubleOrNull() ?: continue
            val original = li.selectFirst("p.price .price_r")?.text()
                ?.replace(Regex("[^\\d.]"), "")?.toDoubleOrNull()?.takeIf { it > price }

            val img = li.selectFirst("a.pic img")
            val image = (img?.attr("src")?.ifEmpty { img.attr("data-src") } ?: "")
                .replace(Regex("^//"), "https://")

            items += DangdangItem(
                skuId = li.attr("id"),
                title = title.take(80),
                price = price,
                originalPrice = original,
                image = image,
                url = url
            )
            if (items.size >= 10) break
        }
        return items
    }
}
