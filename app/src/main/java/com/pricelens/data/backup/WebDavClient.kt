package com.pricelens.data.backup

import java.io.IOException
import java.io.StringReader
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/** PROPFIND 一条结果：只带备份需要的三样（href / 修改时间 / 字节数） */
data class DavItem(val href: String, val modified: Long, val size: Long) {

    /** 链接末段文件名（`/dav/pricelens/x.json` → `x.json`；集合链接以 / 结尾 → 空串） */
    val fileName: String get() = href.substringAfterLast('/')

    /** 目录（集合）条目：保留策略与"选一份恢复"都不要它 */
    val isCollection: Boolean get() = href.endsWith("/")
}

/** 测试连接的分档结论（如实四态：不把"连不上"写成"没有"） */
sealed interface WebDavProbe {
    data object Ok : WebDavProbe
    data object AuthFailed : WebDavProbe
    data object Unreachable : WebDavProbe
    data class HttpError(val code: Int) : WebDavProbe
}

/**
 * PROPFIND 响应解析（纯函数，便于单测；独立于网络层）。
 *
 * 只认 href / getlastmodified / getcontentlength 三个标签的**本地名**（冒号后半段），
 * 所以 `D:` / `d:` / `lp1:` / 无前缀默认命名空间等一切前缀写法都成立。
 * 单个 response 缺 href 或字段缺失都不判死：缺 href 跳过该条，其余字段读不出给默认值。
 */
fun parsePropfind(xml: String): List<DavItem> {
    val parser = XmlPullParserFactory.newInstance().newPullParser()
    parser.setInput(StringReader(xml))
    val items = mutableListOf<DavItem>()
    var inResponse = false
    var href: String? = null
    var modified = 0L
    var size = 0L
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> when (localName(parser.name)) {
                "response" -> {
                    inResponse = true
                    href = null
                    modified = 0L
                    size = 0L
                }
                "href" -> if (inResponse && href == null) href = readElementText(parser)
                "getlastmodified" -> if (inResponse) modified = parseHttpDate(readElementText(parser))
                "getcontentlength" -> if (inResponse) size = readElementText(parser).toLongOrNull() ?: 0L
            }
            XmlPullParser.END_TAG -> if (localName(parser.name) == "response" && inResponse) {
                val resolved = href
                if (resolved != null) items += DavItem(resolved, modified, size)
                inResponse = false
            }
        }
        event = parser.next()
    }
    return items
}

/** 带前缀的 qname → 本地名（`D:href` → `href`；`href` → `href`） */
private fun localName(qname: String?): String = qname.orEmpty().substringAfterLast(':')

/** 在 START_TAG 上读该元素文本（空元素/无文本 → 空串；XML 转义由解析器还原） */
private fun readElementText(parser: XmlPullParser): String {
    val next = parser.next()
    return if (next == XmlPullParser.TEXT) parser.text?.trim().orEmpty() else ""
}

/** getlastmodified 是 HTTP-date（RFC 1123）；个别服务器给 ISO8601，一并容忍；都读不出 → 0 */
private fun parseHttpDate(text: String): Long {
    runCatching { ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME) }
        .getOrNull()?.let { return it.toInstant().toEpochMilli() }
    runCatching { OffsetDateTime.parse(text) }.getOrNull()?.let { return it.toInstant().toEpochMilli() }
    return 0L
}

/**
 * 极简 WebDAV 客户端（okhttp 已有依赖，不引新库）：
 *  - Basic Auth（账号/密码都空则匿名请求）；
 *  - 超时 15s（connect/read/write）；
 *  - 只实现备份需要的：MKCOL / PUT / GET / DELETE / PROPFIND(list) + 测活。
 *
 * 失败语义刻意分开：`put` 非 2xx **抛 IOException**（备份必须知道没传上去），
 * `get` 非 2xx 返回 null（"远端没有"是合法结果），`delete` 返回 false（清理是尽力而为）。
 */
