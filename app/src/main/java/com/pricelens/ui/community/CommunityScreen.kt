package com.pricelens.ui.community

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.pricelens.data.remote.SmzdmApi
import com.pricelens.ui.components.PriceCard
import com.pricelens.ui.components.ShimmerList
import com.pricelens.ui.main.MainUiState
import com.pricelens.ui.theme.Dims
import com.pricelens.ui.theme.SemanticColors

/**
 * §6.4 临门一脚 — 什么值得买爆料 + 值/不值进度条。
 * 关键词高亮：神价|史低 → 绿加粗；翻车|品控 → 红加粗（AnnotatedString，无重组动画）。
 */
@Composable
fun CommunityScreen(state: MainUiState) {
    if (state.posts.isEmpty()) {
        if (state.loading) ShimmerList()
        else com.pricelens.ui.bilibili.EmptyHint("搜索后聚合什么值得买最新爆料与热评")
        return
    }
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Dims.SpacingXL)
    ) {
        items(state.posts, key = { it.url.ifEmpty { it.title } }) { post ->
            PostCard(post) {
                // 唤起值得买/京东/淘宝 App，未安装回退浏览器
                if (post.url.isNotEmpty()) {
                    com.pricelens.util.UrlOpener.open(context, post.url)
                }
            }
            Spacer(Modifier.height(Dims.SpacingM))
        }
    }
}

@Composable
private fun PostCard(post: SmzdmApi.SmzdmPost, onClick: () -> Unit) {
    PriceCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Text(
            highlightKeywords(post.title),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(Dims.SpacingS))
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            post.price?.let {
                Text(
                    com.pricelens.util.PriceFormatter.format(it),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(Dims.SpacingM))
            }
            val total = (post.positive + post.negative).coerceAtLeast(1)
            Column(Modifier.weight(1f)) {
                Text(
                    "值 ${post.positive} / 不值 ${post.negative}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LinearProgressIndicator(
                    progress = { post.positive.toFloat() / total },
                    color = SemanticColors.LowPrice,
                    trackColor = SemanticColors.Suspicious.copy(alpha = 0.25f),
                    modifier = Modifier.fillMaxWidth().height(4.dp)
                )
            }
        }
    }
}

private val GOOD_WORDS = listOf("神价", "史低")
private val BAD_WORDS = listOf("翻车", "品控")

/** §6.4 关键词高亮 */
private fun highlightKeywords(title: String) = buildAnnotatedString {
    var cursor = 0
    val matches = mutableListOf<Triple<Int, Int, Boolean>>() // start, end, good
    (GOOD_WORDS.map { it to true } + BAD_WORDS.map { it to false }).forEach { (word, good) ->
        var from = 0
        while (true) {
            val idx = title.indexOf(word, from)
            if (idx < 0) break
            matches += Triple(idx, idx + word.length, good)
            from = idx + word.length
        }
    }
    matches.sortedBy { it.first }.forEach { (start, end, good) ->
        if (start < cursor) return@forEach  // 重叠跳过
        append(title.substring(cursor, start))
        withStyle(
            SpanStyle(
                color = if (good) SemanticColors.LowPrice else SemanticColors.Suspicious,
                fontWeight = FontWeight.Bold
            )
        ) { append(title.substring(start, end)) }
        cursor = end
    }
    if (cursor < title.length) append(title.substring(cursor))
}
