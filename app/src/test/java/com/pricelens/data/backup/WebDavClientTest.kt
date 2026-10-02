package com.pricelens.data.backup

import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §五 WebDAV 客户端回归（MockWebServer 假服务器，版本与 okhttp 4.12.0 对齐）：
 *  - MKCOL 201/405 都算成功、其余码算失败；
 *  - PUT 非 2xx 抛错（备份失败必须暴露，不许静默）；
 *  - GET 非 2xx 返回 null；
 *  - PROPFIND 带 Depth:1 并解析；
 *  - 测活四档分类（成功/认证失败/连不上/其他错误码）与 Basic Auth 头。
 */
class WebDavClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client() = WebDavClient(server.url("/dav/").toString(), "user", "pass")

    @Test
    fun `mkcol accepts 201 and 405 but rejects other codes`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201))
        assertTrue(client().mkcol("pricelens"))
        val first = server.takeRequest()
        assertEquals("MKCOL", first.method)
        assertEquals("/dav/pricelens", first.path)
        assertEquals(Credentials.basic("user", "pass"), first.getHeader("Authorization"))

        server.enqueue(MockResponse().setResponseCode(405)) // 目录已存在：不少服务器用 405 表达
        assertTrue("405（已存在）必须容忍", client().mkcol("pricelens"))

        server.enqueue(MockResponse().setResponseCode(403))
        assertFalse("权限不足绝不能当成功", client().mkcol("pricelens"))
    }

    @Test
    fun `put succeeds on 2xx and throws on non-2xx`() {
        server.enqueue(MockResponse().setResponseCode(201))
        runBlocking { client().put("pricelens/a.json", """{"app":"PriceLens"}""") }
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/dav/pricelens/a.json", request.path)
        assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))

        server.enqueue(MockResponse().setResponseCode(500))
        val e = assertThrows(IOException::class.java) {
            runBlocking { client().put("pricelens/a.json", "{}") }
        }
        assertTrue("抛错要带状态码", e.message!!.contains("500"))
    }

    @Test
    fun `get returns null on non-2xx and the body on 2xx`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(client().get("pricelens/gone.json"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("payload"))
        assertEquals("payload", client().get("pricelens/a.json"))
    }

    @Test
    fun `list sends propfind depth 1 and parses entries`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                """<?xml version="1.0" encoding="utf-8"?>
                <D:multistatus xmlns:D="DAV:">
                  <D:response>
                    <D:href>/dav/pricelens/</D:href>
                    <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat>
                  </D:response>
                  <D:response>
                    <D:href>/dav/pricelens/pricelens-20261005-070809.json</D:href>
                    <D:propstat><D:prop>
                      <D:getlastmodified>Mon, 05 Oct 2026 07:08:09 GMT</D:getlastmodified>
                      <D:getcontentlength>321</D:getcontentlength>
                    </D:prop></D:propstat>
                  </D:response>
                </D:multistatus>"""
            )
        )
        val items = client().list("pricelens")
        val request = server.takeRequest()
        assertEquals("PROPFIND", request.method)
        assertEquals("1", request.getHeader("Depth"))
        assertEquals(2, items!!.size)
        assertTrue(items[0].isCollection)
        assertEquals("pricelens-20261005-070809.json", items[1].fileName)
        assertEquals(321L, items[1].size)
    }

    @Test
    fun `list returns null when the server refuses`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        assertNull(client().list("pricelens"))
    }

    @Test
    fun `testConnection classifies ok auth failure and other codes`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(207))
        assertEquals(WebDavProbe.Ok, client().testConnection())

        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(WebDavProbe.AuthFailed, client().testConnection())

        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(WebDavProbe.HttpError(500), client().testConnection())
    }

    @Test
    fun `testConnection reports unreachable when the host is down`() {
        val dead = WebDavClient(server.url("/dav/").toString(), "user", "pass")
        server.shutdown()
        assertEquals(WebDavProbe.Unreachable, runBlocking { dead.testConnection() })
    }

    @Test
    fun `deleteHref resolves absolute paths against the base host`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(client().deleteHref("/dav/pricelens/pricelens-20261001-010101.json"))
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/dav/pricelens/pricelens-20261001-010101.json", request.path)
    }

    @Test
    fun `base url normalization adds scheme and trailing slash`() {
        assertEquals("https://dav.example.com/dav/", WebDavClient.normalizeBaseUrl("dav.example.com/dav").toString())
        assertEquals("http://127.0.0.1:9/dav/", WebDavClient.normalizeBaseUrl("http://127.0.0.1:9/dav").toString())
    }
}
