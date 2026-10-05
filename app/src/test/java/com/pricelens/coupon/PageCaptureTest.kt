package com.pricelens.coupon

import com.pricelens.accessibility.leaf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 页面树单槽交接（#61）。
 *
 * 输入取舍判据（原样住在这个文件里的旧版）已经扩成三路并搬去
 * `ClipboardCaptureTest` —— 那里同时钉树、剪贴板与关键词三路的取舍与边界；
 * 这里只留"槽位本身"的语义：只留最新、按签名清理、清理幂等。
 */
class PageCaptureTest {

    private fun capture(at: Long, signature: String = "sig-1") = PageCapture.Capture(
        signature = signature,
        packageName = "com.jingdong.app.mall",
        itemId = "100012043982",
        capturedAtElapsedMs = at,
        root = leaf(text = "领券满4999减300")
    )

    @After
    fun tearDown() {
        PageCapture.clear()
    }

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
    fun `服务被杀时 clear 兜住且幂等`() {
        PageCapture.publish(capture(1L))
        PageCapture.clear()
        assertNull(PageCapture.latest.value)
        // clear 在空槽上也必须安全（onDestroy 可能与"从未抓到"并发）
        PageCapture.clear()
        assertNull(PageCapture.latest.value)
    }
}
