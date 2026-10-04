package com.pricelens.coupon

import com.pricelens.accessibility.leaf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #61 的两件新东西：页面树的单槽交接，和"这一次用哪一路输入"的判据。
 *
 * 判据写在纯函数里是有目的的：`SystemClock` 一旦进判据本体，这段逻辑就只能上真机测，
 * 而它最该钉的恰好是**边界**（恰好到期的那一条、时钟回拨的那一条）。
 */
class PageCaptureTest {

    private fun capture(at: Long, signature: String = "sig-1") = PageCapture.Capture(
        signature = signature,
        packageName = "com.jingdong.app.mall",
        itemId = "100012043982",
        capturedAtElapsedMs = at,
        root = leaf(text = "领券满4999减300")
    )

    private fun plan(capture: PageCapture.Capture?, keyword: String, now: Long) = LocalCouponInputPlanner.plan(capture, keyword, now).kind

    @After
    fun tearDown() {
        PageCapture.clear()
    }

    // ---------- 输入选择 ----------

    @Test
    fun `没有树时回到关键词那一路`() {
        assertEquals(LocalCouponInputPlanner.Kind.KEYWORD_ONLY, plan(null, "小米14", 0L))
        // 两路都没有必须明说 NONE —— 不许拿"读了个空字符串"冒充"读过了"
        assertEquals(LocalCouponInputPlanner.Kind.NONE, plan(null, "   ", 0L))
    }

    @Test
    fun `树新鲜时两路一起用而关键词为空时只用树`() {
        val fresh = capture(at = 1_000L)
        assertEquals(LocalCouponInputPlanner.Kind.BOTH, plan(fresh, "小米14 12+256G", 2_000L))
        assertEquals(LocalCouponInputPlanner.Kind.PAGE_ONLY, plan(fresh, "", 2_000L))
    }

    @Test
    fun `新鲜度边界与反常时钟`() {
        val at = 10_000L
        val limit = LocalCouponInputPlanner.MAX_AGE_MS
        // 恰好等于上限算新鲜（与三档阈值同一条口径：闭区间下界，别把正常命中降一档）
        assertEquals(LocalCouponInputPlanner.Kind.BOTH, plan(capture(at), "券", at + limit))
        // 超过一毫秒就过期：这一刻起树不许再被读
        assertEquals(LocalCouponInputPlanner.Kind.KEYWORD_ONLY, plan(capture(at), "券", at + limit + 1))
        // 关键词也是空的 ⇒ 过期树 + 空关键词 = 没有输入，不许假装读到了东西
        assertEquals(LocalCouponInputPlanner.Kind.NONE, plan(capture(at), "", at + limit + 1))
        // 时钟回拨（负龄）按过期处理，而不是"永远新鲜"
        assertEquals(LocalCouponInputPlanner.Kind.KEYWORD_ONLY, plan(capture(at), "券", at - 1))
    }

    @Test
    fun `年龄随判据一起交出去给日志用`() {
        val at = 5_000L
        assertEquals(3_000L, LocalCouponInputPlanner.plan(capture(at), "券", at + 3_000L).ageMs)
        // 没有树时年龄无意义：用 -1 表示"不适用"，而不是 0（0 会被读成"刚刚抓的"）
        assertEquals(-1L, LocalCouponInputPlanner.plan(null, "券", at).ageMs)
    }

    // ---------- 单槽交接 ----------

    @Test
    fun `单槽只留最新的一棵树`() {
        PageCapture.publish(capture(1L, signature = "旧页"))
        PageCapture.publish(capture(2L, signature = "新页"))
        assertEquals("新页", PageCapture.latest.value?.signature)
        assertEquals(2L, PageCapture.latest.value?.capturedAtElapsedMs)
    }

    @Test
    fun `清理只认当前签名`() {
        PageCapture.publish(capture(1L, signature = "A"))
        // 晚到的旧事件（签名对不上）不许把新页面刚交的树擦掉
        PageCapture.clearIfCurrent("B")
        assertEquals("A", PageCapture.latest.value?.signature)
        PageCapture.clearIfCurrent("A")
        assertNull(PageCapture.latest.value)
    }

    @Test
    fun `服务被杀时 clear 兜住`() {
        PageCapture.publish(capture(1L))
        PageCapture.clear()
        assertNull(PageCapture.latest.value)
        // clear 在空槽上也必须安全（onDestroy 可能与"从未抓到"并发）
        PageCapture.clear()
        assertNull(PageCapture.latest.value)
    }
}
