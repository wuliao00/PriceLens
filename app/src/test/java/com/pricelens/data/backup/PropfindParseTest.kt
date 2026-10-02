package com.pricelens.data.backup

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §五 PROPFIND 解析夹具：命名空间前缀变体（D: / d: / lp1: / 无前缀默认命名空间）
 * 与属性缺失都要容忍 —— 真实 NAS 上的 WebDAV 网关前缀写法五花八门。
 */
class PropfindParseTest {

    @Test
    fun `parses mixed namespace prefix variants`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:" xmlns:lp1="DAV:">
              <D:response>
                <D:href>/dav/pricelens/</D:href>
                <D:propstat>
                  <D:prop>
                    <D:resourcetype><D:collection/></D:resourcetype>
                    <lp1:getlastmodified>Wed, 21 Oct 2015 07:28:00 GMT</lp1:getlastmodified>
                    <lp1:getcontentlength>0</lp1:getcontentlength>
                  </D:prop>
                </D:propstat>
              </D:response>
              <d:response xmlns:d="DAV:">
                <d:href>/dav/pricelens/pricelens-20261001-120000.json</d:href>
                <d:propstat>
                  <d:prop>
                    <d:getlastmodified>Thu, 22 Oct 2015 08:00:00 GMT</d:getlastmodified>
                    <d:getcontentlength>1234</d:getcontentlength>
                  </d:prop>
                </d:propstat>
              </d:response>
              <response xmlns="DAV:">
                <href>/dav/pricelens/pricelens-20261002-120000.json</href>
                <propstat><prop><getlastmodified>Fri, 23 Oct 2015 09:00:00 GMT</getlastmodified></prop></propstat>
              </response>
            </D:multistatus>
        """.trimIndent()
        val items = parsePropfind(xml)
        assertEquals(3, items.size)
        assertEquals("/dav/pricelens/", items[0].href)
        assertTrue("集合条目以 / 结尾", items[0].isCollection)
        assertEquals(Instant.parse("2015-10-21T07:28:00Z").toEpochMilli(), items[0].modified)
        assertEquals("pricelens-20261001-120000.json", items[1].fileName)
        assertEquals(Instant.parse("2015-10-22T08:00:00Z").toEpochMilli(), items[1].modified)
        assertEquals(1234L, items[1].size)
        assertEquals(Instant.parse("2015-10-23T09:00:00Z").toEpochMilli(), items[2].modified)
        assertEquals(0L, items[2].size) // 缺 getcontentlength → 0，不判死
    }

    @Test
    fun `tolerates empty elements and responses without href`() {
        val xml = """
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/dav/x.json</D:href>
                <D:propstat><D:prop><D:getlastmodified/></D:prop></D:propstat>
              </D:response>
              <D:response><D:propstat><D:prop/></D:propstat></D:response>
            </D:multistatus>
        """.trimIndent()
        val items = parsePropfind(xml)
        assertEquals("没有 href 的 response 跳过", 1, items.size)
        assertEquals("x.json", items[0].fileName)
        assertEquals(0L, items[0].modified)
        assertEquals(0L, items[0].size)
    }

    @Test
    fun `parses iso8601 date variant and empty multistatus`() {
        val xml = """
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/dav/y.json</d:href>
                <d:getlastmodified>2015-10-21T07:28:00Z</d:getlastmodified>
              </d:response>
            </d:multistatus>
        """.trimIndent()
        assertEquals(Instant.parse("2015-10-21T07:28:00Z").toEpochMilli(), parsePropfind(xml)[0].modified)
        assertTrue(parsePropfind("<D:multistatus xmlns:D=\"DAV:\"/>").isEmpty())
        assertTrue(parsePropfind("").isEmpty())
    }

    @Test
    fun `unescapes xml entities in href`() {
        val xml = """
            <D:multistatus xmlns:D="DAV:">
              <D:response><D:href>/dav/a%20b.json?x=1&amp;y=2</D:href></D:response>
            </D:multistatus>
        """.trimIndent()
        assertEquals("/dav/a%20b.json?x=1&y=2", parsePropfind(xml)[0].href)
    }
}
