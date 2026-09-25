package com.pricelens.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 京东商品页解析基线（夹具：2026-09-25 实况抓取）。
 * 回归点：旧实现解析 item.jd.com，脚本请求会拿到风控页（<title>京东验证</title>），
 * 该标题被当成商品名展示。现解析 item.m.jd.com 页内 `window._itemInfo` 结构化 JSON。
 */
class JdItemPageTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) { "缺少夹具 $name" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test
    fun `sku name and image come from embedded item info json`() {
        val page = JdApi.parseItemPage(fixture("jd_m_item.html"), "5089253")
        assertNotNull(page)
        assertTrue(page!!.title.startsWith("Apple/苹果 iPhone X"))
        assertEquals("https://m.360buyimg.com/mobilecms/s750x750_jfs/t10675/253/1344769770/66891/92d54ca4/59df2e7fN86c99a27.jpg", page.image)
    }

    @Test
    fun `risk control page is rejected instead of being used as product name`() {
        // 实况风控页正文即 <title>京东验证</title>
        assertNull(JdApi.parseItemPage(fixture("jd_risk.html"), "100012043978"))
    }

    @Test
    fun `plain title fallback strips jd template suffix`() {
        val html = "<html><head><title>Apple iPhone 15 手机 黑色【图片 价格 品牌 评论】-京东</title></head><body></body></html>"
        val page = JdApi.parseItemPage(html, "100000000001")
        assertEquals("Apple iPhone 15 手机 黑色", page?.title)
    }
}
