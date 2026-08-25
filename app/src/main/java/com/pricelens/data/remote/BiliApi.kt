package com.pricelens.data.remote

import com.pricelens.util.ContentRisk
import com.pricelens.util.ContentRiskRules
import com.pricelens.util.WbiSigner
import javax.inject.Inject
import javax.inject.Singleton

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
        /** 内容风险判定（诚实豆沙包思路轻量版：商单/夸大宣传标记） */
        val risk: ContentRisk = ContentRisk.NONE
    )

    /** wbi key 会随版本轮换，进程内缓存一份 */
    @Volatile private var cachedKeys: Pair<String, String>? = null

    suspend fun searchVideos(keyword: String, page: Int = 1): List<BiliVideo> {
        val keys = cachedKeys ?: fetchWbiKeys()?.also { cachedKeys = it } ?: return emptyList()
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
        val json = client.getJson(
            "https://api.bilibili.com/x/web-interface/wbi/search/type?$query",
            referer = "https://www.bilibili.com",
            cookie = cookie
        ) ?: return emptyList()
        val result = json.optJSONObject("data")?.optJSONArray("result") ?: return emptyList()

        val videos = mutableListOf<BiliVideo>()
        for (i in 0 until result.length()) {
            val item = result.optJSONObject(i) ?: continue
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
                risk = ContentRiskRules.assess("$rawTitle $tags", union)
            )
        }
        return videos
    }

    private suspend fun fetchWbiKeys(): Pair<String, String>? {
        // nav 未登录也返回 wbi_img（注意字段名是 wbi_img，不是 wbi）
        val nav = client.getJsonAllowCache("https://api.bilibili.com/x/web-interface/nav")
            ?: return null
        val wbi = nav.optJSONObject("data")?.optJSONObject("wbi_img") ?: return null
        val imgKey = wbi.optString("img_url").substringAfterLast('/').substringBefore('.')
        val subKey = wbi.optString("sub_url").substringAfterLast('/').substringBefore('.')
        if (imgKey.isEmpty() || subKey.isEmpty()) return null
        return imgKey to subKey
    }

    private fun randomBuvid(): String =
        java.security.MessageDigest.getInstance("MD5")
            .digest((Math.random().toString() + System.currentTimeMillis()).toByteArray())
            .joinToString("") { "%02x".format(it) }
}
