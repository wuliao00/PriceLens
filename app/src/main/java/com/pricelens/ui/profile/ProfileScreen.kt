package com.pricelens.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pricelens.BuildConfig
import com.pricelens.R
import com.pricelens.ui.components.AppImage
import com.pricelens.ui.components.EmptyText
import com.pricelens.ui.components.PriceBadge
import com.pricelens.ui.components.PriceCard
import com.pricelens.ui.components.SectionHeader
import com.pricelens.ui.overview.SearchViewModel
import com.pricelens.ui.price.PriceWatchViewModel
import com.pricelens.ui.theme.BadgeTone
import com.pricelens.ui.theme.Dims
import com.pricelens.ui.theme.PriceType
import com.pricelens.util.PriceFormatter
import com.pricelens.util.UrlOpener

/**
 * 个人页（“我的”）：资料头 + 统计 + 搜索历史 + 我的收藏 + 盯价管理 + 设置入口。
 * 阶段4：文案全走 strings.xml、区块标题统一 SectionHeader、空态走 EmptyText。
 */
@Composable
fun ProfileScreen(onOpenSettings: () -> Unit, onOpenScripts: () -> Unit = {}) {
    val profileViewModel: ProfileViewModel = hiltViewModel()
    val priceWatchViewModel: PriceWatchViewModel = hiltViewModel()
    val searchViewModel: SearchViewModel = hiltViewModel()

    LaunchedEffect(Unit) { profileViewModel.refreshCacheStats() }

    val pinned by profileViewModel.pinnedProducts.collectAsStateWithLifecycle()
    val targets by priceWatchViewModel.watchTargets.collectAsStateWithLifecycle()
    val history by profileViewModel.searchHistory.collectAsStateWithLifecycle()
    val cacheStats by profileViewModel.cacheStats.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Dims.SpacingXL)
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
            item(key = "history_title") {
                SectionHeader(stringResource(R.string.profile_section_history))
            }
            item(key = "history") {
                HistoryChips(history = history) { searchViewModel.research(it) }
            }
        }
        item(key = "pinned_title") {
            SectionHeader(stringResource(R.string.profile_section_pinned, pinned.size))
        }
        if (pinned.isEmpty()) {
            item(key = "pinned_empty") {
                EmptyText(stringResource(R.string.profile_pinned_empty))
            }
        } else {
            items(pinned, key = { it.id }) { product ->
                PinnedRow(
                    title = product.title,
                    price = product.currentPrice,
                    image = product.imageUrl,
                    url = if (product.platform == "jd") {
                        "https://item.jd.com/${product.id.removePrefix("jd:")}.html"
                    } else {
                        ""
                    }
                ) {
                    searchViewModel.research(product.title.take(30))
                }
            }
        }
        item(key = "targets_title") {
            SectionHeader(stringResource(R.string.profile_section_targets, targets.size))
        }
        if (targets.isEmpty()) {
            item(key = "targets_empty") {
                EmptyText(stringResource(R.string.profile_targets_empty))
            }
        } else {
            items(targets, key = { it.productId }) { target ->
                TargetRow(
                    title = target.title,
                    targetPrice = target.targetPrice,
                    onDelete = { priceWatchViewModel.removeTarget(target.productId) }
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
                        Text(
                            stringResource(R.string.profile_scripts_title),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            stringResource(R.string.profile_scripts_desc),
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
                    Text(
                        stringResource(R.string.profile_settings_title),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        stringResource(R.string.profile_settings_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item(key = "footer") {
            Text(
                stringResource(R.string.profile_footer, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Dims.SpacingXL),
                textAlign = TextAlign.Center
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
                contentDescription = stringResource(R.string.app_name),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(Dims.SpacingXXXL + Dims.SpacingXL)
                    .clip(MaterialTheme.shapes.medium)
            )
            Spacer(Modifier.size(Dims.SpacingL))
            Column {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.profile_tagline, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Dims.SpacingS))
                PriceBadge(stringResource(R.string.profile_free_badge), BadgeTone.POSITIVE)
            }
        }
    }
}

@Composable
private fun StatsRow(pinnedCount: Int, targetCount: Int, cacheStats: String) {
    Spacer(Modifier.height(Dims.SpacingL))
    Row(horizontalArrangement = Arrangement.spacedBy(Dims.SpacingM)) {
        StatCard(stringResource(R.string.profile_stat_favorites), pinnedCount.toString(), Modifier.weight(1f))
        StatCard(stringResource(R.string.profile_stat_watching), targetCount.toString(), Modifier.weight(1f))
        PriceCard(modifier = Modifier.weight(1.4f)) {
            Text(
                stringResource(R.string.profile_stat_cache),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Dims.SpacingS))
        Text(
            value,
            style = PriceType.PriceLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
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
            AppImage(
                url = image,
                contentDescription = title,
                modifier = Modifier.size(Dims.SpacingXXXL + Dims.SpacingM)
            )
            Spacer(Modifier.size(Dims.SpacingM))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    PriceFormatter.format(price),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFeatureSettings = "tnum"
                    ),
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
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    stringResource(
                        R.string.profile_target_price,
                        PriceFormatter.format(targetPrice)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.profile_cd_delete_target),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
    Spacer(Modifier.height(Dims.SpacingS))
}
