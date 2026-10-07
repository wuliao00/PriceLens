package com.pricelens.ui.community

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pricelens.R
import com.pricelens.community.CommunityFeed
import com.pricelens.community.CommunityPost
import com.pricelens.community.CommunityState
import com.pricelens.ui.components.PriceCard
import com.pricelens.ui.components.SectionHeader
import com.pricelens.ui.theme.Dims
import com.pricelens.util.TimeAgo
import com.pricelens.util.UrlOpener

/**
 * 社区零服务器（文档 §九）的展示层：GitHub Discussions → community.json → Gitee raw 拉取。
 * 诚实三态：拿不到就说拿不到（带原因）；有旧数据就标注「本轮刷新失败」；空社区引导去发第一帖。
 * 发帖走浏览器深链（鉴权交给 GitHub），本条路径对"需能访问 GitHub 的网络"如实标注。
 */
@Composable
fun CommunityFeedSection() {
    val viewModel: CommunityFeedViewModel = hiltViewModel()
    LaunchedEffect(Unit) { viewModel.ensureLoaded() }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxWidth()) {
        SectionHeader(stringResource(R.string.community_feed_title))
        when (val current = state) {
            is CommunityState.Loading -> Text(
                stringResource(R.string.community_feed_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            is CommunityState.Unavailable -> {
                Text(
                    stringResource(R.string.community_feed_unavailable, current.reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            is CommunityState.Ready -> {
                if (current.posts.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.ChatBubble,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(Dims.SpacingL)
                        )
                        Spacer(Modifier.size(Dims.SpacingS))
                        Text(
                            stringResource(R.string.community_feed_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    current.posts.take(CommunityFeed.PREVIEW_POSTS).forEach { post ->
                        CommunityPostCard(post)
                        Spacer(Modifier.height(Dims.SpacingS))
                    }
                    if (current.posts.size > CommunityFeed.PREVIEW_POSTS) {
                        Text(
                            stringResource(R.string.community_feed_more),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (current.stale) {
                    Text(
                        stringResource(R.string.community_feed_stale),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        NewDiscussionRow()
    }
}

/**
 * 社区动态卡片：稳定三段（标题 1 行 + 摘要 2 行 + 元信息 1 行）。
 *
 * 改动前这张卡是"徽标行 + 标题 2 行 + 摘要 3 行 + 回复 2 行 + 落款行"——
 * 一屏放得下的帖子数完全取决于摘要长短，扫读时眼睛找不到对齐线。
 * 现在段数与每段行数都固定，预算与截断口径见 [CommunityRows]（纯函数 + JVM 单测）。
 *
 * 摘要取值有一条诚实的回落：帖子没有正文摘要时，用第一条回复充当摘要——
 * 内容还是那条内容，只是换了个段来放（回复比"这段是空的"有用）。
 */
@Composable
private fun CommunityPostCard(post: CommunityPost) {
    val context = LocalContext.current
    val parts = CommunityRows.of(
        title = post.title,
        excerpt = post.excerpt.ifBlank { post.replies.firstOrNull()?.body ?: "" },
        metaParts = listOf(
            post.category,
            stringResource(R.string.layout_community_author_prefix, post.author),
            if (post.updatedAtMs > 0) TimeAgo.format(post.updatedAtMs) else "",
            if (post.replies.isNotEmpty()) stringResource(R.string.layout_community_replies, post.replies.size) else ""
        )
    )
    PriceCard(modifier = Modifier.fillMaxWidth(), onClick = { UrlOpener.open(context, post.url) }) {
        Text(
            parts.title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        parts.excerpt?.let {
            Spacer(Modifier.height(Dims.SpacingXS))
            // 段宽已由 CommunityRows 收敛到两行预算，这里的 maxLines=2 只是兜底
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(Dims.SpacingXS))
        Text(
            parts.meta,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun NewDiscussionRow() {
    val context = LocalContext.current
    // F3（2026-10-07 真机截图复核，改了两轮才对的排法）：
    // ① 最初版是「按钮右对齐一行 + 说明另起一行贴左」——说明读起来像在讲下面那块内容，
    //    而不是在讲这个按钮，动作和它的限制条件分了家。
    // ② 中间版把两者塞进同一行（说明 weight(1f) + 按钮在右）：真机上说明被按钮挤成
    //    **四行窄栏**，正是本轮在 SetupHintBar 里刚修掉的那个毛病，不能留在这儿。
    // ③ 现在这版：说明占满整行、按钮紧跟其下并与它同左边缘。相邻 + 共享左边界 = 一组，
    //    文字也不必让位给按钮。
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.community_feed_post_hint),
            // 这句是脚注（限制条件），字重必须低于上面的「社区还没有帖子」：本主题的
            // labelSmall 是 12sp **SemiBold**，与那句 bodySmall（12sp Normal）同尺寸却更粗，
            // 真机上"次要的一句"反而比"主句"更响（2026-10-08 截图复核）→ 换成 bodySmall。
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(
            onClick = { UrlOpener.open(context, CommunityFeed.NEW_DISCUSSION_URL) },
            modifier = Modifier.heightIn(min = Dims.TouchMin)
        ) {
            Text(stringResource(R.string.community_feed_post_new))
        }
    }
}
