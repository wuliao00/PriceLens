package com.pricelens.data.remote

import com.pricelens.util.LogT
import com.pricelens.util.QueryRelevance
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/**
 * 识货（shihuo.cn）搜索：官方 m 站搜索接口（2026-09 纠正）。
 *
 * 旧实现请求 `www.shihuo.cn/search?keywords=`——该地址已 302 到首页，页面里
 * `__NEXT_DATA__` 是**首页热榜**（鞋服/美妆等），于是"搜索 iPhone 15"会返回
 * adidas 板鞋、洗发水等热榜商品（接口内容不准确的典型）。真搜索接口是 m 站
 * `GET m.shihuo.cn/search?page=1&page_size=30&type=goods&keywords=<kw>`，
 * 返回 `{"status":0,"data":{"list":[...]}}`（type 取值：goods / pic / shaiwu / news）。
 * 数据项字段：goods_id/title/price/img/brand_name/sales_info/labels[].type
 * （labels 中 type=PUBLIC_SUBSIDIES 为该国补商品）。
 *
 * 该接口当前对匿名请求常返回 `null`（站点侧限制/变更），因此解析必须防御：
 * 结构不符即返回空并记录原因，绝不再把首页热榜当搜索结果。
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

    /**
     * F4（2026-09-29）：走四态 `getHtmlResult`，Blocked/Network 冒泡。
     * 下面那句 `识货搜索无结果或结构异常` 日志从此只在**够着了**时才会出现——
     * 断网时以前也打这条日志，把排查带去"页面结构"方向。
     */
    suspend fun searchProducts(keyword: String): List<ShihuoItem> {
        val encoded = java.net.URLEncoder.encode(keyword, "UTF-8")
        val url = "https://m.shihuo.cn/search?page=1&page_size=30&type=goods&keywords=$encoded"
        val result = client.getHtmlResult(
            url,
            referer = "https://m.shihuo.cn/search/goods?keywords=$encoded",
            userAgent = MOBILE_UA
        )
        result.toSourceFailure()?.let { throw it }
        val body = (result as? CrawlerResult.Success)?.data ?: return emptyList()
        val all = parseSearchPage(body, keyword)
        if (all.isEmpty()) {
            LogT.w("识货搜索无结果或结构异常: [$keyword]")
            return emptyList()
        }
        val relevant = all.filter { QueryRelevance.isRelevant(keyword, it.title) }
        if (relevant.isEmpty()) LogT.w("识货 ${all.size} 条全部判定为不相关: [$keyword]")
        return relevant
    }

    companion object {
        /** 桌面版无关；这里用移动 UA 以命中 m 站接口 */
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; PLB110) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Version/4.0 Chrome/126.0.0.0 Mobile Safari/537.36"

        /**
         * 纯解析（无网络），供单测用固定 JSON 快照验证。
         * 容错读取多种字段名（站点历史上有 goods_id/id、img/image 等写法）。
         * [keyword] 仅用于生成条目跳转链接（识货无稳定商品详情页）。
         */
        fun parseSearchPage(json: String, keyword: String = ""): List<ShihuoItem> {
            val root = try {
                JSONObject(json)
            } catch (_: Exception) {
                return emptyList()
            }
            if (root.optInt("status", -1) != 0) return emptyList()
            val list = root.optJSONObject("data")?.optJSONArray("list") ?: return emptyList()

            val items = mutableListOf<ShihuoItem>()
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                val title = (o.optString("title").ifEmpty { o.optString("name") }).trim()
                val price = o.optString("price").toDoubleOrNull()
                    ?: o.optDouble("price", 0.0).takeIf { it > 0 }
                    ?: continue
                if (title.isEmpty() || price <= 0) continue

                var subsidy = false
                val labels = o.optJSONArray("labels")
                if (labels != null) {
                    for (j in 0 until labels.length()) {
                        if (labels.optJSONObject(j)?.optString("type") == "PUBLIC_SUBSIDIES") {
                            subsidy = true
                            break
                        }
                    }
                }

                items += ShihuoItem(
                    goodsId = o.optLong("goods_id").takeIf { it != 0L } ?: o.optLong("id"),
                    title = title.take(60),
                    price = price,
                    image = (o.optString("img").ifEmpty { o.optString("image") })
                        .replace(Regex("^http://"), "https://")
                        .replace(Regex("^//"), "https://"),
                    salesInfo = o.optString("sales_info").ifEmpty { o.optString("salesInfo") },
                    brand = o.optString("brand_name").ifEmpty { o.optString("brandName") },
                    hasSubsidy = subsidy,
                    url = searchPageUrl(keyword)
                )
                if (items.size >= 10) break
            }
            return items
        }

        /** 识货无稳定商品详情页链接，条目点击跳识货搜索页（沿用旧行为，带关键词） */
        private fun searchPageUrl(keyword: String): String =
            if (keyword.isEmpty()) {
                "https://m.shihuo.cn/search/goods"
            } else {
                "https://m.shihuo.cn/search/goods?keywords=" +
                    java.net.URLEncoder.encode(keyword, "UTF-8")
            }
    }
}
