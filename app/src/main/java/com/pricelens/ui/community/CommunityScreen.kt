package com.pricelens.ui.community

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pricelens.R
import com.pricelens.data.remote.ShihuoApi
import com.pricelens.data.remote.SmzdmApi
import com.pricelens.ui.common.AsyncValue
import com.pricelens.ui.common.valueOrDefault
import com.pricelens.ui.components.AppImage
import com.pricelens.ui.components.EmptyState
import com.pricelens.ui.components.PriceCard
import com.pricelens.ui.components.SectionHeader
import com.pricelens.ui.components.ShimmerList
import com.pricelens.ui.overview.SearchViewModel
import com.pricelens.ui.theme.Dims
import com.pricelens.ui.theme.LocalSemanticColors
import com.pricelens.ui.theme.SemanticPalette
import com.pricelens.util.PriceFormatter
import com.pricelens.util.UrlOpener

/**
 * §6.4 临门一脚 — 识货商品 + 什么值得买爆料 + 值/不值进度条。
 * 识货补充鞋服/数码等当当覆盖不到的品类，含国补标记。
 * 关键词高亮：神价|史低 → 语义绿加粗；翻车|品控 → 语义红加粗（AnnotatedString）。
 * 阶段4：三态渲染 + 语义色走 LocalSemanticColors + 区块标题走 SectionHeader。
 */
@Composable
fun CommunityScreen(searchViewModel: SearchViewModel) {
    val loading by searchViewModel.loading.collectAsStateWithLifecycle()
    val postsAsync by searchViewModel.posts.collectAsStateWithLifecycle()
    val shihuoAsync by searchViewModel.shihuo.collectAsStateWithLifecycle()
    val posts = postsAsync.valueOrDefault(emptyList())
    val shihuoItems = shihuoAsync.valueOrDefault(emptyList())

    val anyLoading = postsAsync is AsyncValue.Loading<*> || shihuoAsync is AsyncValue.Loading<*>
    if (anyLoading || (loading && posts.isEmpty() && shihuoItems.isEmpty())) {
        ShimmerList()
        return
    }
    val anyError = postsAsync is AsyncValue.Error<*> || shihuoAsync is AsyncValue.Error<*>
    if (posts.isEmpty() && shihuoItems.isEmpty()) {
        EmptyState(
            icon = if (anyError) Icons.Filled.Warning else Icons.Filled.ChatBubble,
            title = stringResource(
                if (anyError) R.string.error_load_failed else R.string.empty_search_first
            ),
            desc = stringResource(
                if (anyError) R.string.error_retry_hint else R.string.community_empty_hint
            ),
            modifier = Modifier.padding(Dims.SpacingXL)
        )
        return
    }

    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Dims.SpacingXL)
    ) {
        if (anyError) {
            // 失败但持有旧数据：顶部提示，列表照常展示
            item(key = "error_hint") {
                EmptyState(
                    icon = Icons.Filled.Warning,
                    title = stringResource(R.string.error_load_failed),
                    desc = stringResource(R.string.error_retry_hint)
                )
            }
        }
        if (shihuoItems.isNotEmpty()) {
            item(key = "shihuo_header") {
                SectionHeader(stringResource(R.string.community_shihuo))
            }
            itemsIndexed(shihuoItems, key = { index, item -> "sh:${index}_${item.goodsId}" }) { _, item ->
                ShihuoCard(item) {
                    if (item.url.isNotEmpty()) UrlOpener.open(context, item.url)
                }
                Spacer(Modifier.height(Dims.SpacingM))
            }
            if (posts.isNotEmpty()) {
                item(key = "smzdm_header") {
                    SectionHeader(stringResource(R.string.community_smzdm))
                }
            }
        }
        itemsIndexed(posts, key = { index, post -> "smzdm:${index}_${post.url.ifEmpty { post.title }}" }) { _, post ->
            PostCard(post) {
                // 唤起值得买/京东/淘宝 App，未安装回退浏览器
                if (post.url.isNotEmpty()) {
                    UrlOpener.open(context, post.url)
                }
            }
            Spacer(Modifier.height(Dims.SpacingM))
        }
    }
}

/** 识货商品卡：标题 + 价格 + 品牌/销量 + 国补标签 */
@Composable
private fun ShihuoCard(item: ShihuoApi.ShihuoItem, onClick: () -> Unit) {
    val semantic = LocalSemanticColors.current
    PriceCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row {
            if (item.image.isNotEmpty()) {
                AppImage(
                    url = item.image,
                    contentDescription = item.title,
                    modifier = Modifier
                        .width(Dims.SpacingXXXL * 2)
                        .height(Dims.SpacingXXXL * 2)
                        .padding(end = Dims.SpacingM),
                    corner = Dims.ChipCorner
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(Dims.SpacingS))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        PriceFormatter.format(item.price),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFeatureSettings = "tnum"
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(Dims.SpacingM))
                    Text(
                        listOf(item.brand, item.salesInfo).filter { it.isNotEmpty() }
                            .joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (item.hasSubsidy) {
                        Spacer(Modifier.width(Dims.SpacingS))
                        Text(
                            stringResource(R.string.community_subsidy),
                            style = MaterialTheme.typography.labelSmall,
                            color = semantic.lowPrice,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PostCard(post: SmzdmApi.SmzdmPost, onClick: () -> Unit) {
    val semantic = LocalSemanticColors.current
    PriceCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Text(
            highlightKeywords(post.title, semantic),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(Dims.SpacingS))
        Row(verticalAlignment = Alignment.CenterVertically) {
            post.price?.let {
                Text(
                    PriceFormatter.format(it),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontFeatureSettings = "tnum"
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(Dims.SpacingM))
            }
            val total = (post.positive + post.negative).coerceAtLeast(1)
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.community_worth_votes, post.positive, post.negative),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LinearProgressIndicator(
                    progress = { post.positive.toFloat() / total },
                    color = semantic.lowPrice,
                    trackColor = semantic.suspicious.copy(alpha = 0.25f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Dims.SpacingXS)
                )
            }
        }
    }
}

private val GOOD_WORDS = listOf("神价", "史低")
private val BAD_WORDS = listOf("翻车", "品控")

/** §6.4 关键词高亮：颜色取自当前主题的语义色（暗色感知） */
private fun highlightKeywords(title: String, semantic: SemanticPalette) = buildAnnotatedString {
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
        if (start < cursor) return@forEach // 重叠跳过
        append(title.substring(cursor, start))
        withStyle(
            SpanStyle(
                color = if (good) semantic.lowPrice else semantic.suspicious,
                fontWeight = FontWeight.Bold
            )
        ) { append(title.substring(start, end)) }
        cursor = end
    }
    if (cursor < title.length) append(title.substring(cursor))
}
