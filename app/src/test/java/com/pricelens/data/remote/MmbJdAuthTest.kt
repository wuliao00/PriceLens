package com.pricelens.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `checkJdAuth` 响应的判定（2026-10-02 实测三态）。
 *
 * 这三条原始响应是从真机 Cookie 实测抄回来的，不是构造的：
 *  - 未授权：`{"code":1,"msg":"未授权","data":{"auth":false,"authUrl":"…jd_oauth_redirect.html…"}}`
 *  - 未登录：`{"code":0,"msg":"请先登录","data":{"login":0}}`
 *  - 已授权：结构同上但 `data.auth == true`（测试里按同结构造，字段名逐字来自实测页面脚本
 *    `if (ret.data && ret.data.auth == true) { doSearchInner(); }`）
 *
 * 守两条红线：
 *  1. **不许猜**：缺字段/非 JSON/结构变化一律 Unknown，不能把"不知道"说成"未授权"或"已授权"；
 *  2. 未授权必须带**可点的授权入口**（payload 里的 authUrl，缺了用官方兜底）。
 */
class MmbJdAuthTest {

    private val realNotAuthorized =
        """{"code":1,"msg":"未授权","data":{"auth":false,"authUrl":""" +
            """"https://apapia-config.manmanbuy.com/h5/jd_oauth_redirect.html?invokeScene=baoliao"}}"""
    private val realLoggedOut = """{"code":0,"msg":"请先登录","data":{"login":0}}"""

    @Test
    fun `real not-authorized payload maps to NotAuthorized with its own auth url`() {
        val state = parseJdAuthState(realNotAuthorized)
        assertTrue("实测未授权响应应判为未授权，而不是登录失效", state is JdAuthState.NotAuthorized)
        assertEquals(
            "授权入口要用响应里给的那条（找不到再用兜底）",
            "https://apapia-config.manmanbuy.com/h5/jd_oauth_redirect.html?invokeScene=baoliao",
            (state as JdAuthState.NotAuthorized).authUrl
        )
    }

    @Test
    fun `real logged-out payload maps to LoggedOut`() {
        assertEquals(JdAuthState.LoggedOut, parseJdAuthState(realLoggedOut))
    }

    @Test
    fun `auth true maps to Authorized`() {
        val body = """{"code":1,"msg":"","data":{"auth":true}}"""
        assertEquals(JdAuthState.Authorized, parseJdAuthState(body))
    }

    @Test
    fun `logged out wins over a stray auth field`() {
        // 未登录时慢慢买可能带上空 auth 字段；登录态优先，否则会让人白点"去授权"
        val body = """{"code":0,"msg":"请先登录","data":{"login":0,"auth":false}}"""
        assertEquals(JdAuthState.LoggedOut, parseJdAuthState(body))
    }

    @Test
    fun `not-authorized without auth url falls back to the official entry`() {
        val body = """{"code":1,"msg":"未授权","data":{"auth":false}}"""
        val state = parseJdAuthState(body)
        assertTrue(state is JdAuthState.NotAuthorized)
        assertEquals(MMB_JD_AUTH_URL_FALLBACK, (state as JdAuthState.NotAuthorized).authUrl)
    }

    @Test
    fun `a non-http auth url is rejected in favour of the fallback`() {
        val body = """{"code":1,"msg":"未授权","data":{"auth":false,"authUrl":"javascript:void(0)"}}"""
        assertEquals(
            MMB_JD_AUTH_URL_FALLBACK,
            (parseJdAuthState(body) as JdAuthState.NotAuthorized).authUrl
        )
    }

    @Test
    fun `empty and non-json bodies are unknown and never guessed`() {
        for (body in listOf(null, "", "   ", "<html>验证码</html>", "{oops")) {
            val state = parseJdAuthState(body)
            assertTrue("body=${body?.take(12)} 必须判为 Unknown，不能猜", state is JdAuthState.Unknown)
            assertTrue("Unknown 要带一句原因，便于日志与 UI 交代", (state as JdAuthState.Unknown).detail.isNotBlank())
        }
    }

    @Test
    fun `a payload missing the auth field is unknown not authorized`() {
        // 结构变化（比如以后字段改名）时宁可说"判定不了"，也不能说"已授权"
        val body = """{"code":1,"msg":"系统繁忙","data":{}}"""
        assertTrue(parseJdAuthState(body) is JdAuthState.Unknown)
        val noData = """{"code":1,"msg":"系统繁忙"}"""
        assertTrue(parseJdAuthState(noData) is JdAuthState.Unknown)
    }

    @Test
    fun `an explicit auth true is required to say authorized`() {
        // 只有字符串 "true" 不算：类型不对就是结构不对，落到 Unknown
        val body = """{"code":1,"data":{"auth":"true"}}"""
        assertTrue(parseJdAuthState(body) is JdAuthState.Unknown)
    }
}
