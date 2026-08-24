package com.pricelens.util

import java.security.MessageDigest

/**
 * B 站 wbi 签名（§6.1）：api.bilibili.com 部分接口需要 wts + w_rid。
 * 算法：nav 接口取 img_key/sub_key → 打乱表重排 → 前 32 位为 mixin key
 *      → 参数排序 urlencode 后拼接 key 做 MD5。
 */
object WbiSigner {

    private val MIXIN_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
        37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
        22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    )

    /** 从 nav 接口响应 JSON 提取的 key 计算签名参数 */
    fun sign(params: Map<String, String>, imgKey: String, subKey: String): Map<String, String> {
        val mixinKey = MIXIN_TAB.joinToString("") {
            (imgKey + subKey).getOrNull(it)?.toString() ?: ""
        }.take(32)
        val wts = (System.currentTimeMillis() / 1000).toString()
        val sorted = (params + ("wts" to wts)).toSortedMap()
        val query = sorted.entries.joinToString("&") { (k, v) ->
            urlEncode(k) + "=" + urlEncode(v.filter { ch -> ch !in "!'()*" })
        }
        val wRid = md5(query + mixinKey)
        return sorted + ("w_rid" to wRid)
    }

    fun extractKeys(navJson: String): Pair<String, String>? {
        // 只取 img_url / sub_url 文件名（去路径与扩展名），不做全量 JSON 建模
        val img = Regex("\"img_url\"\\s*:\\s*\"([^\"]+)\"").find(navJson)?.groupValues?.get(1)
        val sub = Regex("\"sub_url\"\\s*:\\s*\"([^\"]+)\"").find(navJson)?.groupValues?.get(1)
        if (img == null || sub == null) return null
        val imgKey = img.substringAfterLast('/').substringBefore('.')
        val subKey = sub.substringAfterLast('/').substringBefore('.')
        return imgKey to subKey
    }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
