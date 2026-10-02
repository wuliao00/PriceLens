package com.pricelens.data.repository

import com.pricelens.data.remote.BiliApi
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §十一：详情页「近半年」筛选读的是 [BiliApi.BiliVideo.pubdate]，
 * 而视频列表会走 L2 缓存 —— pubdate 要是没过缓存，命中缓存的那一轮筛开关就成了摆设
 * （所有条目都变成"发布时间未知"，永远保留）。
 */
class BiliVideoCachePubdateTest {

    @Test
    fun `pubdate survives the cache round trip`() {
        val videos = listOf(
            BiliApi.BiliVideo(
                title = "半年内的视频",
                cleanTitle = "半年内的视频",
                author = "某某",
                play = 10,
                pic = "https://i0.hdslb.com/a.jpg",
                bvid = "BV1new",
                pubdate = 1_765_800_000L
            )
        )
        val decoded = BiliVideosCodec.decode(BiliVideosCodec.encode(videos))
        assertEquals(1, decoded.size)
        assertEquals("缓存往返后 pubdate 不能丢", 1_765_800_000L, decoded[0].pubdate)
        assertEquals("BV1new", decoded[0].bvid)
    }

    /** 升级前写进缓存的旧条目没有 pd 字段：必须解成 0（"不知道"），不能变成负数或当前时间 */
    @Test
    fun `legacy cache entry without pubdate decodes as unknown time`() {
        val legacy = """
            [{"title":"旧条目","author":"某某","play":1,"pic":"https://x/y.jpg","bvid":"BVold",
              "sponsored":false,"hype":false,"sw":null,"hw":null}]
        """.trimIndent()
        val decoded = BiliVideosCodec.decode(legacy)
        assertEquals(1, decoded.size)
        assertEquals(0L, decoded[0].pubdate)
    }
}
