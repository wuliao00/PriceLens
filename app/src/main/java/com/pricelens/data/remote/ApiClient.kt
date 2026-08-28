package com.pricelens.data.remote

import com.pricelens.util.LogT
import com.pricelens.util.RateLimiter
import com.pricelens.util.UserAgents
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
 *
 * 阶段2 改造：新增返回 [CrawlerResult] 的 *Result 方法，携带失败原因
 * （反爬拦截 / 网络异常 / 空响应）；原返回 nullable 的旧方法签名不变，
 * 内部委托新方法并经 [asNullable] 转换，8 个 *Api 解析类零改动。
 * 限流 / 熔断 / 403 处理 / 反爬挑战页识别 / 重试逻辑与改造前完全一致。
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

    /** 各域名最近一次非成功结果（阶段4 SourceStatusRow 诊断用） */
    private val lastOutcomes = ConcurrentHashMap<String, CrawlerResult<String>>()

    /** 查询某域名最近一次失败结果（成功则不记录；未请求过返回 null） */
    fun lastOutcomeFor(domain: String): CrawlerResult<String>? = lastOutcomes[domain]

    // ---------- 新方法：返回 CrawlerResult（携带失败原因） ----------

    suspend fun getJsonResult(
        url: String,
        referer: String? = null,
        cookie: String? = null
    ): CrawlerResult<String> =
        withContext(Dispatchers.IO) {
            requestWithLimiterResult(url) { headers ->
                http.newCall(
                    Request.Builder().url(url).headers(headers).apply {
                        referer?.let { header("Referer", it) }
                        cookie?.let { header("Cookie", it) }
                    }.cacheControl(CacheControl.FORCE_NETWORK).build()
                ).execute()
            }
        }

    suspend fun getJsonAllowCacheResult(
        url: String,
        referer: String? = null
    ): CrawlerResult<String> =
        withContext(Dispatchers.IO) {
            requestWithLimiterResult(url) { headers ->
                http.newCall(
                    Request.Builder().url(url).headers(headers).apply {
                        referer?.let { header("Referer", it) }
                    }.build()
                ).execute()
            }
        }

    suspend fun postJsonResult(url: String, jsonBody: String): CrawlerResult<String> =
        withContext(Dispatchers.IO) {
            requestWithLimiterResult(url) { headers ->
                http.newCall(
                    Request.Builder().url(url).headers(headers)
                        .post(jsonBody.toRequestBody(jsonMedia)).build()
                ).execute()
            }
        }

    suspend fun getHtmlResult(
        url: String,
        referer: String? = null,
        cookie: String? = null
    ): CrawlerResult<String> =
        withContext(Dispatchers.IO) {
            requestWithLimiterResult(url) { headers ->
                http.newCall(
                    Request.Builder().url(url).headers(headers).apply {
                        referer?.let { header("Referer", it) }
                        cookie?.let { header("Cookie", it) }
                    }.build()
                ).execute()
            }
        }

    // ---------- 旧方法：签名不变，内部委托（*Api 零改动） ----------

    suspend fun getJson(
        url: String,
        referer: String? = null,
        cookie: String? = null
    ): JSONObject? = getJsonResult(url, referer, cookie).asNullable()?.let { JSONObject(it) }

    suspend fun getJsonAllowCache(url: String, referer: String? = null): JSONObject? =
        getJsonAllowCacheResult(url, referer).asNullable()?.let { JSONObject(it) }

    suspend fun postJson(url: String, jsonBody: String): JSONObject? =
        postJsonResult(url, jsonBody).asNullable()?.let { JSONObject(it) }

    suspend fun getHtml(
        url: String,
        referer: String? = null,
        cookie: String? = null
    ): String? = getHtmlResult(url, referer, cookie).asNullable()

    // ---------- 限速 / 熔断 / 重试核心（逻辑与改造前一致） ----------

    /** 统一限速执行：403 → 熔断；10s 超时；失败重试 1 次；结果携带原因 */
    private suspend fun requestWithLimiterResult(
        url: String,
        build: (okhttp3.Headers) -> okhttp3.Response
    ): CrawlerResult<String> {
        val domain = try {
            java.net.URI(url).host
                ?: return CrawlerResult.Network(IllegalStateException("URL 缺少 host: $url"))
        } catch (e: Exception) {
            return CrawlerResult.Network(e)
        }
        var attempt = 0
        var last: CrawlerResult<String> = CrawlerResult.Network(IllegalStateException("未执行"))
        while (attempt <= 1) {
            val result = rateLimiter.withLimit<CrawlerResult<String>>(domain) {
                executeOnceResult(build, domain, url)
            } ?: CrawlerResult.Blocked("域名熔断中(反爬暂停): $domain")
            if (result.isSuccess()) return result
            last = result
            attempt++
        }
        LogT.w("NET 失败(重试后仍无数据): $url")
        recordOutcome(domain, last)
        return last
    }

    private fun executeOnceResult(
        build: (okhttp3.Headers) -> okhttp3.Response,
        domain: String,
        url: String
    ): CrawlerResult<String> {
        return try {
            val headers = okhttp3.Headers.Builder()
                .set("User-Agent", UserAgents.next())
                .set("Accept", "application/json, text/plain, */*")
                .build()
            build(headers).use { resp ->
                when {
                    resp.code == 403 || resp.code == 412 -> {
                        LogT.w("NET 403/412 反爬拦截: $domain")
                        rateLimiter.penalize(domain)
                        CrawlerResult.Blocked("403/412 反爬拦截")
                    }
                    resp.isSuccessful -> {
                        val body = resp.body?.string()
                        LogT.i("NET ${resp.code} len=${body?.length ?: 0} $url")
                        // 反爬软拦截（如 202 + JS challenge 页）：内容过短视为无效，避免误判为"无数据"
                        if (body != null && body.length < 512 && isAntiBotChallenge(body)) {
                            LogT.w("NET 疑似反爬拦截页: $domain")
                            rateLimiter.penalize(domain)
                            CrawlerResult.Blocked("疑似反爬挑战页")
                        } else if (body == null) {
                            CrawlerResult.Empty
                        } else {
                            CrawlerResult.Success(body)
                        }
                    }
                    else -> {
                        LogT.w("NET ${resp.code} $url")
                        CrawlerResult.Network(java.io.IOException("HTTP ${resp.code}"))
                    }
                }
            }
        } catch (e: Exception) {
            LogT.w("NET 异常 ${e.javaClass.simpleName}: $url")
            CrawlerResult.Network(e)
        }
    }

    private fun recordOutcome(domain: String, result: CrawlerResult<String>) {
        if (result !is CrawlerResult.Success) lastOutcomes[domain] = result
    }

    /** 常见反爬 JS challenge 页特征（如什么值得买的 probe.js 探测页） */
    private fun isAntiBotChallenge(body: String): Boolean =
        // 括号显式化优先级：probe.js 命中，或（challenge 与 script 同时出现）；行为与加括号前一致
        body.contains("probe.js") || (body.contains("challenge") && body.contains("script"))

    // ---------- 阶段3：singleflight 在途请求去重（仅新增重载，现有方法零改动） ----------
    //
    // 同一规范化 key（URL 查询参数排序；POST 另含 body）在 10s 窗口内：
    //  - 在途请求共享同一个 Deferred（并发调用方 await 同一结果）
    //  - 已完成的仅缓存成功结果至窗口结束，避免失败粘滞；
    //  - forceRefresh = true 穿透：丢弃已有槽位直发新请求。
    // 线程安全：槽位创建/替换/移除均在 slotLock 监视器内，等待方只持不可变 Deferred。

    private sealed class Slot {
        class Running(val deferred: CompletableDeferred<CrawlerResult<String>>) : Slot()
        class Done(val result: CrawlerResult<String>, val completedAt: Long) : Slot()
    }

    private sealed class Decision {
        class Wait(val deferred: CompletableDeferred<CrawlerResult<String>>) : Decision()
        class Hit(val result: CrawlerResult<String>) : Decision()
        class Own(val deferred: CompletableDeferred<CrawlerResult<String>>) : Decision()
    }

    private val slots = ConcurrentHashMap<String, Slot>()
    private val slotLock = Any()

    /** 规范化请求 key：scheme/host 小写 + 查询参数按字典序排序（同义请求归一） */
    fun requestKey(method: String, url: String, extra: String? = null): String {
        val uri = runCatching { java.net.URI(url) }.getOrNull()
        val normalized = if (uri == null || uri.host == null) {
            url
        } else {
            buildString {
                append(uri.scheme?.lowercase() ?: "http").append("://")
                append(uri.host.lowercase())
                if (uri.port != -1) append(':').append(uri.port)
                append(uri.rawPath.orEmpty())
                val query = uri.rawQuery?.split('&')?.filter { it.isNotEmpty() }
                    ?.sorted()?.joinToString("&")
                if (!query.isNullOrEmpty()) append('?').append(query)
            }
        }
        return "$method|$normalized" + (extra?.let { "|$it" } ?: "")
    }

    private suspend fun singleflight(
        key: String,
        forceRefresh: Boolean,
        block: suspend () -> CrawlerResult<String>
    ): CrawlerResult<String> {
        val nowMs = System.currentTimeMillis()
        val decision = synchronized(slotLock) {
            if (forceRefresh) slots.remove(key)
            when (val slot = slots[key]) {
                is Slot.Running -> Decision.Wait(slot.deferred)
                is Slot.Done ->
                    if (slot.result.isSuccess() && nowMs - slot.completedAt < DEDUP_WINDOW_MS) {
                        Decision.Hit(slot.result)
                    } else {
                        slots.remove(key) // 超窗 Done 槽位惰性清理，防槽位表无界增长
                        Decision.Own(newFlight(key))
                    }
                null -> Decision.Own(newFlight(key))
            }
        }
        return when (decision) {
            is Decision.Wait -> decision.deferred.await()
            is Decision.Hit -> decision.result
            is Decision.Own -> executeFlight(key, decision.deferred, block)
        }
    }

    private fun newFlight(key: String): CompletableDeferred<CrawlerResult<String>> =
        CompletableDeferred<CrawlerResult<String>>().also { slots[key] = Slot.Running(it) }

    private suspend fun executeFlight(
        key: String,
        deferred: CompletableDeferred<CrawlerResult<String>>,
        block: suspend () -> CrawlerResult<String>
    ): CrawlerResult<String> = try {
        val result = block()
        deferred.complete(result)
        synchronized(slotLock) {
            // 仅缓存成功结果；失败时若槽位仍是本次航班则移除，后续调用可即刻重试
            if (result.isSuccess()) {
                slots[key] = Slot.Done(result, System.currentTimeMillis())
            } else if ((slots[key] as? Slot.Running)?.deferred === deferred) {
                slots.remove(key)
            }
        }
        result
    } catch (e: CancellationException) {
        // 取消透传：不包装为 Network 失败（不污染失败统计/熔断）；
        // 通知等待方取消并释放在途槽位，后续调用可即刻重发
        deferred.cancel(e)
        synchronized(slotLock) { slots.remove(key) }
        throw e
    } catch (e: Throwable) {
        val net = CrawlerResult.Network(e)
        deferred.complete(net)
        synchronized(slotLock) { slots.remove(key) }
        net
    }

    /** 去重版 JSON GET（FORCE_NETWORK）；forceRefresh 穿透在途/窗口缓存 */
    suspend fun getJsonResultDedup(
        url: String,
        referer: String? = null,
        cookie: String? = null,
        forceRefresh: Boolean = false
    ): CrawlerResult<String> =
        singleflight(requestKey("GET", url), forceRefresh) { getJsonResult(url, referer, cookie) }

    /** 去重版 JSON GET（允许 OkHttp 缓存） */
    suspend fun getJsonAllowCacheResultDedup(
        url: String,
        referer: String? = null,
        forceRefresh: Boolean = false
    ): CrawlerResult<String> =
        singleflight(requestKey("GETC", url), forceRefresh) { getJsonAllowCacheResult(url, referer) }

    /** 去重版 JSON POST（key 含请求体，不同体不合并） */
    suspend fun postJsonResultDedup(
        url: String,
        jsonBody: String,
        forceRefresh: Boolean = false
    ): CrawlerResult<String> =
        singleflight(requestKey("POST", url, jsonBody), forceRefresh) { postJsonResult(url, jsonBody) }

    /** 去重版 HTML GET */
    suspend fun getHtmlResultDedup(
        url: String,
        referer: String? = null,
        cookie: String? = null,
        forceRefresh: Boolean = false
    ): CrawlerResult<String> =
        singleflight(requestKey("HTML", url), forceRefresh) { getHtmlResult(url, referer, cookie) }

    /** 去重版 JSON GET 的 nullable 兼容桥 */
    suspend fun getJsonDedup(
        url: String,
        referer: String? = null,
        cookie: String? = null,
        forceRefresh: Boolean = false
    ): JSONObject? =
        getJsonResultDedup(url, referer, cookie, forceRefresh).asNullable()?.let { JSONObject(it) }

    /** 去重版 HTML GET 的 nullable 兼容桥 */
    suspend fun getHtmlDedup(
        url: String,
        referer: String? = null,
        cookie: String? = null,
        forceRefresh: Boolean = false
    ): String? = getHtmlResultDedup(url, referer, cookie, forceRefresh).asNullable()

    companion object {
        /** singleflight 结果共享窗口：10 秒（仅成功结果） */
        const val DEDUP_WINDOW_MS = 10_000L
    }
}
