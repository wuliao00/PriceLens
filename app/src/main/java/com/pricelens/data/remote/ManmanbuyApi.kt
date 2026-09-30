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

    suspend fun getHistory(productUrl: String, cookie: String? = null): History? {
        val body = """{"methodName":"getHistoryTrend","p_url":"$productUrl"}"""
        val text = client.postJson(
            "https://apapia-history.manmanbuy.com/HistoryLowest.ashx",
            body
        )

        val points = mutableListOf<PricePoint>()
        if (text != null) {
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
        }

        // 公开 JSON 接口 2026-09 起下线；用户在设置里自填登录 Cookie 时走移动端 SSR 页
        if (points.isEmpty() && !cookie.isNullOrBlank()) {
            points += fetchViaCookie(productUrl, cookie)
        }
        if (points.isEmpty()) return null
        return finalize(points)
    }

    /**
     * 移动端历史价页 SSR 通道：内嵌 flot 序列 `[Date.UTC(y,m,d),price]`。
     *
     * 改造前这里走 `client.getHtml`（`CrawlerResult` → `String?` 的兼容桥），于是
     * 「网络不可达」「Cookie 失效被 302 弹到人机验证页」「该商品真的没有历史数据」
     * 三种结局全被压成同一个空列表，用户粘完 Cookie 只能看到"无曲线"。
     * 现在改用 [ApiClient.getHtmlResult] 并把结局交给 [classifyHistoryPage] 分：
     * 本方法只取价格点（[getHistory] 对外的 `History?` 语义因此不变），
     * 要给用户交代"为什么没曲线"时走 [probeCookie]。
     */
    private suspend fun fetchViaCookie(productUrl: String, cookie: String): List<PricePoint> =
        (probeHistory(productUrl, cookie) as? CookieProbe.Ok)?.points ?: emptyList()

    /** 设置页「检测 Cookie」：同一个请求，但把四种结局原样交出去 */
    suspend fun probeCookie(productUrl: String, cookie: String): CookieProbe =
        if (cookie.isBlank()) CookieProbe.Unreachable("empty-cookie") else probeHistory(productUrl, cookie)

    private suspend fun probeHistory(productUrl: String, cookie: String): CookieProbe {
        val url = HISTORY_URL_PREFIX + java.net.URLEncoder.encode(productUrl, "UTF-8")
        return when (val result = client.getHtmlResult(url, referer = HISTORY_REFERER, cookie = cookie)) {
            is CrawlerResult.Success ->
                when (val page = classifyHistoryPage(result.data)) {
                    is HistoryPage.Points -> CookieProbe.Ok(page.points)
                    is HistoryPage.Captcha ->
                        CookieProbe.Captcha("aliVal/AliyunCaptcha markers, body=${result.data.length}")
                    is HistoryPage.NoData ->
                        CookieProbe.NoData("no Date.UTC series, body=${result.data.length}")
                }
            is CrawlerResult.Blocked -> CookieProbe.Unreachable("blocked: ${result.reason}")
            is CrawlerResult.Empty -> CookieProbe.Unreachable("empty body")
            is CrawlerResult.Network ->
                CookieProbe.Unreachable("network: ${result.cause.javaClass.simpleName}")
        }
    }

    companion object {
        /** 2026-09-30 真机验证过能打开的京东商品页；探针 URL 固定，两次检测的结论才可比 */
        const val PROBE_PRODUCT_URL: String = "https://item.jd.com/100012043978.html"

        private const val HISTORY_URL_PREFIX =
            "https://tool.manmanbuy.com/m/history.aspx?type=history_mobile_tool&url="
        private const val HISTORY_REFERER = "https://tool.manmanbuy.com/HistoryLowest.aspx"
    }

    private fun finalize(points: List<PricePoint>): History {
        // 按天去重（降采样），保持曲线平滑
        val deduped = mutableListOf<PricePoint>()
        for (p in points.sortedBy { it.date }) {
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