class WebDavClient(
    baseUrl: String,
    private val username: String,
    private val password: String,
    private val client: OkHttpClient = defaultHttpClient()
) {
    private val base: HttpUrl = normalizeBaseUrl(baseUrl)

    /** MKCOL：201（已创建）与 405（已存在）都算成功；其余码返回 false */
    suspend fun mkcol(path: String): Boolean = withContext(Dispatchers.IO) {
        client.newCall(builder().url(urlFor(path)).method("MKCOL", null).build()).execute().use {
            it.code == 201 || it.code == 405
        }
    }

    /** PUT：非 2xx 抛 IOException（消息里带方法、路径与状态码，不吞成"未知错误"） */
    suspend fun put(path: String, content: String) {
        withContext(Dispatchers.IO) {
            val body = content.toRequestBody(JSON_MEDIA)
            client.newCall(builder().url(urlFor(path)).put(body).build()).execute().use {
                if (!it.isSuccessful) throw IOException("PUT $path 失败：HTTP ${it.code}")
            }
        }
    }

    /** GET：非 2xx 返回 null（下载失败由调用方如实转述） */
    suspend fun get(path: String): String? = withContext(Dispatchers.IO) {
        client.newCall(builder().url(urlFor(path)).get().build()).execute().use {
            if (it.isSuccessful) it.body?.string() else null
        }
    }

    /** DELETE（相对路径版）；返回是否成功，网络异常吞掉返回 false */
    suspend fun delete(path: String): Boolean = deleteUrl(urlFor(path))

    /** 按 PROPFIND 返回的 href 删除（href 可能是绝对路径或完整 URL，统一交给 base 解析） */
    suspend fun deleteHref(href: String): Boolean {
        val target = base.resolve(href) ?: return false
        return deleteUrl(target)
    }

    /** PROPFIND Depth:1；非 2xx / 网络异常 / 解析失败一律 null，成功返回目录条目（含集合自身） */
    suspend fun list(path: String): List<DavItem>? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(propfindRequest(urlFor(path), "1")).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string()?.let { parsePropfind(it) } else null
            }
        }.getOrNull()
    }

    /** 测活：对 base 打 PROPFIND Depth:0，按结果分档（207/200 = 成功；401/403 = 认证失败） */
    suspend fun testConnection(): WebDavProbe = withContext(Dispatchers.IO) {
        try {
            client.newCall(propfindRequest(base, "0")).execute().use { resp ->
                when {
                    resp.code == 401 || resp.code == 403 -> WebDavProbe.AuthFailed
                    resp.code == 207 || resp.code == 200 -> WebDavProbe.Ok
                    else -> WebDavProbe.HttpError(resp.code)
                }
            }
        } catch (e: IOException) {
            WebDavProbe.Unreachable
        }
    }

    private fun propfindRequest(url: HttpUrl, depth: String): Request = builder().url(url)
        .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA))
        .header("Depth", depth)
        .build()

    private fun builder(): Request.Builder = Request.Builder().apply {
        if (username.isNotBlank() || password.isNotBlank()) {
            header("Authorization", Credentials.basic(username, password))
        }
    }

    private fun urlFor(path: String): HttpUrl = base.newBuilder().addPathSegments(path.trim('/')).build()

    private suspend fun deleteUrl(url: HttpUrl): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(builder().url(url).delete().build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    companion object {
        const val TIMEOUT_SECONDS = 15L
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val XML_MEDIA = "application/xml; charset=utf-8".toMediaType()
        private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>""" +
            """<propfind xmlns="DAV:"><prop><getlastmodified/><getcontentlength/></prop></propfind>"""

        /** 默认客户端：15s 三超时（备份是低频操作，耐心可以给足一点） */
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        /** 用户输入容错：没写协议补 `https://`，结尾没 `/` 补上（相对路径都相对它解析） */
        fun normalizeBaseUrl(raw: String): HttpUrl {
            val trimmed = raw.trim()
            val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
            val withSlash = if (withScheme.endsWith("/")) withScheme else "$withScheme/"
            return withSlash.toHttpUrlOrNull() ?: throw IllegalArgumentException("WebDAV 地址无效：$raw")
        }
    }
}
