package com.pricelens.data.remote

import javax.inject.Inject
import javax.inject.Singleton

/**
 * §6.4 什么值得买：search.smzdm.com SSR 页解析（与桌面版同选择器、同兜底策略）。
 * 关键词搜索时，第一条带价格的爆料会被用作商品候选（桌面版 searchProducts 同款流程）。
 */
@Singleton
class SmzdmApi @Inject constructor(private val client: ApiClient) {

    data class SmzdmPost(
        val title: String,
        val price: Double?,
        val url: String,
        val image: String = "",
        val mall: String = "",
        val positive: Int = 0,      // 值/不不值投票需进文章页拉取，列表页先置 0
        val negative: Int = 0
    )

    suspend fun searchPosts(keyword: String): List<SmzdmPost> {
        val url = "https://search.smzdm.com/?c=home&s=" +
            java.net.URLEncoder.encode(keyword, "UTF-8") + "&v=b&order=time"
        val html = client.getHtml(url, referer = "https://www.smzdm.com/")
            ?: return emptyList()
        val doc = org.jsoup.Jsoup.parse(html)

        val posts = mutableListOf<SmzdmPost>()
        // 列表结构随版本变动，多组选择器兜底（与桌面版一致）
        val items = doc.select(
            "#feed-main-list .feed-row-wide, #feed-main-list li, .list-man .feed-row-wide"
        )
        for (item in items) {
            val linkEl = item.selectFirst("h5 a, .feed-block-title a") ?: continue
            val rawUrl = linkEl.attr("href")
            if (rawUrl.isEmpty()) continue
            val title = linkEl.text().replace(Regex("\\s+"), " ").trim()
            if (title.isEmpty()) continue

            // 标题节点内常含价格高亮 span；有则用之，无则从标题文本提取（"5999元"）
            val priceEl = item.selectFirst(".z-highlight, .feed-block-title .z-highlight")
            val price = priceEl?.text()?.replace(Regex("[^\\d.]"), "")?.toDoubleOrNull()
                ?: extractPrice(title)

            val img = item.selectFirst("img")
            val image = (img?.attr("data-src")?.ifEmpty { img.attr("src") } ?: "")
                .replace(Regex("^//"), "https://")
            val mall = (item.selectFirst(".feed-block-info a.z-highlight, .feed-block-extras span")
                ?.text()?.trim() ?: "").ifEmpty { "未知渠道" }

            posts += SmzdmPost(
                title = title.take(60),
                price = price,
                url = if (rawUrl.startsWith("//")) "https:$rawUrl" else rawUrl,
                image = image,
                mall = mall ?: "未知渠道"
            )
            if (posts.size >= 10) break
        }
        return posts
    }

    /** "iPhone 16 128g 5999元" → 5999（与桌面版 extractPrice 同规则） */
    private fun extractPrice(text: String): Double? {
        val m = Regex("(?:¥|￥|\\s)(\\d{2,6}(?:\\.\\d{1,2})?)(?:元|\\b)").find(text)
            ?: return null
        return m.groupValues[1].toDoubleOrNull()
    }
}
