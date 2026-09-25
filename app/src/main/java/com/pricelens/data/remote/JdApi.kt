package com.pricelens.data.remote

import com.pricelens.util.LogT
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §7.2 京东：p.3.cn 公开批量查价（尽力而为）+ item.m.jd.com SSR 页解析标题/主图。
 * 与桌面版（pricelens Electron）保持同一套接口与降级策略。
 *
 * 2026-09 修复（接口内容不准确 + 通道下线）：
 *  1. 标题/主图改走 `item.m.jd.com/product/{sku}.html`（可用），解析页内
 *     `window._itemInfo` 结构化 JSON（product.skuName / product.imageurl），
 *     <title> 仅作兜底并裁掉京东模板后缀。旧实现解析 item.jd.com，
 *     该页对脚本请求返回风控页 `<title>京东验证</title>`，会被当成商品名。
 *  2. p.3.cn 查价接口目前在公网 DNS 已不返回可达地址（AliDNS/腾讯 DoH 均返回
 *     私网 IP，实测 2026-09-25），失败时不再整体返回 null：标题/主图照常可用，
 *     价格置 0 并由 UI 如实提示"需在京东 App 查看"（配合无障碍实时价）。
 */
@Singleton
class JdApi @Inject constructor(private val client: ApiClient) {

    data class JdProduct(
        val skuId: String,
        val title: String,
        val price: Double,
        val originalPrice: Double?,
        val image: String,
        val url: String
    )

    /** p.3.cn 批量查价：J_100012043978,J_... → 顶层数组 [{id,p,op,m}]；失败返回空 Map */
    suspend fun getPrices(skuIds: List<String>): Map<String, Pair<Double, Double?>> {
        if (skuIds.isEmpty()) return emptyMap()
        val q = skuIds.joinToString(",") { "J_$it" }
        // ApiClient 返回的是 JSONObject，p.3.cn 是顶层数组 → 这里单独走 HTML 通道拿原文
        val body = client.getHtml(
            "https://p.3.cn/prices/mgets?skuIds=${java.net.URLEncoder.encode(q, "UTF-8")}",
            referer = "https://item.jd.com/"
        ) ?: return emptyMap()
        val arr = try {
            org.json.JSONArray(body)
        } catch (_: Exception) {
            return emptyMap()
        }
        val result = mutableMapOf<String, Pair<Double, Double?>>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val id = item.optString("id").removePrefix("J_")
            val p = item.optDouble("p", 0.0)
            val op = item.optDouble("op", 0.0).takeIf { it > 0 }
            if (p > 0) result[id] = p to op
        }
        return result
    }

    /**
     * 商品标题/主图（尽力解析，失败给占位）+ 尽力查价。
     * 价格不可得时 price = 0.0（不再返回 null，避免标题/主图被一并丢弃）。
     */
    suspend fun getProduct(skuId: String): JdProduct? {
        if (!skuId.matches(Regex("\\d{6,}"))) return null
        val prices = runCatching { getPrices(listOf(skuId))[skuId] }.getOrNull()
        if (prices == null) LogT.w("京东查价不可用（p.3.cn 不可达或未收录）: $skuId")

        val url = "https://item.m.jd.com/product/$skuId.html"
        val html = client.getHtml(url, referer = "https://item.m.jd.com/", userAgent = MOBILE_UA)
        val parsed = html?.let { parseItemPage(it, skuId) }
        if (parsed == null && html != null) {
            // 风控页/结构变更：不能把"京东验证"之类风控标题当商品名
            LogT.w("京东 m 站商品页解析失败（疑似风控页或结构变更）: $skuId")
        }

        return JdProduct(
            skuId = skuId,
            title = parsed?.title ?: "京东商品 $skuId",
            price = prices?.first ?: 0.0,
            originalPrice = prices?.second,
            image = parsed?.image.orEmpty(),
            url = url
        )
    }

    companion object {
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; PLB110) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Version/4.0 Chrome/126.0.0.0 Mobile Safari/537.36"

        /** 京东模板后缀："【图片 价格 品牌 评论】-京东" / "-京东" */
        private val TITLE_SUFFIX = Regex("【[^】]{0,20}】-京东\\s*$|-京东\\s*$")
        private const val ITEM_INFO_ANCHOR = "window._itemInfo"
        private const val PRODUCT_KEY = "\"product\""
        private val SKU_NAME = Regex("\"skuName\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.){1,400})\"")
        private val IMAGE_URL = Regex("\"imageurl\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.){1,400})\"")
        private val IMG_URL_PREFIX = Regex("^https?://")

        data class ItemPage(val title: String, val image: String)

        /**
         * 纯解析（无网络），供单测用固定页面快照验证。
         * 优先 `window._itemInfo` → `product` 对象内的 skuName / imageurl
         * （页面别处还有截断版 skuName，必须先锚到 product 再取字段）；
         * 兜底 <title>（裁掉京东模板后缀；风控页标题"京东验证"直接判失败）。
         */
        fun parseItemPage(html: String, skuId: String): ItemPage? {
            val anchor = html.indexOf(ITEM_INFO_ANCHOR)
            if (anchor >= 0) {
                // _itemInfo 是一段很大的内嵌 JSON，整段大括号配平易被截断；
                // 这里先锚到 product 对象，再在有限窗口内提取字段（值按 JSON 转义还原）
                val region = html.substring(anchor, minOf(html.length, anchor + 200_000))
                val productIdx = region.indexOf(PRODUCT_KEY)
                val scope = if (productIdx >= 0) {
                    region.substring(productIdx, minOf(region.length, productIdx + 3_000))
                } else {
                    region
                }
                val name = SKU_NAME.find(scope)?.groupValues?.get(1)?.let(::unescapeJsonString).orEmpty()
                if (name.isNotBlank()) {
                    val rawImg = IMAGE_URL.find(scope)?.groupValues?.get(1)?.let(::unescapeJsonString).orEmpty()
                    return ItemPage(title = name.trim().take(80), image = buildImageUrl(rawImg))
                }
            }
            // 兜底：<title>（风控页/验证页标题不可作商品名）
            val rawTitle = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.get(1)
                ?.replace(Regex("\\s+"), " ")?.trim()
                .orEmpty()
            val title = TITLE_SUFFIX.replace(rawTitle, "").trim()
            if (title.isEmpty() || title.contains("京东验证")) return null
            return ItemPage(title = title.take(80), image = "")
        }

        /** 把捕获到的 JSON 字符串字面量还原为真实文本（处理 \\uXXXX、\\" 等转义） */
        private fun unescapeJsonString(raw: String): String = try {
            org.json.JSONObject("{\"v\":\"$raw\"}").optString("v")
        } catch (_: Exception) {
            raw
        }

        private fun buildImageUrl(rawImg: String): String {
            if (rawImg.isEmpty()) return ""
            val cleaned = rawImg.replace(Regex("^//"), "https://")
            return when {
                IMG_URL_PREFIX.containsMatchIn(cleaned) -> cleaned
                cleaned.startsWith("jfs/") -> "https://m.360buyimg.com/mobilecms/s750x750_$cleaned"
                else -> ""
            }
        }
    }
}
