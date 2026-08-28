package com.pricelens.ui.bilibili

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pricelens.R
import com.pricelens.data.remote.BiliApi
import com.pricelens.ui.common.AsyncValue
import com.pricelens.ui.common.valueOrDefault
import com.pricelens.ui.components.AppImage
import com.pricelens.ui.components.EmptyState
import com.pricelens.ui.components.PriceCard
import com.pricelens.ui.components.ShimmerList
import com.pricelens.ui.overview.SearchViewModel
import com.pricelens.ui.theme.Dims
import com.pricelens.ui.theme.LocalSemanticColors
import com.pricelens.util.ContentRisk
import com.pricelens.util.UrlOpener

/**
 * §6.1 种草 — B站评测聚合。
 * 疑似商单/含夸大宣传话术的视频打标并沉底（只标记不删除）。
 * §2.5 LazyColumn 稳定 key（bvid）；封面 16:9 RGB_565 降采样。
 * 阶段4：三态渲染 + 语义色走 LocalSemanticColors（暗色感知）。
 */
@Composable
fun BilibiliScreen(searchViewModel: SearchViewModel) {
    val loading by searchViewModel.loading.collectAsStateWithLifecycle()
    val videosAsync by searchViewModel.videos.collectAsStateWithLifecycle()
    val videos = videosAsync.valueOrDefault(emptyList())

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
        EmptyState(
            icon = Icons.Filled.OndemandVideo,
            title = stringResource(R.string.empty_search_first),
            desc = stringResource(R.string.bili_empty_hint),
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
            VideoCard(video) {
                // 唤起B站 App，未安装回退浏览器
                UrlOpener.open(context, "https://www.bilibili.com/video/${video.bvid}")
            }
            Spacer(Modifier.height(Dims.SpacingM))
        }
    }
}

private val NEGATIVE_WORDS = listOf("翻车", "避坑", "缺点", "退货", "踩雷")
private val POSITIVE_WORDS = listOf("推荐", "真香")

@Composable
private fun VideoCard(video: BiliApi.BiliVideo, onClick: () -> Unit) {
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
        Row {
            Text(
                "${video.author} · ${formatPlay(video.play)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
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

private fun formatPlay(play: Long): String = when {
    play >= 100_000_000 -> "${play / 100_000_000}亿"
    play >= 10_000 -> "${play / 10_000}万"
    else -> play.toString()
}
