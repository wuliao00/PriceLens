package com.pricelens.data.remote

import javax.inject.Inject
import javax.inject.Singleton

/**
 * §6.3 找券：购物党 + 京东联盟。此处实现购物党渠道，
 * 返回券面额/条件/链接；到手价 = 原价 − Σ优惠券 − 满减 − 红包 由 UI 层计算展示。
 */
@Singleton
class GwdangApi @Inject constructor(private val client: ApiClient) {

    data class Coupon(
        val amount: Double,        // 券面额
        val threshold: Double,     // 使用条件（满 X 可用），0 = 无门槛
        val title: String,
        val url: String
    )

    suspend fun searchCoupons(keyword: String): List<Coupon> {
        val url = "https://www.gwdang.com/tuan/search?q=" +
            java.net.URLEncoder.encode(keyword, "UTF-8")
        val html = client.getHtml(url, referer = "https://www.gwdang.com/") ?: return emptyList()
        val doc = org.jsoup.Jsoup.parse(html)

        val coupons = mutableListOf<Coupon>()
        // 尽力解析：券卡片通常带 "券" 字样与 ¥ 面额
        for (card in doc.select("div[data-search-id], .goods-item, .card")) {
            val text = card.text()
            val amount = Regex("[¥￥]\\s*(\\d+(?:\\.\\d+)?)").find(text)?.groupValues?.get(1)
                ?.toDoubleOrNull() ?: continue
            val threshold = Regex("满\\s*(\\d+(?:\\.\\d+)?)").find(text)?.groupValues?.get(1)
                ?.toDoubleOrNull() ?: 0.0
            val link = card.selectFirst("a[href]")?.absUrl("href") ?: ""
            if (amount > 0) {
                coupons += Coupon(
                    amount = amount,
                    threshold = threshold,
                    title = card.selectFirst(".title, .goods-title")?.text()?.take(40)
                        ?: text.take(30),
                    url = link
                )
            }
            if (coupons.size >= 10) break
        }
        return coupons
    }
}
