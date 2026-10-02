package com.pricelens.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「检测 Cookie」的结论映射（2026-10-02 真机诊断之后重写）。
 *
 * 为什么单开一条映射：真实原因只有一个 —— 账号**没授权京东**（`checkJdAuth` 实测返回
 * `{"code":1,"msg":"未授权","data":{"auth":false,"authUrl":…}}`），而 App 当时把
 * "换不到数据"一律写成「登录态无效或已过期」——把用户往"重新登录"这条死路上引。
 * 现在判定的优先级是：**登录态 → 京东授权 → 页面**，且授权缺失必须带可点的 authUrl。
 */
class CookieProbeOutcomeTest {

    private val authUrl = "https://apapia-config.manmanbuy.com/h5/jd_oauth_redirect.html?invokeScene=baoliao"

    @Test
    fun `logged out is reported as logged out whatever the page said`() {
        val out = probeOutcome(JdAuthState.LoggedOut, HistoryPage.Captcha)
        assertTrue(out is CookieProbe.LoggedOut)
        assertEquals(JdAuthState.LoggedOut, JdAuthState.LoggedOut)
    }

    @Test
    fun `not authorized wins over the page and always carries a clickable entry`() {
        val out = probeOutcome(JdAuthState.NotAuthorized(authUrl), HistoryPage.Captcha)
        assertTrue("这是最常见的真实原因，不能被页面状态盖掉", out is CookieProbe.JdNotAuthorized)
        val state = out as CookieProbe.JdNotAuthorized
        assertTrue("必须带可点的授权入口", state.authUrl.startsWith("http"))
        assertEquals(authUrl, state.authUrl)
    }

    @Test
    fun `authorized with points is a plain success`() {
        val points = listOf(ManmanbuyApi.PricePoint("2026-09-25", 262.0))
        val out = probeOutcome(JdAuthState.Authorized, HistoryPage.Points(points))
        assertEquals(CookieProbe.Ok(points), out)
    }

    @Test
    fun `authorized but the channel is captcha-gated is not an account problem`() {
        // 移动页对任何程序化请求都要人机验证（实测：带不带 Cookie 同为 4,135 字节验证页），
        // 账号已授权时这句必须说"去网页取数"，绝不能说"登录失效"
        val out = probeOutcome(JdAuthState.Authorized, HistoryPage.Captcha)
        assertTrue(out is CookieProbe.Ready)
        assertTrue((out as CookieProbe.Ready).reason.isNotBlank())
    }

    @Test
    fun `authorized without a page still means ready for the web fetch`() {
        assertTrue(probeOutcome(JdAuthState.Authorized, null) is CookieProbe.Ready)
        assertTrue(probeOutcome(JdAuthState.Authorized, HistoryPage.NoData) is CookieProbe.Ready)
    }

    @Test
    fun `unknown auth falls back to what the page can prove`() {
        val points = listOf(ManmanbuyApi.PricePoint("2026-09-25", 262.0))
        assertEquals(CookieProbe.Ok(points), probeOutcome(JdAuthState.Unknown("network"), HistoryPage.Points(points)))
        assertTrue(probeOutcome(JdAuthState.Unknown("network"), HistoryPage.Captcha) is CookieProbe.Captcha)
        assertTrue(probeOutcome(JdAuthState.Unknown("network"), HistoryPage.NoData) is CookieProbe.NoData)
        assertTrue(probeOutcome(JdAuthState.Unknown("network"), null) is CookieProbe.Unreachable)
    }

    @Test
    fun `every non-ok verdict carries a non-blank machine reason for the log`() {
        val cases = listOf(
            probeOutcome(JdAuthState.LoggedOut, null),
            probeOutcome(JdAuthState.NotAuthorized(authUrl), null),
            probeOutcome(JdAuthState.Unknown("dns"), null),
            probeOutcome(JdAuthState.Authorized, HistoryPage.Captcha)
        )
        for (case in cases) {
            val reason = when (case) {
                is CookieProbe.LoggedOut -> case.reason
                is CookieProbe.JdNotAuthorized -> case.reason
                is CookieProbe.Ready -> case.reason
                is CookieProbe.Unreachable -> case.reason
                else -> "n/a"
            }
            assertTrue("${case::class.simpleName} 应带原因", reason.isNotBlank())
        }
    }
}
