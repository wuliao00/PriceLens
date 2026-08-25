package com.pricelens.ui.bilibili

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pricelens.data.remote.BiliApi
import com.pricelens.ui.components.PriceCard
import com.pricelens.ui.components.ShimmerList
import com.pricelens.ui.main.MainUiState
import com.pricelens.ui.theme.Dims
import com.pricelens.ui.theme.SemanticColors
import com.pricelens.util.ContentRisk

/**
 * §6.1 种草 — B站评测聚合。
 * 诚实豆沙包思路轻量版：疑似商单/含夸大宣传话术的视频打标并沉底（只标记不删除）。
 * §2.5 LazyColumn 稳定 key（bvid）；封面 16:9 RGB_565 降采样。
 */
@Composable
fun BilibiliScreen(state: MainUiState) {
    if (state.videos.isEmpty()) {
        if (state.loading) ShimmerList() else EmptyHint("搜索后聚合 B 站真实评测")
        return
    }
    val context = LocalContext.current
    // 稳定排序：被标记（疑似商单/夸大宣传）的视频沉底，其余保持原相关度顺序
    val sorted = state.videos.sortedBy { it.risk.flagged }
    val flaggedCount = state.videos.count { it.risk.flagged }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Dims.SpacingXL)
    ) {
        if (flaggedCount > 0) {
            item(key = "risk_hint") {
                Text(
                    "$flaggedCount 条视频被标记为疑似商单/含夸大宣传话术，已沉底展示（规则识别仅供参考）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = Dims.SpacingM)
                )
            }
        }
        items(sorted, key = { it.bvid }) { video ->
            VideoCard(video) {
                // 唤起B站 App，未安装回退浏览器
                com.pricelens.util.UrlOpener.open(
                    context, "https://www.bilibili.com/video/${video.bvid}"
                )
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
        com.pricelens.ui.components.AppImage(
            url = video.pic,
            contentDescription = video.title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
            corner = MaterialTheme.shapes.medium.topStart
                .let { 8.dp }  // 统一 8dp 圆角，与占位图协调
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

/** §6.1 关键词高亮：负面红色 / 正面绿色 AssistChip */
@Composable
private fun KeywordChip(title: String) {
    val negative = NEGATIVE_WORDS.firstOrNull { title.contains(it) }
    val positive = POSITIVE_WORDS.firstOrNull { title.contains(it) }
    val (word, color) = when {
        negative != null -> negative to SemanticColors.Suspicious
        positive != null -> positive to SemanticColors.LowPrice
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

/** 内容风险标签：疑似商单（红）/ 夸大宣传（橙黄），命中词随标展示 */
@Composable
private fun RiskChip(risk: ContentRisk) {
    val (word, color) = when {
        risk.sponsored -> "疑似商单" to SemanticColors.Suspicious
        risk.hype -> "夸大宣传" to MaterialTheme.colorScheme.tertiary
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

@Composable
internal fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(Dims.SpacingXXL)
    )
}
