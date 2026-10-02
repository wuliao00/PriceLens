package com.pricelens.ui.settings

import com.pricelens.R
import com.pricelens.data.remote.CookieProbe
import com.pricelens.data.remote.ManmanbuyApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 「检测 Cookie」结论 → 文案资源的映射（纯函数，不碰 Compose/Robolectric）。
 *
 * 锁的是这条红线：四种结局必须各有各的一句话，尤其"没够着慢慢买"（Unreachable）
 * 不能被显示成"该商品没有历史数据"（NoData）——后者是把失败谎报成结论。
 */
class ManmanbuyProbeCopyTest {

    private fun point() = ManmanbuyApi.PricePoint("2026-09-01", 3899.0)

    @Test
    fun `each outcome maps to its own string`() {
        assertEquals(R.string.settings_mmb_probe_ok, mmbProbeStringRes(CookieProbe.Ok(List(3) { point() })))
        assertEquals(
            R.string.settings_mmb_probe_logged_out,
            mmbProbeStringRes(CookieProbe.LoggedOut("checkJdAuth 返回 code=0/login=0"))
        )
        assertEquals(
            R.string.settings_mmb_probe_need_jd_auth,
            mmbProbeStringRes(CookieProbe.JdNotAuthorized("https://apapia-config.manmanbuy.com/h5/x", "auth=false"))
        )
        assertEquals(
            R.string.settings_mmb_probe_ready,
            mmbProbeStringRes(CookieProbe.Ready("checkJdAuth 返回 auth=true"))
        )
        assertEquals(
            R.string.settings_mmb_probe_captcha,
            mmbProbeStringRes(CookieProbe.Captcha("aliVal markers"))
        )
        assertEquals(
            R.string.settings_mmb_probe_no_data,
            mmbProbeStringRes(CookieProbe.NoData("no Date.UTC series"))
        )
        assertEquals(
            R.string.settings_mmb_probe_unreachable,
            mmbProbeStringRes(CookieProbe.Unreachable("network: SocketTimeoutException"))
        )
    }

    @Test
    fun `not-authorized is never worded as logged-out`() {
        // 2026-10-02 的真机事故：登录有效、只是没授权京东，旧文案却写「登录态无效或已过期」，
        // 把用户引去重新登录。这两句话必须永远不同、且各自指向正确的下一步。
        val needAuth = CookieProbe.JdNotAuthorized("https://x", "auth=false")
        assertNotEquals(R.string.settings_mmb_probe_logged_out, mmbProbeStringRes(needAuth))
        assertNotEquals(R.string.settings_mmb_probe_captcha, mmbProbeStringRes(needAuth))
        assertNotEquals(
            R.string.settings_mmb_probe_need_jd_auth,
            mmbProbeStringRes(CookieProbe.LoggedOut("login=0"))
        )
        assertNotEquals(
            R.string.settings_mmb_probe_need_jd_auth,
            mmbProbeStringRes(CookieProbe.Ready("auth=true"))
        )
    }

    @Test
    fun `unreachable is never worded as no-data`() {
        assertNotEquals(
            R.string.settings_mmb_probe_no_data,
            mmbProbeStringRes(CookieProbe.Unreachable("network: UnknownHostException"))
        )
        assertNotEquals(
            R.string.settings_mmb_probe_ok,
            mmbProbeStringRes(CookieProbe.Captcha("blocked by captcha page"))
        )
    }

    @Test
    fun `ok fills the point count and the rest fill the reason`() {
        assertEquals(3, mmbProbeArg(CookieProbe.Ok(List(3) { point() })))
        assertEquals("aliVal markers", mmbProbeArg(CookieProbe.Captcha("aliVal markers")))
        assertEquals("no Date.UTC series", mmbProbeArg(CookieProbe.NoData("no Date.UTC series")))
        assertEquals(
            "network: SocketTimeoutException",
            mmbProbeArg(CookieProbe.Unreachable("network: SocketTimeoutException"))
        )
        assertEquals("login=0", mmbProbeArg(CookieProbe.LoggedOut("login=0")))
        assertEquals("auth=false", mmbProbeArg(CookieProbe.JdNotAuthorized("https://x", "auth=false")))
        assertEquals("auth=true", mmbProbeArg(CookieProbe.Ready("auth=true")))
    }
}
