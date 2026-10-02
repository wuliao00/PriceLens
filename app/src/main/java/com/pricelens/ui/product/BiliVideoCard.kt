package com.pricelens.ui.product

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pricelens.R
import com.pricelens.data.remote.BiliApi
import com.pricelens.ui.common.AsyncValue
import com.pricelens.ui.common.EmptyStateCause
import com.pricelens.ui.common.EmptyStateCauseOf
import com.pricelens.ui.common.valueOrDefault
import com.pricelens.ui.common.valueOrNull
import com.pricelens.ui.components.AppImage
import com.pricelens.ui.components.EmptyState
import com.pricelens.ui.components.PriceCard
import com.pricelens.ui.components.ShimmerList
import com.pricelens.ui.overview.SearchViewModel
import com.pricelens.ui.theme.Dims
import com.pricelens.ui.theme.LocalSemanticColors
import com.pricelens.util.ContentRisk
import com.pricelens.util.TimeAgo
import com.pricelens.util.UrlOpener

/**
 * §十一 详情页「评测」段。
 *
 * 这就是原来的 `ui/bilibili/BilibiliScreen.kt`：底部导航 6→4 之后 B 站不再是独立 tab，
 * 渲染整体搬到这里来（顶层页文件删除，不留两份平行实现）。原样保留的取证结论：
 *  - §6.1 疑似商单/夸大宣传只标记沉底、不删除；
 *  - §2.5 LazyColumn 用 bvid 作稳定 key、封面 16:9 降采样；
 *  - F4：空列表只剩"够着了但 0 条"这一种含义，空态成因走 [EmptyStateCauseOf] 同一套判据。
 *
 * 详情页新增两件事：
 *  1. 「近半年」筛选开关（[withinRecentWindow]，接口没给 pubdate 的条目照常保留）；
 *  2. 打开视频时装了 B站 App 走原生深链、没装才回落 https（修复旧版"恒为 https"）。
 */
@Composable
fun BiliReviewSection(searchViewModel: SearchViewModel) {
    val loading by searchViewModel.loading.collectAsStateWithLifecycle()
    val keyword by searchViewModel.keyword.collectAsStateWithLifecycle()
    val videosAsync by searchViewModel.videos.collectAsStateWithLifecycle()
    val productAsync by searchViewModel.product.collectAsStateWithLifecycle()
    val videos = videosAsync.valueOrDefault(emptyList())

    var recentOnly by rememberSaveable { mutableStateOf(false) }
    var allowDeepLink by rememberSaveable { mutableStateOf(true) }
    val biliInstalled = rememberBiliInstalled()
    // 窗口起点在同一屏内固定，避免每次重组都重新取时钟导致"边界条目一闪一闪"
    val nowEpochSec = remember { System.currentTimeMillis() / 1_000 }
    val kept = if (recentOnly) {
        videos.filter { withinRecentWindow(it.pubdate, nowEpochSec, REVIEW_WINDOW_MONTHS) }
    } else {
        videos
    }

    Column(Modifier.fillMaxSize()) {
        if (videos.isNotEmpty()) {
            BiliFilterRow(
                recentOnly = recentOnly,
                droppedCount = videos.size - kept.size,
                allowDeepLink = allowDeepLink,
                biliInstalled = biliInstalled,
                onRecentChange = { recentOnly = it },
                onDeepLinkChange = { allowDeepLink = it }
            )
            ReviewKeywordRow(
                searchViewModel = searchViewModel,
                keyword = keyword,
                title = productAsync.valueOrNull()?.title
            )
        }
        Box(Modifier.weight(1f)) {
            BiliVideoBody(
                videosAsync = videosAsync,
                videos = kept,
                hadAnyBeforeFilter = videos.isNotEmpty(),
                keyword = keyword,
                loading = loading,
                biliInstalled = biliInstalled,
                allowDeepLink = allowDeepLink,
                nowEpochSec = nowEpochSec
            )
        }
    }
}

