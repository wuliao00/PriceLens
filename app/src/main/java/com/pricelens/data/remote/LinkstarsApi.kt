package com.pricelens.data.remote

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 星罗好货开放平台（openapi.linkstars.com）：「历史低价榜」
 * GET /api/jd_historyLowPriceRank?apikey=..&v=1.0.0&page=N
 * 榜单为人工审核的历史最低价同款商品（每页 100 条，最多 10 页）。
 * 用途：① 按京东 SKU 命中时给出 在售价/券后价 参考；② 盯价查价兜底。
 * apikey 由用户在「设置 → 数据源凭证」自行注册填入（免费额度）。
 */
@Singleton
class LinkstarsApi @Inject constructor(private val client: ApiClient) {

    data class Deal(
        val goodsId: String,
        val title: String,
        val listPrice: Double,     // 在售价
        val couponPrice: Double    // 券后历史低价
    )

    /** 在榜单（最多 10 页）内查找指定京东 SKU；未命中或失败返回 null */
    suspend fun lookupSku(skuId: String, apikey: String): Deal? {
        for (page in 1..MAX_PAGES) {
            val deals = fetchPage(apikey, page) ?: return null
            deals.firstOrNull { it.goodsId == skuId }?.let { return it }
            if (deals.size < PAGE_SIZE) return null
        }
        return null
    }

    private suspend fun fetchPage(apikey: String, page: Int): List<Deal>? {
        val url = "https://openapi.linkstars.com/api/jd_historyLowPriceRank" +
            "?apikey=" + java.net.URLEncoder.encode(apikey, "UTF-8") +
            "&v=1.0.0&page=$page"
        val json = client.getJson(url) ?: return null
        if (json.optInt("code") != 1) return null
        val list = json.optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
        val deals = mutableListOf<Deal>()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val goodsId = o.optString("goods_id")
            if (goodsId.isEmpty()) continue
            deals += Deal(
                goodsId = goodsId,
                title = o.optString("short_extension_title").ifEmpty { o.optString("goods_brand") },
                listPrice = o.optDouble("goods_list_money", 0.0),
                couponPrice = o.optDouble("real_money", 0.0)
            )
        }
        return deals
    }

    private companion object {
        const val MAX_PAGES = 10
        const val PAGE_SIZE = 100
    }
}
