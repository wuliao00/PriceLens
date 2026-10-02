package com.pricelens.domain

import com.pricelens.domain.LinkParser.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 链接解析用例库（文档 §4.4 的形态清单 + 本项目实测过的形态）。
 *
 * 这是入口管道最容易回归的一环：分享、剪贴板、搜索框粘贴三条路都经过它，
 * 而各平台的链接形态只会越来越多。用例只加不改 —— 改了就说明行为变了，得先说清为什么。
 */
class LinkParserTest {

    private fun ok(input: String, platform: Platform?, sku: String?): LinkParser.ParsedLink {
        val r = LinkParser.parse(input)
        assertNotNull("应识别：$input", r)
        assertEquals("平台：$input", platform, r!!.platform)
        assertEquals("商品 ID：$input", sku, r.skuId)
        return r
    }

    @Test
    fun `parses the known jd forms`() {
        ok("https://item.jd.com/100012031548.html", Platform.JD, "100012031548")
        ok("https://item.m.jd.com/product/100012031548.html?cu=true", Platform.JD, "100012031548")
    }

    @Test
    fun `parses the known taobao and tmall forms`() {
        ok("https://item.taobao.com/item.htm?id=674512345678", Platform.TAOBAO, "674512345678")
        ok("https://detail.m.tmall.com/item.htm?id=674512345678&skuId=5", Platform.TAOBAO, "674512345678")
    }

    @Test
    fun `parses the known pdd forms`() {
        ok("https://mobile.yangkeduo.com/goods.html?goods_id=345678901234", Platform.PDD, "345678901234")
        ok("https://p.pinduoduo.com/goods2.html?goods_id=345678901234", Platform.PDD, "345678901234")
    }

    @Test
    fun `short links are marked for redirect instead of guessing a sku`() {
        val jd = ok("【京东】https://u.jd.com/AbCdEf 复制打开", Platform.JD, null)
        assertTrue("短链必须标记 needsRedirect，交给数据层跳转", jd.needsRedirect)

        val tb = ok("https://m.tb.cn/h.g5XkQlP", Platform.TAOBAO, null)
        assertTrue(tb.needsRedirect)

        val three = ok("https://3.cn/1AbCdE", Platform.JD, null)
        assertTrue("3.cn 是京东短链", three.needsRedirect)
    }

    @Test
    fun `a taobao code without a url is recognised as taobao but has no sku`() {
        val r = LinkParser.parse("3 ￥A1b2C3d4E5￥ 打开淘宝")
        assertNotNull(r)
        assertEquals(Platform.TAOBAO, r!!.platform)
        assertNull(r.skuId)
        assertFalse("口令没有 URL，跳转解析也无从谈起", r.needsRedirect)
        assertEquals("", r.url)
    }

    @Test
    fun `share noise around the link is kept as raw but never breaks the sku`() {
        val r = ok("在京东发现了宝贝 https://item.jd.com/100012031548.html 快来看看", Platform.JD, "100012031548")
        assertTrue("原始文本要留着（分享语里可能含标题）", r.raw.contains("京东"))
    }

    @Test
    fun `trailing chinese punctuation is not part of the url`() {
        val r = ok("看看这个 https://item.jd.com/100012031548.html。", Platform.JD, "100012031548")
        assertFalse("句号不该被吃进 URL", r.url.endsWith("。"))
    }

    @Test
    fun `non-commerce text and non-commerce urls are rejected`() {
        assertNull(LinkParser.parse("茅台 飞天 500ml"))
        assertNull(LinkParser.parse(""))
        assertNull(LinkParser.parse("   "))
        assertNull("普通网页不是商品入口", LinkParser.parse("https://www.baidu.com/s?wd=x"))
    }

    @Test
    fun `a jd link on a wrong-shaped path is still jd without a sku`() {
        val r = LinkParser.parse("https://item.jd.com/channel/123.html")
        assertNotNull(r)
        assertEquals(Platform.JD, r!!.platform)
        assertNull(r.skuId)
        assertFalse("已知平台的普通页面：不需要短链跳转", r.needsRedirect)
    }

    @Test
    fun `host matching does not fall for lookalike domains`() {
        // evil-jd.com 不能因为结尾是 jd.com 就被当成京东（实际 endsWith(".jd.com") 不匹配，
        // 但这条用例把意图钉住：域名必须是相等或点号分界的后缀）
        assertNull(LinkParser.parse("https://evil-jd.com/item/1.html"))
    }
}
