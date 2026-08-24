package com.pricelens.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pricelens.BuildConfig
import com.pricelens.R
import com.pricelens.ui.components.AppImage
import com.pricelens.ui.components.BadgeTone
import com.pricelens.ui.components.PriceBadge
import com.pricelens.ui.components.PriceCard
import com.pricelens.ui.main.MainViewModel
import com.pricelens.ui.theme.Dims
import com.pricelens.util.PriceFormatter
import com.pricelens.util.UrlOpener

/**
 * 个人页（“我的”）：资料头 + 统计 + 搜索历史 + 我的收藏 + 盯价管理 + 设置入口。
 */
@Composable
fun ProfileScreen(
    viewModel: MainViewModel,
    onOpenSettings: () -> Unit,
    onOpenScripts: () -> Unit = {}
) {
    LaunchedEffect(Unit) { viewModel.refreshCacheStats() }

    val pinned by viewModel.pinnedProducts.collectAsStateWithLifecycle()
    val targets by viewModel.watchTargets.collectAsStateWithLifecycle()
    val history by viewModel.searchHistory.collectAsStateWithLifecycle()
    val cacheStats by viewModel.cacheStats.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Dims.SpacingXL)
    ) {
        item(key = "header") { ProfileHeader() }
        item(key = "stats") {
            StatsRow(
                pinnedCount = pinned.size,
                targetCount = targets.size,
                cacheStats = cacheStats
            )
        }
        if (history.isNotEmpty()) {
            item(key = "history_title") { SectionTitle("搜索历史") }
            item(key = "history") {
                HistoryChips(history = history) { viewModel.research(it) }
            }
        }
        item(key = "pinned_title") { SectionTitle("我的收藏（${pinned.size}）") }
        if (pinned.isEmpty()) {
            item(key = "pinned_empty") {
                EmptyText("暂无收藏。概览页长按商品卡可加入收藏，收藏的缓存永不淘汰。")
            }
        } else {
            items(pinned, key = { it.id }) { product ->
                PinnedRow(
                    title = product.title,
                    price = product.currentPrice,
                    image = product.imageUrl,
                    url = if (product.platform == "jd")
                        "https://item.jd.com/${product.id.removePrefix("jd:")}.html" else ""
                ) {
                    viewModel.research(product.title.take(30))
                }
            }
        }
        item(key = "targets_title") { SectionTitle("盯价目标（${targets.size}）") }
        if (targets.isEmpty()) {
            item(key = "targets_empty") {
                EmptyText("暂无盯价目标。设置目标价后，后台每 30 分钟检查并推送降价提醒。")
            }
        } else {
            items(targets, key = { it.productId }) { target ->
                TargetRow(
                    title = target.title,
                    targetPrice = target.targetPrice,
                    onDelete = { viewModel.removeTarget(target.productId) }
                )
            }
        }
        item(key = "scripts_entry") {
            Spacer(Modifier.height(Dims.SpacingS))
            PriceCard(modifier = Modifier.fillMaxWidth(), onClick = onOpenScripts) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Terminal,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.size(Dims.SpacingM))
                    Column(Modifier.weight(1f)) {
                        Text("自定义脚本（Shizuku）", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "ADB 权限执行，预置+自建",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        item(key = "settings_entry") {
            Spacer(Modifier.height(Dims.SpacingL))
            PriceCard(modifier = Modifier.fillMaxWidth(), onClick = onOpenSettings) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.size(Dims.SpacingM))
                    Text("设置", style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f))
                    Text(
                        "权限 · 外观 · 缓存 · 关于",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item(key = "footer") {
            Text(
                "作者：莫 · PriceLens v${BuildConfig.VERSION_NAME} 永久免费\n任何收费行为都是骗子",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = Dims.SpacingXL),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
private fun ProfileHeader() {
    PriceCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_launcher),
                contentDescription = "PriceLens",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(52.dp)
                    .clip(MaterialTheme.shapes.medium)
            )
            Spacer(Modifier.size(Dims.SpacingL))
            Column {
                Text("PriceLens", style = MaterialTheme.typography.titleLarge)
                Text(
                    "全网比价决策工具 · v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Dims.SpacingS))
                PriceBadge("永久免费 · 付费即骗", BadgeTone.POSITIVE)
            }
        }
    }
}

@Composable
private fun StatsRow(pinnedCount: Int, targetCount: Int, cacheStats: String) {
    Spacer(Modifier.height(Dims.SpacingL))
    Row(horizontalArrangement = Arrangement.spacedBy(Dims.SpacingM)) {
        StatCard("收藏", pinnedCount.toString(), Modifier.weight(1f))
        StatCard("盯价中", targetCount.toString(), Modifier.weight(1f))
        PriceCard(modifier = Modifier.weight(1.4f)) {
            Text("缓存", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Dims.SpacingS))
            Text(
                cacheStats,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    PriceCard(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Dims.SpacingS))
        Text(value, style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = Dims.SpacingXL, bottom = Dims.SpacingS)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryChips(history: List<String>, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dims.SpacingS)) {
        history.take(10).forEach { kw ->
            AssistChip(onClick = { onPick(kw) }, label = { Text(kw, maxLines = 1) })
        }
    }
}

@Composable
private fun PinnedRow(title: String, price: Double, image: String, url: String, onClick: () -> Unit) {
    val context = LocalContext.current
    PriceCard(modifier = Modifier.fillMaxWidth(), onClick = {
        onClick()
        if (url.startsWith("http")) UrlOpener.open(context, url)
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppImage(url = image, contentDescription = title, modifier = Modifier.size(44.dp))
            Spacer(Modifier.size(Dims.SpacingM))
            Column(Modifier.weight(1f)) {
                Text(
                    title, style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    PriceFormatter.format(price),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
    Spacer(Modifier.height(Dims.SpacingS))
}

@Composable
private fun TargetRow(title: String, targetPrice: Double, onDelete: () -> Unit) {
    PriceCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    title, style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    "目标 ${PriceFormatter.format(targetPrice)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除盯价",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
    Spacer(Modifier.height(Dims.SpacingS))
}

@Composable
private fun EmptyText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
