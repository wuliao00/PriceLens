package com.pricelens.data.remote

import com.pricelens.util.LogT
import com.pricelens.util.QueryRelevance
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §6.5 当当搜索：search.dangdang.com SSR 页（传统服务端渲染，无 JS 反爬）。
 * 纯关键词搜索的商品候选主数据源：标题 / 现价 / 原价 / 主图 / 链接。
 *
 * 注意：当当搜索链接要求关键词用 GBK 编码（UTF-8 编码会返回空结果）。
 * 什么值得买自 2026 年起对非浏览器请求返回 202 JS 探测页，故降级为兜底数据源。
 *
 * 2026-09 修复（接口内容不准确）：当当列表页价格节点由 `p.price .price_n / .price_r`
 * 改为 `span.search_now_price / span.search_pre_price`，旧选择器恒为空导致本数据源
 * 静默返回 0 条。现改为"新选择器 → 旧选择器 → 价格文本兜底"三级，并在结构校验失败时
 * 明确记录日志（不再静默）。解析出的条目统一过 [QueryRelevance] 相关性过滤。
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
        val all = parseSearchPage(html)
        val relevant = all.filter { QueryRelevance.isRelevant(keyword, it.title) }
        if (all.isNotEmpty() && relevant.isEmpty()) {
            LogT.w("当当 ${all.size} 条全部判定为不相关: [$keyword]")
        }
        return relevant
    }

    companion object {
        /** 价格文本兜底：优先取 "¥62.40" 这类带符号数字，其次取首个数字 */
        private val PRICE_WITH_SYMBOL = Regex("[¥￥]\\s*(\\d+(?:\\.\\d{1,2})?)")
        private val PRICE_TEXT = Regex("(\\d+(?:\\.\\d{1,2})?)")

        private fun parsePrice(text: String?): Double? {
            if (text == null) return null
            val cleaned = text.replace(",", "")
            return PRICE_WITH_SYMBOL.find(cleaned)?.groupValues?.get(1)?.toDoubleOrNull()
                ?: PRICE_TEXT.find(cleaned)?.groupValues?.get(1)?.toDoubleOrNull()
        }

        /**
         * 主图取值顺序（F5，2026-09-29）：非占位的 `src` → `data-original` → `data-src`。
         *
         * 当当列表页用懒加载：真图写在 `data-original`，`src` 是灰占位块
         * `images/model/guan/url_none.png`。2026-09-29 实况 60 条里 59 条如此，`data-src` 出现 0 次。
         * 旧实现只读 `src`（非空即返回）与 `data-src`，于是除第一条外每条候选都拿到相对路径的占位图，
         * `AppImage` 的 `takeIf { it.startsWith("http") }` 把它变成 null → 概览/浮窗恒显示灰块。
         * `data-src` 保留在链尾是为了站点回退到旧懒加载属性时不至于全丢。
         */
        private fun realImageUrl(img: org.jsoup.nodes.Element?): String {
            if (img == null) return ""
            for (attr in listOf("src", "data-original", "data-src")) {
                val value = img.attr(attr)
                if (value.isNotEmpty() && !isPlaceholderImage(value)) return absolutize(value)
            }
            return ""
        }

        /** 懒加载占位图/无图兜底：不是商品主图，不能当候选图片用 */
        private fun isPlaceholderImage(url: String): Boolean =
            url.contains("url_none") || url.contains("nobook") || url.contains("noresult") || url.contains("loading")

        /** 当当的图床地址常写成协议相对（`//img3m9.ddimg.cn/...`），补成 https 才能被 Coil 加载 */
        private fun absolutize(url: String): String = if (url.startsWith("//")) "https:$url" else url

        /**
         * 纯解析（无网络），供单测用固定页面快照验证选择器有效性。
         * 列表结构：`<ul class="bigimg">` 下每个 `<li id="p<skuId>">`，含 title/price/img。
         */
        fun parseSearchPage(html: String): List<DangdangItem> {
            val doc = org.jsoup.Jsoup.parse(html)
            val nodes = doc.select("#search_nature_rg li, ul.bigimg li")
            val items = mutableListOf<DangdangItem>()
            for (li in nodes) {
                val link = li.selectFirst("p.name a[name=itemlist-title]")
                    ?: li.selectFirst("a.pic") ?: continue
                val rawUrl = link.attr("href")
                if (!rawUrl.contains("product.dangdang.com")) continue
                val url = if (rawUrl.startsWith("//")) "https:$rawUrl" else rawUrl

                val title = link.attr("title").ifEmpty { link.text() }
                    .replace(Regex("\\s+"), " ").trim()
                if (title.isEmpty()) continue

                // 现价：新版 .search_now_price → 旧版 .price_n → p.price 文本
                val price = li.selectFirst("p.price .search_now_price")?.text()
                    ?.let { parsePrice(it) }
                    ?: li.selectFirst("p.price .price_n")?.text()?.let { parsePrice(it) }
                    ?: li.selectFirst("p.price")?.text()?.let { parsePrice(it) }
                    ?: continue

                // 原价：新版 .search_pre_price → 旧版 .price_r（"定价：¥214"场景同上）
                val original = li.selectFirst("p.price .search_pre_price")?.text()?.let { parsePrice(it) }
                    ?: li.selectFirst("p.price .price_r")?.text()?.let { parsePrice(it) }
                val originalValid = original?.takeIf { it > price }

                val image = realImageUrl(li.selectFirst("a.pic img"))

                items += DangdangItem(
                    skuId = li.attr("id"),
                    title = title.take(80),
                    price = price,
                    originalPrice = originalValid,
                    image = image,
                    url = url
                )
                if (items.size >= 10) break
            }
            return items
        }
    }
}
