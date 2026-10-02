package com.pricelens.data.remote

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桌面历史页序列的解析（形状取自站点自己的 `Scripts/echartsTrend.js`，2026-10-02）：
 * `flotChart.data = [[毫秒时间戳, 价格, 附加], …]`，`oldData[i][1] <= 0` 的条目页面自己就跳过。
 *
 * 时间戳 → 日期的断言**不写死字符串**：CI 跑在 UTC、本机跑在 +08:00，
 * 同一个毫秒数会落在不同的"本地日"。这里用独立实现（java.time）算期望值，
 * 与实现里的 SimpleDateFormat 互为对照 —— 既不受时区影响，又能抓出格式化 bug。
 */
class MmbPageSeriesTest {

    private fun localDay(ts: Long): String =
        java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneId.systemDefault())
            .toLocalDate().toString()

    private fun tsOf(y: Int, m: Int, d: Int): Long {
        val cal = java.util.Calendar.getInstance()
        cal.clear()
        cal.set(y, m - 1, d, 12, 0, 0)
        return cal.timeInMillis
    }

    @Test
    fun `parses the page's own three-column shape and keeps local days`() {
        val a = tsOf(2026, 9, 24)
        val b = tsOf(2026, 9, 25)
        val json = "[[$a,268.0,\"\"],[$b,262.0,\"\"]]"
        val points = MmbPageSeries.parseFlotData(json)
        assertEquals(2, points.size)
        assertEquals(localDay(a), points[0].date)
        assertEquals(268.0, points[0].price, 0.001)
        assertEquals(localDay(b), points[1].date)
    }

    @Test
    fun `date formatting pads month and day`() {
        val ts = tsOf(2026, 1, 5)
        assertEquals("2026-01-05", MmbPageSeries.parseFlotData("[[$ts,99.0]]").first().date)
    }

    @Test
    fun `non-positive prices and bogus timestamps are skipped like the page does`() {
        val ts = tsOf(2026, 9, 25)
        val json = "[[$ts,0],[$ts,-3],[[],100],[0,88],[$ts,249.0]]"
        val points = MmbPageSeries.parseFlotData(json)
        assertEquals("只应留下那一条合法点", 1, points.size)
        assertEquals(249.0, points.first().price, 0.001)
    }

    @Test
    fun `object rows with a value array are accepted`() {
        val ts = tsOf(2026, 9, 26)
        val points = MmbPageSeries.parseFlotData("""[{"value":[$ts,240.0,"x"]}]""")
        assertEquals(1, points.size)
        assertEquals(240.0, points.first().price, 0.001)
    }

    @Test
    fun `garbage never throws and never invents points`() {
        for (bad in listOf(null, "", "   ", "<html>", "{}", "[[]]", "[[1]]")) {
            assertTrue("bad=$bad", MmbPageSeries.parseFlotData(bad).isEmpty())
        }
    }

    @Test
    fun `collapse keeps one point per day with the later observation`() {
        val points = listOf(
            ManmanbuyApi.PricePoint("2026-09-25", 260.0),
            ManmanbuyApi.PricePoint("2026-09-24", 268.0),
            ManmanbuyApi.PricePoint("2026-09-25", 255.0)
        )
        val collapsed = MmbPageSeries.collapseByDay(points)
        assertEquals(listOf("2026-09-24", "2026-09-25"), collapsed.map { it.date })
        assertEquals("同一天保留靠后的观测", 255.0, collapsed.last().price, 0.001)
    }

    @Test
    fun `page probe carries both the series and the short visible text`() {
        val ts = tsOf(2026, 9, 25)
        val probe = MmbPageSeries.parsePageProbe("""{"s":[[$ts,262.0]],"t":"正在抓取，请稍后"}""")
        assertEquals(1, probe.series.size)
        assertTrue(MmbPageSeries.pageLooksCrawling(probe.visibleText))
        assertFalse(MmbPageSeries.pageWantsJdAuth(probe.visibleText))
    }

    @Test
    fun `page probe survives a broken payload`() {
        for (bad in listOf(null, "", "{", "{}")) {
            val probe = MmbPageSeries.parsePageProbe(bad)
            assertTrue(probe.series.isEmpty())
            assertFalse(MmbPageSeries.pageLooksCrawling(probe.visibleText))
            assertFalse(MmbPageSeries.pageWantsJdAuth(probe.visibleText))
        }
    }

    @Test
    fun `the auth gate and the crawling notice are recognised from the site's own wording`() {
        assertTrue(MmbPageSeries.pageWantsJdAuth("京东授权 请先完成授权"))
        assertTrue(MmbPageSeries.pageLooksCrawling("正在抓取该商品的历史价格"))
        assertFalse(MmbPageSeries.pageWantsJdAuth(""))
    }

    @Test
    fun `js probe reads the page's own container and never writes`() {
        val js = MmbPageSeries.READ_PROBE_JS
        assertTrue("读的是页面自己的数据容器", js.contains("flotChart"))
        assertTrue("返回体含可见文本，供识别授权/抓取态", js.contains("innerText"))
        assertFalse("探针只读不改：不该出现赋值/注入语句", js.contains("document.cookie"))
    }

    @Test
    fun `format uses the same timezone as the page script`() {
        // 页面脚本用 `new Date(parseInt(nS))`（浏览器本地时区）；我们用系统默认时区对齐
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getDefault()
        val ts = tsOf(2026, 9, 25)
        assertEquals(fmt.format(Date(ts)), MmbPageSeries.parseFlotData("[[$ts,1.0]]").first().date)
    }
}
