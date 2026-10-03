package com.pricelens.coupon.normalize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 链接/淘口令的**字符串级**抽取（红线：本版绝不发 HTTP，展开留给网络层）。
 *
 * 判据形态与 `Normalize` 同一口径：认出来的原样带走，认不出就是"没有候选"，
 * 绝不返回一个"看起来像 ID 的字符"当确定性商品 ID（那是最难查的假阳性来源）。
 */
class LinksTest {

    @Test
    fun `淘口令原样截出且不展开`() {
        val raw = "【￥K8Z3c1Abc￥】复制打开京东"
        val candidates = Links.decode(raw)
        assertEquals(1, candidates.size)
        assertEquals(LinkKind.TB_TOKEN, candidates[0].kind)
        // 正例：值必须与原文里那段**一模一样**（没有补协议头、没有展开、没有解码）
        assertEquals("￥K8Z3c1Abc￥", candidates[0].value)
        assertTrue(raw.contains(candidates[0].value))
    }

    @Test
    fun `纯数字的货币串不算口令`() {
        // `￥19.9` 是价格；`￥123456￥` 长度够但没有字母 —— 两条都会被当口令的话，
        // 一行价格就变成一条"链接"，展开展示的是不存在的商品
        assertTrue(Links.decode("￥19.9").isEmpty())
        assertTrue(Links.decode("￥123456￥").isEmpty())
        // 正例对照：含字母的同形态必须认得出来（证明"要含字母"不是把整条路判死）
        assertEquals(1, Links.decode("￥123a56￥").size)
    }

    @Test
    fun `短链与明文商品链分开标类型`() {
        val share = Links.decode("复制 m.tb.cn/h.GqXbK1 打开，领50元券")
        assertEquals(1, share.size)
        assertEquals(LinkKind.SHORT_LINK, share[0].kind)
        assertEquals("m.tb.cn/h.GqXbK1", share[0].value)
        val item = Links.decode("https://item.jd.com/100012043978.html 领券")
        assertEquals(1, item.size)
        assertEquals(LinkKind.ITEM_LINK, item[0].kind)
        assertEquals("item.jd.com/100012043978.html", item[0].value)
        // 中文标点截断：句读之后的内容不该并进链接（否则"券"字被带进 URL，展开必失败）
        val truncated = Links.decode("打开 m.tb.cn/h.Ab1，领取50元券")
        assertEquals(1, truncated.size)
        assertEquals("m.tb.cn/h.Ab1", truncated[0].value)
    }

    @Test
    fun `认不出就没有候选，不返回猜测值`() {
        assertTrue(Links.decode("满199减50").isEmpty())
        assertTrue(Links.decode("https://example.com/x").isEmpty())
        // 正例对照：同一个串里既有口令又有短链时两条都出（顺序是先口令后链接）
        val both = Links.decode("￥K8Z3c1Abc￥ m.tb.cn/h.GqXbK1")
        assertEquals(listOf(LinkKind.TB_TOKEN, LinkKind.SHORT_LINK), both.map { it.kind })
    }

    @Test
    fun `平台线索只从字符串判`() {
        assertEquals("jd", Links.platformHint("item.jd.com/100012043978.html"))
        assertEquals("taobao", Links.platformHint("m.tb.cn/h.GqXbK1"))
        assertEquals("pdd", Links.platformHint("https://yangkeduo.com/tv/1234"))
        assertEquals("unknown", Links.platformHint("满199减50"))
        // 大小写混排的域名也要认（宿主名常出现在分享文案的大写形态）
        assertEquals("jd", Links.platformHint("ITEM.JD.COM/100012043978.html"))
    }
}
