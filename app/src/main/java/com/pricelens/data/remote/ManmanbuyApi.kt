package com.pricelens.data.remote

import javax.inject.Inject
import javax.inject.Singleton

/**
 * §6.2 慢慢买：历史价格。走 App 端公开接口（与桌面版一致）：
 *   POST apapia-history.manmanbuy.com/HistoryLowest.ashx
 *   body {"methodName":"getHistoryTrend","p_url":"https://item.jd.com/xxx.html"}
 * 响应兼容两代结构：新 singlePriceTimeLine.timeline[] / 旧 bjDate[]+bjPrice[]。
 */
@Singleton
class ManmanbuyApi @Inject constructor(private val client: ApiClient) {

    data class PricePoint(val date: String, val price: Double)

    data class History(
        val current: Double,
        val lowest: Double,
        val highest: Double,
        val points: List<PricePoint>
    )

    suspend fun getHistory(productUrl: String): History? {
        val body = """{"methodName":"getHistoryTrend","p_url":"$productUrl"}"""
        val text = client.postJson(
            "https://apapia-history.manmanbuy.com/HistoryLowest.ashx",
            body
        ) ?: return null

        val points = mutableListOf<PricePoint>()
        // 新版：singlePriceTimeLine.timeline = [{pubDate, price}]
        points += extractTimeline(text)

        // 旧版：bjDate[] + bjPrice[] 平行数组
        if (points.isEmpty()) {
            val dates = text.optJSONArray("bjDate")
            val prices = text.optJSONArray("bjPrice")
            if (dates != null && prices != null) {
                val n = minOf(dates.length(), prices.length())
                for (i in 0 until n) {
                    val p = prices.optDouble(i, 0.0)
                    if (p > 0) points += PricePoint(dates.optString(i).take(10), p)
                }
            }
        }
        if (points.isEmpty()) return null

        // 按天去重（降采样），保持曲线平滑
        val deduped = mutableListOf<PricePoint>()
        for (p in points) {
            if (deduped.isEmpty() || deduped.last().date != p.date) deduped += p
        }
        val prices = deduped.map { it.price }
        return History(
            current = prices.last(),
            lowest = prices.min(),
            highest = prices.max(),
            points = deduped
        )
    }

    private fun extractTimeline(json: org.json.JSONObject): List<PricePoint> {
        val result = mutableListOf<PricePoint>()
        val timeline = json.optJSONObject("singlePriceTimeLine")?.optJSONArray("timeline") ?: return result
        for (i in 0 until timeline.length()) {
            val item = timeline.optJSONObject(i) ?: continue
            val price = item.optDouble("price", 0.0)
            if (price > 0) result += PricePoint(item.optString("pubDate").take(10), price)
        }
        return result
    }
}
