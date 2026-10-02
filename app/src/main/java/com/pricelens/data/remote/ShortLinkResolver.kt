package com.pricelens.data.remote

import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 短链跳转解析（文档 §4.1 的数据层部分）：`u.jd.com` / `m.tb.cn` / `p.pinduoduo.com` 这类
 * 短链要先跟一两跳才知道真实商品页，才能拿到 SKU。
 *
 * 两条纪律：
 *  1. **手动跟跳、不自动重定向**（`followRedirects(false)`）：只认 `Location` 头，
 *     最多 [MAX_HOPS] 跳，避免被一路带进营销落地页/下载页；
 *  2. 每跳都要求还落在短链域名上才继续 —— 一旦跳到非短链域名就停下，
 *     把结果交给 [com.pricelens.domain.LinkParser] 判定是不是商品链接。
 */
class ShortLinkResolver {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    /**
     * @return 跳转后的 URL；任意一跳失败/没有 Location/跳出短链域名 → 返回当前已知的 URL
     *   （**不抛异常**：入口管道不该因为一条短链失效就崩，调用方拿原样 URL 继续）
     */
    suspend fun resolve(url: String, maxHops: Int = MAX_HOPS): String = withContext(Dispatchers.IO) {
        var current = url
        var hops = 0
        while (hops < maxHops && isShortHost(current)) {
            val location = runCatching { headLocation(current) }.getOrNull() ?: return@withContext current
            val next = runCatching { URI(current).resolve(location).toString() }.getOrNull() ?: return@withContext current
            if (next == current) return@withContext current
            current = next
            hops++
        }
        current
    }

    private fun headLocation(url: String): String? =
        client.newCall(Request.Builder().url(url).head().build()).execute().use { resp ->
            resp.header("Location")
        }

    companion object {
        const val MAX_HOPS = 3

        /** 短链域名（与 [com.pricelens.domain.LinkParser] 的判定保持一致） */
        private val SHORT_HOSTS = listOf("3.cn", "u.jd.com", "m.tb.cn", "p.pinduoduo.com", "tb.cn")

        fun isShortHost(rawUrl: String): Boolean {
            val host = runCatching { URI(rawUrl).host.orEmpty().lowercase() }.getOrDefault("")
            if (host.isEmpty()) return false
            return SHORT_HOSTS.any { host == it || host.endsWith(".$it") }
        }
    }
}
