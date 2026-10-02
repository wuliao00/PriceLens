package com.pricelens.data.remote

import com.pricelens.util.ContentRisk
import com.pricelens.util.ContentRiskRules
import com.pricelens.util.LogT
import com.pricelens.util.WbiSigner
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/**
 * §6.1 B站评测聚合：api.bilibili.com wbi 签名搜索（无需 headless）。
 */
@Singleton
class BiliApi @Inject constructor(private val client: ApiClient) {

    data class BiliVideo(
        val title: String,        // 原始标题（含 b 站 em 标签已剥离）
        val cleanTitle: String,   // 关键词高亮用纯文本
        val author: String,
        val play: Long,
        val pic: String,
        val bvid: String,
        /**
         * 发布时间（epoch 秒）。§十一 详情页的「近半年」筛选要用它。
         * 接口没给（或走 L2 缓存的旧条目没有该字段）时为 0 —— 0 的含义是"不知道"，
         * 不是"1970 年"：筛选逻辑必须保留 0，否则开关一按列表就空。
         */
        val pubdate: Long = 0,
        /** 内容风险判定（诚实豆沙包思路轻量版：商单/夸大宣传标记） */
        val risk: ContentRisk = ContentRisk.NONE
    )

    /** wbi key 会随版本轮换，进程内缓存一份 */
    @Volatile private var cachedKeys: Pair<String, String>? = null

    /**
     * F4（2026-09-29）：两条塌缩点一起收口——
     *  1. `getJson`（可空桥）→ `getJsonResult`（四态），Blocked/Network 冒泡；
     *  2. **业务码 `code` 终于被读**（以前完全不看，`code=-412` 风控与"这关键词真没视频"
     *     在下游同为 `emptyList()`，于是 B站徽标写「正常」、页面写「请先搜索商品」）。
     *     对齐桌面端 `desktop/src/main/crawlers/bilibili.js:114-116`。
     * 现在空列表只剩一种含义：B站答了我们，且 result 里确实没有视频。
     */
    suspend fun searchVideos(keyword: String, page: Int = 1): List<BiliVideo> {
        var keys = cachedKeys
        if (keys == null) {
            val nav = fetchWbiKeys()
            nav.toSourceFailure()?.let { throw it }
            keys = (nav as? CrawlerResult.Success)?.data
                ?: throw SourceUnreachableException("B站 wbi 签名密钥不可得（nav 未返回 wbi_img）")
            cachedKeys = keys
        }
        val signed = WbiSigner.sign(
            mapOf(
                "search_type" to "video",
                "keyword" to keyword,
                "page" to page.toString(),
                "platform" to "pc"
            ),
            keys.first, keys.second
        )
        val query = signed.entries.joinToString("&") { (k, v) ->
            k + "=" + java.net.URLEncoder.encode(v, "UTF-8")
        }
        // 未登录态注入随机 buvid3 设备指纹 + Referer，降低 -412 风控概率（与桌面版一致）
        val cookie = "buvid3=${randomBuvid()}; b_nut=${System.currentTimeMillis() / 1000}"
        val net = client.getJsonResult(
            "https://api.bilibili.com/x/web-interface/wbi/search/type?$query",
            referer = "https://www.bilibili.com",
            cookie = cookie
        )
        net.toSourceFailure()?.let { throw it }
        val body = (net as? CrawlerResult.Success)?.data ?: return emptyList()
        return parseSearchResponse(body)
    }

    companion object {
        /**
         * 纯解析（无网络），供夹具单测覆盖——与 `DangdangApi`/`SmzdmApi`/`GwdangApi`/`ShihuoApi`
         * 的 `parseSearchPage` 同一套约定（`data/remote` 靠夹具测，不靠 ktlint/风格门禁用例）。
         *
         * F16 顺带收口（与 F4 同一处塌缩）：**业务码 `code` 必须读**。
         * 修复前这里完全不看 `code`，于是 `code=-412`（风控）/签名失效 与"这关键词真没视频"
         * 在下游同为 `emptyList()`，B站徽标因此能写成「正常」。桌面端一直是看的
         * （`desktop/src/main/crawlers/bilibili.js:114-116`），本函数把两端拉平。
         *
         *  - `code != 0` → 抛 [CrawlerBlockedException]（不是空结果）
         *  - `code == 0` 且无 `data.result` → 空表（够着了、确实 0 条）
         *  - 非 JSON 响应体 → `JSONObject` 抛错，由上层同样记为失败
         */
        fun parseSearchResponse(body: String): List<BiliVideo> {
            val json = JSONObject(body)
            val code = json.optInt("code")
            if (code != 0) {
                LogT.w("B站搜索接口业务码异常 code=$code message=${json.optString("message")}")
                throw CrawlerBlockedException("B站接口业务码 code=$code ${json.optString("message")}".trim())
            }
            val rows = json.optJSONObject("data")?.optJSONArray("result") ?: return emptyList()

            val videos = mutableListOf<BiliVideo>()
            for (i in 0 until rows.length()) {
                val item = rows.optJSONObject(i) ?: continue
                val rawTitle = item.optString("title")
                    .replace(Regex("<[^>]+>"), "")
                    .replace("&quot;", "\"").replace("&amp;", "&")
                val tags = item.optString("tag")
                val union = item.optInt("is_union_video") == 1
                videos += BiliVideo(
                    title = rawTitle,
                    cleanTitle = rawTitle,
                    author = item.optString("author"),
                    play = item.optLong("play"),
                    pic = if (item.optString("pic").startsWith("http"))
                        item.optString("pic") else "https:${item.optString("pic")}",
                    bvid = item.optString("bvid"),
                    // §十一 详情页「近半年」筛选：接口没给 pubdate 时落 0（"不知道"，不是 1970）
                    pubdate = item.optLong("pubdate"),
                    risk = ContentRiskRules.assess("$rawTitle $tags", union)
                )
            }
            return videos
        }
    }

    /**
     * wbi 密钥取数：返回四态而不是 `Pair?`。
     * nav 拿不到（域名被拒/断网）冒泡失败；拿到了但没有 `wbi_img` 属"够着了但内容无效"→ [CrawlerResult.Empty]。
     */
    private suspend fun fetchWbiKeys(): CrawlerResult<Pair<String, String>> {
        // nav 未登录也返回 wbi_img（注意字段名是 wbi_img，不是 wbi）
        val net = client.getJsonAllowCacheResult("https://api.bilibili.com/x/web-interface/nav")
        net.toSourceFailure()?.let { throw it }
        val body = (net as? CrawlerResult.Success)?.data ?: return CrawlerResult.Empty
        val wbi = runCatching { JSONObject(body) }.getOrNull()
            ?.optJSONObject("data")?.optJSONObject("wbi_img") ?: return CrawlerResult.Empty
        val imgKey = wbi.optString("img_url").substringAfterLast('/').substringBefore('.')
        val subKey = wbi.optString("sub_url").substringAfterLast('/').substringBefore('.')
        if (imgKey.isEmpty() || subKey.isEmpty()) return CrawlerResult.Empty
        return CrawlerResult.Success(imgKey to subKey)
    }

    private fun randomBuvid(): String =
        java.security.MessageDigest.getInstance("MD5")
            .digest((Math.random().toString() + System.currentTimeMillis()).toByteArray())
            .joinToString("") { "%02x".format(it) }
}
