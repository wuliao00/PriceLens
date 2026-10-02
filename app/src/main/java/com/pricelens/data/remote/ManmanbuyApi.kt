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
     * 移动端历史价页 SSR 通道。**2026-10-02 实测降级为兜底**：该页对任何程序化请求都先弹
     * 阿里云滑块（带不带 Cookie 均为 4,135 字节验证页），真正取数走 [MmbPageSeries] 那套
     * 应用内 WebView 方案（设置页「用网页取历史价」）。这里保留原样：万一站点以后放开，
     * `Ok` 分支能立刻拿到点，不需要再改结构。
     */
    private suspend fun fetchViaCookie(productUrl: String, cookie: String): List<PricePoint> =
        (probeHistory(productUrl, cookie) as? CookieProbe.Ok)?.points ?: emptyList()

    /** 设置页「检测 Cookie」：把每种结局原样交出去（判定优先级见 [probeOutcome]） */
    suspend fun probeCookie(productUrl: String, cookie: String): CookieProbe =
        if (cookie.isBlank()) CookieProbe.LoggedOut("empty-cookie") else probeHistory(productUrl, cookie)

    /**
     * 账号侧的**权威**判定：登录了吗？授权京东了吗？
     *
     * 这条 JSON 是 2026-10-02 实测出来的（页面脚本自己就这么调）：
     * 已登录+已授权 `{"data":{"auth":true}}`；未授权 `{"code":1,"msg":"未授权",…authUrl}`；
     * 未登录 `{"code":0,"msg":"请先登录","data":{"login":0}}`。
     * 网络层失败一律 [JdAuthState.Unknown]——**不许把"问不到"说成任何一种结论**。
     */
    suspend fun checkJdAuth(cookie: String): JdAuthState {
        if (cookie.isBlank()) return JdAuthState.LoggedOut
        return when (val result = client.getHtmlResult(JD_AUTH_CHECK_URL, referer = HISTORY_REFERER, cookie = cookie)) {
            is CrawlerResult.Success -> parseJdAuthState(result.data)
            is CrawlerResult.Blocked -> JdAuthState.Unknown("blocked: ${result.reason}")
            is CrawlerResult.Empty -> JdAuthState.Unknown("empty body")
            is CrawlerResult.Network -> JdAuthState.Unknown("network: ${result.cause.javaClass.simpleName}")
        }
    }

    private suspend fun probeHistory(productUrl: String, cookie: String): CookieProbe {
        val auth = checkJdAuth(cookie)
        // 未登录 / 未授权：结论已由账号侧给出，不必再跑那条"注定被弹验证码"的移动页
        // （少一次请求，也少一次把用户引向错误归因的机会）
        if (auth is JdAuthState.LoggedOut || auth is JdAuthState.NotAuthorized) return probeOutcome(auth, null)
        val page = when (val result = client.getHtmlResult(mobileUrl(productUrl), referer = HISTORY_REFERER, cookie = cookie)) {
            is CrawlerResult.Success -> classifyHistoryPage(result.data)
            is CrawlerResult.Blocked -> return CookieProbe.Unreachable("blocked: ${result.reason}")
            is CrawlerResult.Empty -> return CookieProbe.Unreachable("empty body")
            is CrawlerResult.Network ->
                return CookieProbe.Unreachable("network: ${result.cause.javaClass.simpleName}")
        }
        return probeOutcome(auth, page)
    }

    private fun mobileUrl(productUrl: String): String =
        HISTORY_URL_PREFIX + java.net.URLEncoder.encode(productUrl, "UTF-8")

    companion object {
        /** 2026-09-30 真机验证过能打开的京东商品页；探针 URL 固定，两次检测的结论才可比 */
        const val PROBE_PRODUCT_URL: String = "https://item.jd.com/100012043978.html"

        private const val HISTORY_URL_PREFIX =
            "https://tool.manmanbuy.com/m/history.aspx?type=history_mobile_tool&url="
        private const val HISTORY_REFERER = "https://tool.manmanbuy.com/HistoryLowest.aspx"

        /** 账号侧权威判定：登录/授权京东的状态（2026-10-02 实测，页面脚本自己就是这条） */
        private const val JD_AUTH_CHECK_URL =
            "https://tool.manmanbuy.com/HistoryLowest.aspx?action=checkJdAuth"
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
