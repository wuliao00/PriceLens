package com.pricelens.data.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 明文 HTTP 放行范围：只许本机/局域网，公网一律拒绝（桌面手机联动真机取证的回归钉） */
class PrivateHostsTest {

    @Test
    fun `loopback and localhost are allowed`() {
        assertTrue(PrivateHosts.isPrivate("localhost"))
        assertTrue(PrivateHosts.isPrivate("127.0.0.1"))
        assertTrue(PrivateHosts.isPrivate("127.8.9.10"))
        assertTrue(PrivateHosts.isPrivate("::1"))
    }

    @Test
    fun `rfc1918 and link local are allowed`() {
        assertTrue(PrivateHosts.isPrivate("10.0.0.1"))
        assertTrue(PrivateHosts.isPrivate("192.168.5.7"))
        assertTrue(PrivateHosts.isPrivate("172.16.0.1"))
        assertTrue(PrivateHosts.isPrivate("172.31.255.254"))
        assertTrue(PrivateHosts.isPrivate("169.254.1.2"))
        assertTrue(PrivateHosts.isPrivate("fe80::1"))
        assertTrue(PrivateHosts.isPrivate("fd12:3456::1"))
        assertTrue(PrivateHosts.isPrivate("nas.local"))
    }

    @Test
    fun `public hosts are rejected`() {
        assertFalse(PrivateHosts.isPrivate("example.com"))
        assertFalse(PrivateHosts.isPrivate("8.8.8.8"))
        assertFalse(PrivateHosts.isPrivate("172.32.0.1"))
        assertFalse(PrivateHosts.isPrivate("192.169.1.1"))
        assertFalse(PrivateHosts.isPrivate("1.1.1.1"))
        assertFalse(PrivateHosts.isPrivate("2001:db8::1"))
        assertFalse(PrivateHosts.isPrivate(""))
    }

    @Test
    fun `client refuses public cleartext at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            WebDavClient("http://example.com/dav/", "u", "p")
        }
        // 私网明文与 https 公网都应构造成功（不发请求）
        WebDavClient("http://192.168.1.10:47821/", "u", "p")
        WebDavClient("https://dav.example.com/", "u", "p")
    }
}
