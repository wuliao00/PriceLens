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
    }
}
