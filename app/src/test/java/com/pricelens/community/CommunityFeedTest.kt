package com.pricelens.community

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 社区动态解析层（纯函数）：
 *  - 信封严格：schemaVersion 不认识 → 整份拒绝；
 *  - 单条宽松：缺标题/链接的帖子跳过，不连坐整份列表；
 *  - 上限生效：帖数 / 回复数 / 文本长度都被裁剪（远端内容不可全信）。
 */
class CommunityFeedTest {

    @Test
    fun `valid payload parses posts and replies`() {
        val raw = """
            {
              "schemaVersion": 1,
              "generatedAt": "2026-10-02T12:00:00Z",
              "posts": [
                {
                  "title": "京东 9.9 神价线索",
                  "url": "https://github.com/wuliao00/PriceLens/discussions/1",
                  "category": "好价爆料",
                  "author": "wuliao00",
                  "updatedAtMs": 1759400000000,
                  "excerpt": "某款洗衣液两件五折",
                  "replies": [{"author": "momo", "body": "已下单"}]
                }
              ]
            }
        """.trimIndent()
        val result = CommunityFeed.parse(raw)
        assertTrue(result is FeedParseResult.Valid)
        val valid = result as FeedParseResult.Valid
        assertEquals(1, valid.posts.size)
        assertEquals("京东 9.9 神价线索", valid.posts[0].title)
        assertEquals("好价爆料", valid.posts[0].category)
        assertEquals("已下单", valid.posts[0].replies[0].body)
        assertTrue(valid.generatedAtMs > 0)
    }

    @Test
    fun `unknown schema or broken json is rejected as a whole`() {
        assertTrue(CommunityFeed.parse(null) is FeedParseResult.Rejected)
        assertTrue(CommunityFeed.parse("{ not json") is FeedParseResult.Rejected)
        assertTrue(CommunityFeed.parse("""{"schemaVersion":2,"posts":[]}""") is FeedParseResult.Rejected)
    }

    @Test
    fun `bad single post is skipped without killing the list`() {
        val raw = """
            {"schemaVersion":1,"posts":[
              {"title":"","url":"https://x","author":"a"},
              {"title":"没有链接","author":"a"},
              {"title":"好的","url":"https://github.com/wuliao00/PriceLens/discussions/2"}
            ]}
        """.trimIndent()
        val valid = CommunityFeed.parse(raw) as FeedParseResult.Valid
        assertEquals(1, valid.posts.size)
        assertEquals("好的", valid.posts[0].title)
        // 缺省值兜底
        assertEquals("匿名", valid.posts[0].author)
        assertEquals("讨论", valid.posts[0].category)
    }

    @Test
    fun `caps enforce post reply and text limits`() {
        val manyPosts = buildString {
            append("""{"schemaVersion":1,"posts":[""")
            repeat(CommunityFeed.MAX_POSTS + 10) { i ->
                if (i > 0) append(',')
                append("""{"title":"t$i","url":"https://x/$i","replies":[""")
                repeat(CommunityFeed.MAX_REPLIES + 5) { r ->
                    if (r > 0) append(',')
                    append("""{"author":"a$r","body":"b"}""")
                }
                append("]}")
            }
            append("]}")
        }
        val valid = CommunityFeed.parse(manyPosts) as FeedParseResult.Valid
        assertEquals(CommunityFeed.MAX_POSTS, valid.posts.size)
        assertEquals(CommunityFeed.MAX_REPLIES, valid.posts[0].replies.size)

        val longText = "x".repeat(CommunityFeed.MAX_TEXT_CHARS + 50)
        val clipped = CommunityFeed.clip(longText, CommunityFeed.MAX_TEXT_CHARS)
        assertEquals(CommunityFeed.MAX_TEXT_CHARS + 1, clipped.length) // 截断 + 省略号
        assertTrue(clipped.endsWith("…"))
        assertTrue(clipped.startsWith("x"))
    }
}
