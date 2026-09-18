package com.pricelens.data.remote

import javax.inject.Inject
import javax.inject.Singleton

/**
 * §6.3 找券 —— 购物党已失效后的替代实现（与桌面端 gwdang.js 同步）：
 * 2026-09 实测 gwdang /tuan/search 404、/search 302 跳滑块验证，不可用。
 * 现走 什么值得买「优惠券频道」搜索（c=youhui，Googlebot UA 过瑞数 WAF），
 * 爆料正文自带「券后到手价 / 原价 / 满X减Y」，据此还原券面额与门槛。
 */
@Singleton
class GwdangApi @Inject constructor(private val client: ApiClient) {

    data class Coupon(
        val amount: Double,        // 券面额
        val threshold: Double,     // 使用条件（满 X 可用），0 = 无门槛
        val title: String,
        val url: String
    )

    private companion object {
        const val BOT_UA = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"
        val MAN_JIAN = Regex("满\\s*(\\d+(?:\\.\\d+)?)\\s*元?\\s*减\\s*(\\d+(?:\\.\\d+)?)")
        val ORIGIN_PRICE = Regex("(?:售价|原价|页面价)\\s*(\\d+(?:\\.\\d+)?)\\s*元")
    }

    suspend fun searchCoupons(keyword: String): List<Coupon> {
        val url = "https://search.smzdm.com/?c=youhui&s=" +
            java.net.URLEncoder.encode(keyword, "UTF-8") + "&v=a&order=score"
        val html = client.getHtml(url, referer = "https://www.smzdm.com/", userAgent = BOT_UA)
            ?: return emptyList()
        val doc = org.jsoup.Jsoup.parse(html)

        val coupons = mutableListOf<Coupon>()
        for (item in doc.select("#feed-main-list .feed-row-wide, #feed-main-list li, .list-man .feed-row-wide")) {
            val linkEl = item.selectFirst("h5 a, .feed-block-title a") ?: continue
            val title = linkEl.text().replace(Regex("\\s+"), " ").trim()
            if (title.isEmpty()) continue

            val priceEl = item.selectFirst(".z-highlight, .feed-block-title .z-highlight")
            val dealPrice = (priceEl?.text() ?: title)
                .replace(Regex("[^\\d.]"), "").toDoubleOrNull()

            val summary = item.text().replace(Regex("\\s+"), " ")
            val coupon = parseCoupon(summary, dealPrice) ?: continue
            coupons += Coupon(
                amount = coupon.first,
                threshold = coupon.second,
                title = title.take(60),
                url = linkEl.attr("href").let { if (it.startsWith("//")) "https:$it" else it }
            )
            if (coupons.size >= 8) break
        }
        return coupons
    }

    /** 优先显式「满X减Y」，否则用 原价−到手价 还原券面额 → (amount, threshold) */
    private fun parseCoupon(summary: String, dealPrice: Double?): Pair<Double, Double>? {
        MAN_JIAN.find(summary)?.let { m ->
            val t = m.groupValues[1].toDoubleOrNull()
            val a = m.groupValues[2].toDoubleOrNull()
            if (t != null && a != null && a > 0) return a to t
        }
        if (dealPrice != null && dealPrice > 0) {
            ORIGIN_PRICE.find(summary)?.groupValues?.get(1)?.toDoubleOrNull()?.let { origin ->
                val off = kotlin.math.round((origin - dealPrice) * 100) / 100
                if (off > 1) return off to 0.0
            }
        }
        return null
    }
}
