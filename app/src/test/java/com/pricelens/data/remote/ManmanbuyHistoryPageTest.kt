package com.pricelens.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 慢慢买历史价响应体分类器（2026-09-30 实测特征）。
 *
 * 回归点：改造前 `fetchViaCookie` 走 `ApiClient.getHtml`（`CrawlerResult` → `String?` 的兼容桥），
 * 于是「网络不可达」「Cookie 失效被 302 弹到人机验证页」「该商品真没历史数据」三种结局
 * 全被压成同一个空列表，用户在设置里粘完 Cookie 只能看到"无曲线"，无法知道是哪一个。
 * 本测试锁住第一类区分：**人机验证页 ≠ 无数据页**。
 */
class ManmanbuyHistoryPageTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) { "缺少夹具 $name" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    /** 2026-09-30 匿名实测（不带 Cookie）拿到的真实人机验证页，原样存的夹具 */
    private val captchaPage = fixture("mmb_history_captcha_20260930.html")

    @Test
    fun `real captcha page is not mistaken for no-data`() {
        assertEquals(HistoryPage.Captcha, classifyHistoryPage(captchaPage))
    }

    @Test
    fun `captcha fixture carries the markers the classifier relies on`() {
        // 夹具特征（实测统计）：有验证脚本、零数据序列
        assertTrue(captchaPage.contains("aliVal"))
        assertTrue(captchaPage.contains("AliyunCaptcha"))
        assertFalse(captchaPage.contains("Date.UTC"))
        assertEquals(0, parseDateUtcPoints(captchaPage).size)
    }

    @Test
    fun `synthetic page with date utc series yields points`() {
        val html = """
            <html><body>
            <div class="title">联想ThinkBook 14 历史价格</div>
            <script>
              var trend = [[Date.UTC(2026,4,9),4299.0],[Date.UTC(2026,4,23),4099.0],[Date.UTC(2026,8,1),3899]];
            </script>
            </body></html>
        """.trimIndent()
        val page = classifyHistoryPage(html)
        assertTrue(page is HistoryPage.Points)
        val points = (page as HistoryPage.Points).points
        assertEquals(3, points.size)
        // JS 月份从 0 起 → 4 月被写成 5 月、8 月被写成 9 月
        assertEquals("2026-05-09", points.first().date)
        assertEquals(4299.0, points.first().price, 0.0)
        assertEquals("2026-09-01", points.last().date)
        assertEquals(3899.0, points.last().price, 0.0)
    }

    @Test
    fun `data page that also embeds captcha script is still points`() {
        // 顺序判据：真实数据页常驻 AliyunCaptcha 初始化脚本，不能被误判成"被拦"
        val html = """
            <html><head><script src="https://o.alicdn.com/captcha-frontend/aliyunCaptcha/AliyunCaptcha.js"></script></head>
            <body><div id="captcha-element"></div>
            <script>var trend = [[Date.UTC(2026,0,15),99.9]];</script>
            </body></html>
        """.trimIndent()
        val page = classifyHistoryPage(html)
        assertEquals(HistoryPage.Points(listOf(ManmanbuyApi.PricePoint("2026-01-15", 99.9))), page)
    }

    @Test
    fun `reachable page without series or captcha yields no-data`() {
        // 慢慢买正常返回了商品页、只是这条 URL 没历史数据（无 Date.UTC、无验证脚本）
        val html = """
            <html><head><title>历史价格查询--慢慢买比价网</title></head>
            <body><div class="nodata">暂无该商品的历史价格数据</div></body></html>
        """.trimIndent()
        assertEquals(HistoryPage.NoData, classifyHistoryPage(html))
    }
}
