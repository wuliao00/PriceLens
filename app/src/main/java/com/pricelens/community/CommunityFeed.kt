package com.pricelens.community

import org.json.JSONArray
import org.json.JSONObject

/**
 * 社区零服务器（文档 §九）：仓库 Discussions 由 Actions 定时导出为 `community.json`，
 * App 经 Gitee raw 拉取（沿用"国内 GitHub 直连不通、Gitee 直连可用"这一现实约束）。
 *
 * 本文件是**纯解析层**（org.json 手写，与 update/ 同一条红线）：
 *  - 信封严格（schemaVersion 必须是 1）；单条宽松（缺标题/链接的帖子跳过，不连坐整份列表）；
 *  - 数量与长度全部设上限（远端内容不可全信，防一张巨型帖拖垮列表）。
 */
data class CommunityReply(val author: String, val body: String)

data class CommunityPost(
    val title: String,
    val url: String,
    val category: String,
    val author: String,
    val updatedAtMs: Long,
    val excerpt: String,
    val replies: List<CommunityReply>
)

sealed interface FeedParseResult {
    data class Valid(val generatedAtMs: Long, val posts: List<CommunityPost>) : FeedParseResult

    data class Rejected(val reason: String) : FeedParseResult
}

object CommunityFeed {

    const val SCHEMA_VERSION = 1
    const val MAX_POSTS = 50
    const val MAX_REPLIES = 3
    const val MAX_TEXT_CHARS = 200

    /** App 只展示最近这么多条（整页预览），全部内容在 GitHub 看 */
    const val PREVIEW_POSTS = 10

    /** 发帖入口：GitHub 会让用户选分类；v1 不内嵌 OAuth，鉴权交给浏览器 */
    const val NEW_DISCUSSION_URL = "https://github.com/wuliao00/PriceLens/discussions/new"

    /** 数据源：Gitee raw（与 update.json / rules 同一仓同分支） */
    const val FEED_URL = "https://gitee.com/wuliao11541/PriceLens/raw/main/community.json"

    fun parse(raw: String?): FeedParseResult {
        if (raw.isNullOrBlank()) return FeedParseResult.Rejected("空内容")
        val root = runCatching { JSONObject(raw) }.getOrNull()
            ?: return FeedParseResult.Rejected("JSON 不可解析")
        if (root.optInt("schemaVersion", -1) != SCHEMA_VERSION) {
            return FeedParseResult.Rejected("schemaVersion 不认识")
        }
        val generatedAtMs = parseIsoMillis(root.optString("generatedAt"))
        val array = root.optJSONArray("posts") ?: JSONArray()
        val posts = buildList {
            for (index in 0 until minOf(array.length(), MAX_POSTS)) {
                val obj = array.optJSONObject(index) ?: continue
                toPost(obj)?.let { add(it) }
            }
        }
        return FeedParseResult.Valid(generatedAtMs, posts)
    }

    /** 单条容错：标题或链接缺失 → 跳过这一条 */
    private fun toPost(obj: JSONObject): CommunityPost? {
        val title = clip(obj.optString("title"), MAX_TEXT_CHARS).takeIf { it.isNotEmpty() } ?: return null
        val url = obj.optString("url").trim().takeIf { it.startsWith("http") } ?: return null
        val replies = buildList {
            val arr = obj.optJSONArray("replies") ?: JSONArray()
            for (i in 0 until minOf(arr.length(), MAX_REPLIES)) {
                val reply = arr.optJSONObject(i) ?: continue
                add(
                    CommunityReply(
                        author = clip(reply.optString("author"), 60).ifEmpty { "匿名" },
                        body = clip(reply.optString("body"), MAX_TEXT_CHARS)
                    )
                )
            }
        }
        return CommunityPost(
            title = title,
            url = url,
            category = clip(obj.optString("category"), 40).ifEmpty { "讨论" },
            author = clip(obj.optString("author"), 60).ifEmpty { "匿名" },
            updatedAtMs = obj.optLong("updatedAtMs", 0L),
            excerpt = clip(obj.optString("excerpt"), MAX_TEXT_CHARS),
            replies = replies
        )
    }

    /** 折叠空白 + 截断；超长加省略号（列表里不允许出现原始长文） */
    internal fun clip(text: String?, limit: Int): String {
        val collapsed = (text ?: "").trim().replace(Regex("\\s+"), " ")
        return if (collapsed.length > limit) collapsed.take(limit) + "…" else collapsed
    }

    private fun parseIsoMillis(iso: String): Long = runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrDefault(0L)
}