/** 两个开关：近半年筛选 / B站 App 深链（没装 App 时深链开关没有意义，只说一句实况） */
@Composable
private fun BiliFilterRow(
    recentOnly: Boolean,
    droppedCount: Int,
    allowDeepLink: Boolean,
    biliInstalled: Boolean,
    onRecentChange: (Boolean) -> Unit,
    onDeepLinkChange: (Boolean) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Dims.SpacingXL, vertical = Dims.SpacingS)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { onRecentChange(!recentOnly) }
        ) {
            Text(
                stringResource(R.string.product_review_recent),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            if (droppedCount > 0) {
                Text(
                    stringResource(R.string.product_review_filtered, droppedCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(Dims.SpacingS))
            }
            Switch(checked = recentOnly, onCheckedChange = onRecentChange)
        }
        if (biliInstalled) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { onDeepLinkChange(!allowDeepLink) }
            ) {
                Text(
                    stringResource(R.string.product_review_deep_link),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = allowDeepLink, onCheckedChange = onDeepLinkChange)
            }
        }
        Text(
            stringResource(
                if (biliInstalled) {
                    R.string.product_review_deep_link_installed
                } else {
                    R.string.product_review_deep_link_absent
                }
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 「标题太脏搜不出评测」的出口：把派生关键词（[reviewKeyword]）摊开给用户看，
 * 点一下就按干净关键词重搜。派生结果与当前关键词一致时不出现（没有可修正的东西）。
 */
@Composable
private fun ReviewKeywordRow(searchViewModel: SearchViewModel, keyword: String, title: String?) {
    val derived = remember(title) { reviewKeyword(title.orEmpty()) }
    if (derived.isBlank() || derived == keyword) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dims.SpacingXL)
    ) {
        Text(
            stringResource(R.string.product_review_keyword_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            modifier = Modifier.weight(1f)
        )
        AssistChip(
            onClick = { searchViewModel.research(derived) },
            label = { Text(stringResource(R.string.product_review_retry_keyword, derived), maxLines = 1) }
        )
    }
}

/** 三态渲染 + 列表主体（原 BilibiliScreen 的分支结构 1:1 搬过来，只是数据换成本地筛选结果） */
@Composable
private fun BiliVideoBody(
    videosAsync: AsyncValue<List<BiliApi.BiliVideo>>,
    videos: List<BiliApi.BiliVideo>,
    hadAnyBeforeFilter: Boolean,
    keyword: String,
    loading: Boolean,
    biliInstalled: Boolean,
    allowDeepLink: Boolean,
    nowEpochSec: Long
) {
    if (videosAsync is AsyncValue.Loading<*> || (loading && videos.isEmpty())) {
        ShimmerList()
        return
    }
    if (videosAsync is AsyncValue.Error<*> && videos.isEmpty()) {
        EmptyState(
            icon = Icons.Filled.Warning,
            title = stringResource(R.string.error_load_failed),
            desc = stringResource(R.string.error_retry_hint),
            modifier = Modifier.padding(Dims.SpacingXL)
        )
        return
    }
    if (videos.isEmpty()) {
        if (hadAnyBeforeFilter) {
            // 列表本来有货，是「近半年」开关把它筛空的 —— 这不能说成"没有相关视频"
            EmptyState(
                icon = Icons.Filled.OndemandVideo,
                title = stringResource(R.string.product_review_recent),
                desc = stringResource(R.string.product_review_recent_desc),
                modifier = Modifier.padding(Dims.SpacingXL)
            )
            return
        }
        val emptyCause = EmptyStateCauseOf.of(keyword.isNotBlank(), listOf(videosAsync))
        EmptyState(
            icon = Icons.Filled.OndemandVideo,
            title = stringResource(
                when (emptyCause) {
                    EmptyStateCause.NOT_SEARCHED -> R.string.empty_search_first
                    EmptyStateCause.UNREACHABLE -> R.string.error_load_failed
                    EmptyStateCause.NO_MATCH -> R.string.bili_no_result
                }
            ),
            desc = stringResource(
                when (emptyCause) {
                    EmptyStateCause.NOT_SEARCHED -> R.string.bili_empty_hint
                    EmptyStateCause.UNREACHABLE -> R.string.error_retry_hint
                    EmptyStateCause.NO_MATCH -> R.string.bili_no_result_desc
                }
            ),
            modifier = Modifier.padding(Dims.SpacingXL)
        )
        return
    }

    val context = LocalContext.current
    // 稳定排序：被标记（疑似商单/夸大宣传）的视频沉底，其余保持原相关度顺序
    val sorted = videos.sortedBy { it.risk.flagged }
    val flaggedCount = videos.count { it.risk.flagged }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Dims.SpacingXL)
    ) {
        if (videosAsync is AsyncValue.Error<*>) {
            // 失败但持有旧数据：顶部提示，列表照常展示
            item(key = "error_hint") {
                EmptyState(
                    icon = Icons.Filled.Warning,
                    title = stringResource(R.string.error_load_failed),
                    desc = stringResource(R.string.error_retry_hint)
                )
                Spacer(Modifier.height(Dims.SpacingM))
            }
        }
        if (flaggedCount > 0) {
            item(key = "risk_hint") {
                Text(
                    stringResource(R.string.bili_risk_hint, flaggedCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = Dims.SpacingM)
                )
            }
        }
        items(sorted, key = { it.bvid }) { video ->
            BiliVideoCard(video = video, pubdateLabel = pubdateLabel(video, nowEpochSec)) {
                val target = biliTarget(video.bvid, biliInstalled, allowDeepLink).url
                if (target.isNotBlank()) UrlOpener.open(context, target)
            }
            Spacer(Modifier.height(Dims.SpacingM))
        }
    }
}

