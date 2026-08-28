package com.pricelens.data.remote

import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/**
 * 识货（shihuo.cn）搜索：Next.js SSR 页的 __NEXT_DATA__ JSON 解析，无需签名/逆向。
 * 数据结构：props.pageProps.data.data.list[] → goods_id/title/img/price/sales_info/labels
 * labels 中 type=PUBLIC_SUBSIDIES 表示该国补商品（找券场景的补贴线索）。
 * 识货无可用网页详情链接，条目点击跳转识货搜索页。
 */
@Singleton
class ShihuoApi @Inject constructor(private val client: ApiClient) {

    data class ShihuoItem(
        val goodsId: Long,
        val title: String,
        val price: Double,
        val image: String,
        val salesInfo: String = "",   // 如 "3.21w人付款"
        val brand: String = "",       // 如 "Apple/苹果"
        val hasSubsidy: Boolean = false,  // 国家补贴标记
        val url: String = ""
    )

    suspend fun searchProducts(keyword: String): List<ShihuoItem> {
        val encoded = java.net.URLEncoder.encode(keyword, "UTF-8")
        val url = "https://www.shihuo.cn/search?keywords=$encoded"
        val html = client.getHtml(url, referer = "https://www.shihuo.cn/") ?: return emptyList()

        val m = NEXT_DATA_REGEX.find(html) ?: return emptyList()
        val root = try { JSONObject(m.groupValues[1]) } catch (_: Exception) { return emptyList() }
        val list = root.optJSONObject("props")
            ?.optJSONObject("pageProps")
            ?.optJSONObject("data")
            ?.optJSONObject("data")
            ?.optJSONArray("list") ?: return emptyList()

        val items = mutableListOf<ShihuoItem>()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val title = o.optString("title").trim()
            val price = o.optString("price").toDoubleOrNull() ?: continue
            if (title.isEmpty() || price <= 0) continue

            val labels = o.optJSONArray("labels")
            var subsidy = false
            if (labels != null) {
                for (j in 0 until labels.length()) {
                    if (labels.optJSONObject(j)?.optString("type") == "PUBLIC_SUBSIDIES") {
                        subsidy = true
                        break
                    }
                }
            }

            items += ShihuoItem(
                goodsId = o.optLong("goods_id"),
                title = title.take(60),
                price = price,
                image = o.optString("img").replace(Regex("^http://"), "https://"),
                salesInfo = o.optString("sales_info"),
                brand = o.optString("brand_name"),
                hasSubsidy = subsidy,
                url = url   // 跳识货搜索页
            )
            if (items.size >= 10) break
        }
        return items
    }

    companion object {
        private val NEXT_DATA_REGEX = Regex(
            "<script id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>",
            RegexOption.DOT_MATCHES_ALL
        )
    }
}
