package com.pricelens.data.remote

import com.pricelens.util.LogT
import com.pricelens.util.QueryRelevance
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §6.4 什么值得买：search.smzdm.com SSR 页解析（与桌面版同选择器、同兜底策略）。
 * 关键词搜索时，第一条带价格且与关键词相关的爆料会被用作商品候选
 * （桌面版 searchProducts 同款流程 + 同款相关性过滤）。
 *
 * 2026-09 修复（接口内容不准确）：站点按"热度"排序时会混入配件（镜头膜/数据线）
 * 与其它品牌爆料，旧实现取第一条带价条目会得到无关商品；现解析后统一过
 * [QueryRelevance]，并在结果被全部过滤时记录日志。
 */
@Singleton
class SmzdmApi @Inject constructor(private val client: ApiClient) {

    data class SmzdmPost(
        val title: String,
        val price: Double?,
        val url: String,
        val image: String = "",
        val mall: String = "",
        val positive: Int = 0,      // 列表页 SSR 直出，见 extractZhiVotes
        val negative: Int = 0
    )

    /**
     * F4（2026-09-29）：走四态 `getHtmlResult`，Blocked/Network 冒泡（详见 [toSourceFailure]）。
     * 值得买前置瑞数 WAF，"被挡"是常态结局之一，不能再伪装成"这条关键词没内容"。
     */
    suspend fun searchPosts(keyword: String): List<SmzdmPost> {
        val url = "https://search.smzdm.com/?c=faxian&s=" +
            java.net.URLEncoder.encode(keyword, "UTF-8") + "&v=a&order=score"
        val result = client.getHtmlResult(url, referer = "https://www.smzdm.com/", userAgent = BOT_UA)
        result.toSourceFailure()?.let { throw it }
        val html = (result as? CrawlerResult.Success)?.data ?: return emptyList()
        val all = parseSearchPage(html)
        val relevant = all.filter { QueryRelevance.isRelevant(keyword, it.title) }
        if (all.isNotEmpty() && relevant.isEmpty()) {
            LogT.w("值得买 ${all.size} 条全部判定为不相关: [$keyword]")
        }
        return relevant
    }

    companion object {
        /**
         * smzdm 前置瑞数动态 WAF：浏览器 UA 拿到 202 + probe.js 挑战页；
         * Googlebot UA 被放行返回完整 SSR（2026-09 实测，与桌面端一致）。
         */
        const val BOT_UA = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"

        /** 纯解析（无网络），供单测用固定页面快照验证选择器有效性 */
        fun parseSearchPage(html: String): List<SmzdmPost> {
            val doc = org.jsoup.Jsoup.parse(html)
            val posts = mutableListOf<SmzdmPost>()
            // 列表结构随版本变动，多组选择器兜底（与桌面版一致）
            val items = doc.select(
                "#feed-main-list .feed-row-wide, #feed-main-list li, .list-man .feed-row-wide"
            )
            for (item in items) {
                val linkEl = item.selectFirst("h5 a, .feed-block-title a") ?: continue
                val rawUrl = linkEl.attr("href")
                if (rawUrl.isEmpty()) continue
                val title = linkEl.text().replace(Regex("\\s+"), " ").trim()
                if (title.isEmpty()) continue

                // 标题节点内常含价格高亮 span；有则用之，无则从标题文本提取（"5999元"）
                val priceEl = item.selectFirst(".z-highlight, .feed-block-title .z-highlight")
                val price = priceEl?.text()?.replace(Regex("[^\\d.]"), "")?.toDoubleOrNull()
                    ?: extractPrice(title)

                val img = item.selectFirst("img")
                val image = (img?.attr("data-src")?.ifEmpty { img.attr("src") } ?: "")
                    .replace(Regex("^//"), "https://")
                val mall = (item.selectFirst(".feed-block-info a.z-highlight, .feed-block-extras span")
                    ?.text()?.trim() ?: "").ifEmpty { "未知渠道" }
                val votes = extractZhiVotes(item)

                posts += SmzdmPost(
                    title = title.take(60),
                    price = price,
                    url = if (rawUrl.startsWith("//")) "https:$rawUrl" else rawUrl,
                    image = image,
                    mall = mall,
                    positive = votes.first,
                    negative = votes.second
                )
                if (posts.size >= 10) break
            }
            return posts
        }

        /** "iPhone 16 128g 5999元" → 5999（与桌面版 extractPrice 同规则） */
        private fun extractPrice(text: String): Double? {
            val m = Regex("(?:¥|￥|\\s)(\\d{2,6}(?:\\.\\d{1,2})?)(?:元|\\b)").find(text)
                ?: return null
            return m.groupValues[1].toDoubleOrNull()
        }

        /**
         * 「值 / 不值」投票数。旧注释写"需进文章页拉取，列表页先置 0"是**未验证的推断**，
         * 后果是社区页每条恒显「值 0 / 不值 0」+ 一根空进度条（真机 2026-09-28 复现）。
         * 实况：列表页 SSR 里就带着票数——
         * `span.J_zhi_like_fav[data-zhi-type="1|-1"] > span.unvoted-wrap > span` 是计数
         * （夹具 smzdm_faxian.html / smzdm_youhui.html 各 14~16 处，实测 15/0、4/1、0/1…）。
         * 同一 item 里同方向出现多次时取最大值（不同 data-article 的重复按钮不应相加）。
         */
        private fun extractZhiVotes(item: org.jsoup.nodes.Element): Pair<Int, Int> {
            var up = 0
            var down = 0
            for (el in item.select("span.J_zhi_like_fav[data-zhi-type]")) {
                val n = el.selectFirst(".unvoted-wrap span")?.text()?.filter { it.isDigit() }?.toIntOrNull() ?: 0
                when (el.attr("data-zhi-type").trim()) {
                    "1" -> up = maxOf(up, n)
                    "-1" -> down = maxOf(down, n)
                }
            }
            return up to down
        }
    }
}