/** 发布时间一行：0（接口没给）如实说"未知"，不假装是 1970 */
@Composable
private fun pubdateLabel(video: BiliApi.BiliVideo, nowEpochSec: Long): String = if (video.pubdate > 0) {
    stringResource(R.string.product_review_pubdate, TimeAgo.format(video.pubdate * 1_000, nowEpochSec * 1_000))
} else {
    stringResource(R.string.product_review_pubdate_unknown)
}

@Composable
fun BiliVideoCard(video: BiliApi.BiliVideo, pubdateLabel: String, onClick: () -> Unit) {
    PriceCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        AppImage(
            url = video.pic,
            contentDescription = video.title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
            corner = Dims.ChipCorner
        )
        Spacer(Modifier.height(Dims.SpacingM))
        Text(
            video.title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(Dims.SpacingS))
        Text(
            "$pubdateLabel · ${video.author} · ${formatPlay(video.play)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Dims.SpacingS))
        Row {
            RiskChip(video.risk)
            KeywordChip(video.title)
        }
    }
}

/** §6.1 关键词高亮：负面红 / 正面绿 AssistChip（暗色感知语义色） */
@Composable
private fun KeywordChip(title: String) {
    val semantic = LocalSemanticColors.current
    val negative = NEGATIVE_WORDS.firstOrNull { title.contains(it) }
    val positive = POSITIVE_WORDS.firstOrNull { title.contains(it) }
    val (word, color) = when {
        negative != null -> negative to semantic.suspicious
        positive != null -> positive to semantic.lowPrice
        else -> return
    }
    AssistChip(
        onClick = {},
        label = { Text(word) },
        colors = AssistChipDefaults.assistChipColors(
            labelColor = color,
            containerColor = color.copy(alpha = 0.10f)
        ),
        border = null
    )
}

/** 内容风险标签：疑似商单（红）/ 夸大宣传（三级强调色），命中词随标展示 */
@Composable
private fun RiskChip(risk: ContentRisk) {
    val (word, color) = when {
        risk.sponsored -> stringResource(R.string.bili_risk_sponsored) to
            LocalSemanticColors.current.suspicious
        risk.hype -> stringResource(R.string.bili_risk_hype) to
            MaterialTheme.colorScheme.tertiary
        else -> return
    }
    AssistChip(
        onClick = {},
        label = { Text(word, maxLines = 1) },
        colors = AssistChipDefaults.assistChipColors(
            labelColor = color,
            containerColor = color.copy(alpha = 0.12f)
        ),
        border = null
    )
    Spacer(Modifier.width(Dims.SpacingS))
}

/**
 * B站 App 装没装（Android 11+ 的包可见性受清单 `<queries>` 约束，
 * 少了那条声明这里就一定查不到 → 深链永远走不到，所以清单里同步补了 tv.danmaku.bili）。
 */
@Composable
private fun rememberBiliInstalled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(BILI_PACKAGE, 0)
            true
        }.getOrDefault(false)
    }
}

private val NEGATIVE_WORDS = listOf("翻车", "避坑", "缺点", "退货", "踩雷")
private val POSITIVE_WORDS = listOf("推荐", "真香")

private fun formatPlay(play: Long): String = when {
    play >= 100_000_000 -> "${play / 100_000_000}亿"
    play >= 10_000 -> "${play / 10_000}万"
    else -> play.toString()
}
