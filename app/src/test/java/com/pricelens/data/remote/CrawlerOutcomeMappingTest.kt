package com.pricelens.data.remote

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * F4（2026-09-29）用例 (a)/(c′) 的映射面：四态 `CrawlerResult` → "冒泡 or 不冒泡"。
 *
 * 这是整条链路的唯一映射点（审计 G5 要求"四种结局各写一条用例"）。
 * 判据只有一条：**够得着的结局（Success/Empty）才允许产生空列表**，
 * 够不着的结局（Blocked/Network）必须是失败——否则下游一切"没结果"的说法都是编的。
 */
class CrawlerOutcomeMappingTest {

    @Test
    fun `blocked bubbles up as anti-bot failure`() {
        val failure = CrawlerResult.Blocked("403/412 反爬拦截").toSourceFailure()
        assertTrue("实际=${failure?.javaClass?.name}", failure is CrawlerBlockedException)
        assertEquals("403/412 反爬拦截", failure!!.message)
    }

    @Test
    fun `network bubbles up as unreachable failure`() {
        val failure = CrawlerResult.Network(IOException("failed to connect to www.baidu.com")).toSourceFailure()
        assertTrue("实际=${failure?.javaClass?.name}", failure is SourceUnreachableException)
        assertTrue("原因里要带上底层异常，便于诊断：${failure?.message}", failure!!.message!!.contains("IOException"))
    }

    @Test
    fun `empty body is a legitimate result and must not be reported as failure`() {
        // 服务端 200 但响应体为空 = 够着了；把它算成失败会让 SourceHealth 因合法空结果而降级好源
        assertNull(CrawlerResult.Empty.toSourceFailure())
    }

    @Test
    fun `success is not a failure`() {
        assertNull(CrawlerResult.Success("<html>ok</html>").toSourceFailure())
    }

    /**
     * (c′) B站业务码：`code=-412` 是风控拒绝，不是"没有相关视频"。
     * 修复前 `BiliApi` 完全不看 `code`，这条结局与零结果同为 emptyList()（F16 与 F4 同一处塌缩）。
     */
    @Test
    fun `bili risk-control code is a failure not an empty result`() {
        val body = """{"code":-412,"message":"request was banned","ttl":1}"""
        val outcome = runCatching { BiliApi.parseSearchResponse(body) }
        val failure = outcome.exceptionOrNull()
        if (failure == null) {
            fail("B站 code=-412 必须冒泡为失败，实际静默返回 ${outcome.getOrNull()}")
        }
        // fail() 返回 void，判空后取非空局部量，后续成员访问才合法（也让断言消息带上真实类型）
        val bubbled = failure!!
        assertTrue("实际=${bubbled.javaClass.name}", bubbled is CrawlerBlockedException)
        assertTrue("原因要带业务码：${bubbled.message}", bubbled.message!!.contains("-412"))
    }

    @Test
    fun `bili zero result stays legitimate empty`() {
        val body = """{"code":0,"message":"0","ttl":1,"data":{"page":1,"result":[]}}"""
        assertEquals(emptyList<BiliApi.BiliVideo>(), BiliApi.parseSearchResponse(body))
    }

    @Test
    fun `bili success with rows parses`() {
        val body = """
            {"code":0,"message":"0","data":{"result":[
              {"title":"<em class=\"keyword\">Mate</em> 80 上手","author":"某某","play":1234,
               "pic":"//i0.hdslb.com/bfs/x.jpg","bvid":"BV1xx411c7mD","tag":"评测","is_union_video":0,
               "pubdate":1765800000}
            ]}}
        """.trimIndent()
        val videos = BiliApi.parseSearchResponse(body)
        assertEquals(1, videos.size)
        assertEquals("Mate 80 上手", videos[0].title)
        assertTrue("协议相对地址要补全 https", videos[0].pic.startsWith("https:"))
        // §十一 详情页「近半年」筛选的唯一数据来源：接口给了 pubdate 就必须读进来
        assertEquals("2025-12-15T12:00:00Z", 1_765_800_000L, videos[0].pubdate)
    }

    /** pubdate 缺失 = 接口没告诉发布时间，必须落成 0（"不知道"），绝不能算成 1970 年后又被筛选删掉 */
    @Test
    fun `bili row without pubdate parses as unknown time`() {
        val body = """
            {"code":0,"message":"0","data":{"result":[
              {"title":"老视频","author":"某某","play":1,"pic":"//i0.hdslb.com/bfs/y.jpg","bvid":"BV1aa"}
            ]}}
        """.trimIndent()
        assertEquals(0L, BiliApi.parseSearchResponse(body)[0].pubdate)
    }

    /** 非 JSON 响应体（网关把请求换成了 HTML 拦截页）不能算"零结果" */
    @Test
    fun `bili non-json body is a failure not an empty result`() {
        val outcome = runCatching { BiliApi.parseSearchResponse("<html><body>banned</body></html>") }
        assertNotNull("实际=${outcome.getOrNull()}", outcome.exceptionOrNull())
    }
}
