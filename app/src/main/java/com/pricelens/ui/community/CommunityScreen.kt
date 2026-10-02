package com.pricelens.ui.community

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.pricelens.ui.common.EmptyStateCause
import com.pricelens.ui.common.EmptyStateCauseOf
import com.pricelens.ui.common.valueOrDefault
import com.pricelens.ui.components.AppImage
import com.pricelens.ui.components.EmptyState
import com.pricelens.ui.components.PriceCard
import com.pricelens.ui.components.SectionHeader
import com.pricelens.ui.components.ShimmerList
import com.pricelens.ui.overview.SearchViewModel
import com.pricelens.ui.theme.Dims
import com.pricelens.ui.theme.LocalSemanticColors
import com.pricelens.ui.theme.PriceType
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
    val keyword by searchViewModel.keyword.collectAsStateWithLifecycle()
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
        // F4（2026-09-29）：三种成因分开说（判定见 EmptyStateCauseOf，有 JVM 单测）。
        // 修复前这里只有两分支，"识货/值得买都被拦或全机断网"会落到 else 上，
        // 对刚搜过的人讲「请先搜索商品」——一句没发生过的原因。
        val emptyCause = EmptyStateCauseOf.of(
            keyword.isNotBlank(),
            listOf(postsAsync, shihuoAsync)
        )
        // 社区动态（§九 Discussions 镜像）不依赖关键词，空态页也要能见到它可读/可发帖
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Dims.SpacingXL)
        ) {
            CommunityFeedSection()
            Spacer(Modifier.height(Dims.SpacingXL))
            EmptyState(
                icon = if (emptyCause == EmptyStateCause.UNREACHABLE) Icons.Filled.Warning else Icons.Filled.ChatBubble,
                title = stringResource(
                    when (emptyCause) {
                        EmptyStateCause.NOT_SEARCHED -> R.string.empty_search_first
                        EmptyStateCause.UNREACHABLE -> R.string.error_load_failed
                        EmptyStateCause.NO_MATCH -> R.string.community_no_result
                    }
                ),
                desc = stringResource(
                    when (emptyCause) {
                        EmptyStateCause.NOT_SEARCHED -> R.string.community_empty_hint
                        EmptyStateCause.UNREACHABLE -> R.string.error_retry_hint
                        EmptyStateCause.NO_MATCH -> R.string.community_no_result_desc
                    }
                )
            )
        }
        return
    }

    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Dims.SpacingXL)
    ) {
        // 社区动态（§九）：不依赖搜索关键词的 Discussions 镜像，固定在最上方
        item(key = "feed") {
            CommunityFeedSection()
            Spacer(Modifier.height(Dims.SpacingL))
        }
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

/**
 * 识货商品卡：标题 1 行 + 元信息 1 行（价在元信息之前，同一行）。
 *
 * 改动前是"标题 2 行 + 价/品牌/销量/国补挤在一行"，长标题会把元信息顶出屏。
 * 现在段数固定、每段一行，预算与截断口径全部走 [CommunityRows]（字簇口径，emoji 不截半）。
 * 摘要段识货没给（搜索接口只有标题），按稳定三段的降级规则整段不出现。
 */
@Composable
private fun ShihuoCard(item: ShihuoApi.ShihuoItem, onClick: () -> Unit) {
    val parts = CommunityRows.of(
        title = item.title,
        excerpt = null,
        metaParts = listOfNotNull(
            item.brand.takeIf { it.isNotEmpty() },
            item.salesInfo.takeIf { it.isNotEmpty() },
            if (item.hasSubsidy) stringResource(R.string.community_subsidy) else null,
            if (item.url.isEmpty()) stringResource(R.string.layout_link_missing) else null
        )
    )
    PriceCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (item.image.isNotEmpty()) {
                AppImage(
                    url = item.image,
                    contentDescription = item.title,
                    modifier = Modifier.size(Dims.ThumbHeader),
                    corner = Dims.ChipCorner
                )
                Spacer(Modifier.size(Dims.SpacingS))
            }
            Text(
                parts.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(Dims.SpacingXS))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                PriceFormatter.format(item.price),
                style = PriceType.PriceRowCompact,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.size(Dims.SpacingS))
            Text(
                parts.meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
    }
}

/**
 * 值得买爆料卡：标题 1 行（关键词高亮保留）+ 元信息 1 行（价 · 商城 · 值/不值票数）。
 *
 * 上一轮把「值不值」从 11sp 挤角标抬成整行 + 进度条，是因为那时它**只有**这一处展示；
 * 现在票数同时进元信息行（文字，可被 TalkBack 读到），进度条退成"装饰 + 一眼比例感"，
 * 没票时两者都不出现——不摆空条冒充结论。
 */
@Composable
private fun PostCard(post: SmzdmApi.SmzdmPost, onClick: () -> Unit) {
    val semantic = LocalSemanticColors.current
    val votes = post.positive + post.negative
    val parts = CommunityRows.of(
        title = post.title,
        excerpt = null,
        metaParts = listOfNotNull(
            post.mall.takeIf { it.isNotEmpty() },
            if (votes > 0) {
                stringResource(R.string.layout_community_votes, post.positive, post.negative)
            } else {
                stringResource(R.string.layout_community_votes_none)
            }
        )
    )
    PriceCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Text(
            highlightKeywords(parts.title, semantic),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(Dims.SpacingXS))
        Row(verticalAlignment = Alignment.CenterVertically) {
            post.price?.let {
                Text(
                    PriceFormatter.format(it),
                    style = PriceType.PriceRowCompact,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.size(Dims.SpacingS))
            }
            Text(
                parts.meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
        if (votes > 0) {
            Spacer(Modifier.height(Dims.SpacingXS))
            LinearProgressIndicator(
                progress = { post.positive.toFloat() / votes },
                color = semantic.lowPrice,
                trackColor = semantic.suspicious.copy(alpha = 0.25f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Dims.SpacingXS)
                    .height(Dims.SpacingXS)
            )
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
