package com.pricelens.data.remote

import com.pricelens.util.LogT
import com.pricelens.util.QueryRelevance
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §6.3 找券 —— 购物党已失效后的替代实现（与桌面端 gwdang.js 同步）：
 * 2026-09 实测 gwdang /tuan/search 404、/search 302 跳滑块验证，不可用。
 * 现走 什么值得买「优惠券频道」搜索（c=youhui，Googlebot UA 过瑞数 WAF）。
 *
 * 2026-09 修复（接口内容不准确）：爆料正文里的券信息是自然语言，例如
 * 「XX目前活动售价19.87元，下单领取满15减8元优惠券，实付低至7.35元」。
 * 旧实现在找不到「满X减Y」时用 **原价−到手价** 反推券面额（"券后直降"），
 * 把国补/PLUS 价/活动折扣统统算成了不存在的券（如"无门槛券￥500"）。
 * 现在只认**显式券文案**：必须出现「满X减Y(元)优惠券」或「领取X元优惠券」。
 * 拿不到就跳过该条，宁缺毋滥；标题一致性另过 [QueryRelevance]。
 */
@Singleton
class GwdangApi @Inject constructor(private val client: ApiClient) {

    data class Coupon(
        val amount: Double,        // 券面额
        val threshold: Double,     // 使用条件（满 X 可用），0 = 无门槛
        val title: String,
        val url: String
    )

    /**
     * F4（2026-09-29）：同 [SmzdmApi.searchPosts]——券频道与爆料走同一个 search.smzdm.com，
     * 被 WAF 拦住时冒泡失败，不再返回 `emptyList()` 让找券页说"未发现优惠券"。
     */
    suspend fun searchCoupons(keyword: String): List<Coupon> {
        val url = "https://search.smzdm.com/?c=youhui&s=" +
            java.net.URLEncoder.encode(keyword, "UTF-8") + "&v=a&order=score"
        val result = client.getHtmlResult(url, referer = "https://www.smzdm.com/", userAgent = BOT_UA)
        result.toSourceFailure()?.let { throw it }
        val html = (result as? CrawlerResult.Success)?.data ?: return emptyList()
        val all = parseSearchPage(html)
        val relevant = all.filter { QueryRelevance.isRelevant(keyword, it.title) }
        if (all.isNotEmpty() && relevant.isEmpty()) {
            LogT.w("找券 ${all.size} 条全部判定为不相关: [$keyword]")
        }
        return relevant
    }

    companion object {
        const val BOT_UA = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"

        /** 「满15减8元优惠券」/「领取满5000减500元优惠券」 */
        private val MAN_JIAN_COUPON = Regex(
            "满\\s*(\\d+(?:\\.\\d+)?)\\s*元?\\s*减\\s*(\\d+(?:\\.\\d+)?)\\s*元?\\s*(?:优惠券|券)"
        )

        /** 「领取5元优惠券」（无门槛） */
        private val FLAT_COUPON = Regex("领取\\s*(\\d+(?:\\.\\d+)?)\\s*元\\s*(?:优惠券|券)")

        /** 纯解析（无网络），供单测用固定页面快照验证；只保留能抽出显式券的条目 */
        fun parseSearchPage(html: String): List<Coupon> {
            val doc = org.jsoup.Jsoup.parse(html)
            val coupons = mutableListOf<Coupon>()
            val items = doc.select("#feed-main-list .feed-row-wide, #feed-main-list li, .list-man .feed-row-wide")
            for (item in items) {
                val linkEl = item.selectFirst("h5 a, .feed-block-title a") ?: continue
                val title = linkEl.text().replace(Regex("\\s+"), " ").trim()
                if (title.isEmpty()) continue

                val summary = item.text().replace(Regex("\\s+"), " ")
                val coupon = extractCoupon(summary) ?: continue
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

        /**
         * 只提取显式券文案 → (amount, threshold)。
         * 「满A减B」优先（要求 A > B > 0）；否则「领取B元优惠券」视为无门槛券。
         * 找不到返回 null —— 绝不按价差反推（旧实现的编造来源）。
         */
        internal fun extractCoupon(summary: String): Pair<Double, Double>? {
            MAN_JIAN_COUPON.find(summary)?.let { m ->
                val threshold = m.groupValues[1].toDoubleOrNull()
                val amount = m.groupValues[2].toDoubleOrNull()
                if (threshold != null && amount != null && amount > 0 && threshold > amount) {
                    return amount to threshold
                }
            }
            FLAT_COUPON.find(summary)?.let { m ->
                val amount = m.groupValues[1].toDoubleOrNull()
                if (amount != null && amount > 0) return amount to 0.0
            }
            return null
        }
    }
}
