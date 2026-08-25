package com.pricelens.data.remote

import com.pricelens.util.RateLimiter
import com.pricelens.util.UserAgents
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §7 爬虫引擎共享客户端：
 *  - 10s 超时、403 → RateLimiter 熔断 5min、UA 轮换
 *  - OkHttp 磁盘缓存 5MB（§4.6 预算）
 *  - 所有请求经 RateLimiter（同域名 1 req/3s、并发域名 ≤ 3）
 */
@Singleton
class ApiClient @Inject constructor(
    private val rateLimiter: RateLimiter,
    cacheDir: File,
) {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(java.time.Duration.ofSeconds(10))
        .readTimeout(java.time.Duration.ofSeconds(10))
        .cache(okhttp3.Cache(File(cacheDir, "okhttp"), 5L * 1024 * 1024))
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun getJson(
        url: String,
        referer: String? = null,
        cookie: String? = null
    ): JSONObject? =
        withContext(Dispatchers.IO) {
            requestWithLimiter(url) { headers ->
                http.newCall(
                    Request.Builder().url(url).headers(headers).apply {
                        referer?.let { header("Referer", it) }
                        cookie?.let { header("Cookie", it) }
                    }.cacheControl(CacheControl.FORCE_NETWORK).build()
                ).execute()
            }?.let { JSONObject(it) }
        }

    suspend fun getJsonAllowCache(url: String, referer: String? = null): JSONObject? =
        withContext(Dispatchers.IO) {
            requestWithLimiter(url) { headers ->
                http.newCall(
                    Request.Builder().url(url).headers(headers).apply {
                        referer?.let { header("Referer", it) }
                    }.build()
                ).execute()
            }?.let { JSONObject(it) }
        }

    suspend fun postJson(url: String, jsonBody: String): JSONObject? =
        withContext(Dispatchers.IO) {
            requestWithLimiter(url) { headers ->
                http.newCall(
                    Request.Builder().url(url).headers(headers)
                        .post(jsonBody.toRequestBody(jsonMedia)).build()
                ).execute()
            }?.let { JSONObject(it) }
        }

    suspend fun getHtml(
        url: String,
        referer: String? = null,
        cookie: String? = null
    ): String? =
        withContext(Dispatchers.IO) {
            requestWithLimiter(url) { headers ->
                http.newCall(
                    Request.Builder().url(url).headers(headers).apply {
                        referer?.let { header("Referer", it) }
                        cookie?.let { header("Cookie", it) }
                    }.build()
                ).execute()
            }
        }

    /** 统一限速执行：403 → 熔断；10s 超时；失败重试 1 次 */
    private suspend fun requestWithLimiter(
        url: String,
        build: (okhttp3.Headers) -> okhttp3.Response
    ): String? {
        val domain = try {
            java.net.URI(url).host ?: return null
        } catch (_: Exception) {
            return null
        }
        var attempt = 0
        while (attempt <= 1) {
            val result = rateLimiter.withLimit(domain) { executeOnce(build, domain, url) }
            if (result != null) return result
            attempt++
        }
        android.util.Log.w("PriceLens", "NET 失败(重试后仍无数据): $url")
        return null
    }

    private fun executeOnce(
        build: (okhttp3.Headers) -> okhttp3.Response,
        domain: String,
        url: String
    ): String? {
        return try {
            val headers = okhttp3.Headers.Builder()
                .set("User-Agent", UserAgents.next())
                .set("Accept", "application/json, text/plain, */*")
                .build()
            build(headers).use { resp ->
                when {
                    resp.code == 403 || resp.code == 412 -> {
                        android.util.Log.w("PriceLens", "NET 403/412 反爬拦截: $domain")
                        rateLimiter.penalize(domain)
                        null
                    }
                    resp.isSuccessful -> {
                        val body = resp.body?.string()
                        android.util.Log.i("PriceLens", "NET ${resp.code} len=${body?.length ?: 0} $url")
                        // 反爬软拦截（如 202 + JS challenge 页）：内容过短视为无效，避免误判为"无数据"
                        if (body != null && body.length < 512 && isAntiBotChallenge(body)) {
                            android.util.Log.w("PriceLens", "NET 疑似反爬拦截页: $domain")
                            rateLimiter.penalize(domain)
                            null
                        } else body
                    }
                    else -> {
                        android.util.Log.w("PriceLens", "NET ${resp.code} $url")
                        null
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("PriceLens", "NET 异常 ${e.javaClass.simpleName}: $url")
            null
        }
    }

    /** 常见反爬 JS challenge 页特征（如什么值得买的 probe.js 探测页） */
    private fun isAntiBotChallenge(body: String): Boolean =
        body.contains("probe.js") || body.contains("challenge") && body.contains("script")
}
