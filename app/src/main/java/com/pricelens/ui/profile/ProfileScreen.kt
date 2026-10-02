package com.pricelens.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pricelens.BuildConfig
import com.pricelens.R
import com.pricelens.data.local.entity.PriceTargetEntity
import com.pricelens.ui.components.AppImage
import com.pricelens.ui.components.EmptyText
import com.pricelens.ui.components.PriceBadge
import com.pricelens.ui.components.PriceCard
import com.pricelens.ui.components.SectionHeader
import com.pricelens.ui.layout.CompactTileRow
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
    // 正在修改目标价的目标（对话框状态）
    var editingTarget by remember { mutableStateOf<PriceTargetEntity?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Dims.SpacingXL)
    ) {
        item(key = "header") { ProfileHeader() }
        item(key = "stats") {
            StatsRow(
                pinnedCount = pinned.size,
                // "盯价中"只算未暂停的（暂停项仍在列表里可见、可恢复）
                targetCount = targets.count { it.active },
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
            // key 必须带段前缀：收藏与盯价共用同一个 id 空间（jd:xxx），同一个商品
            // 既收藏又盯价时裸 id 会撞 key，Compose 直接抛 IllegalArgumentException（真机崩过）
            items(pinned, key = { ProfileKeys.pin(it.id) }) { product ->
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
            items(targets, key = { ProfileKeys.target(it.productId) }) { target ->
                TargetSwipeRow(
                    target = target,
                    onPause = { priceWatchViewModel.pauseTarget(target.productId) },
                    onResume = { priceWatchViewModel.resumeTarget(target.productId) },
                    onDelete = { priceWatchViewModel.deleteTarget(target.productId) },
                    onEditPrice = { editingTarget = target }
                )
            }
        }
        item(key = "entries") {
            // 组间留白走 Dims.GroupGap：入口区与"盯价目标"之间要能看出换了一组（SectionHeader 自己带的
            // 20dp 只够撑开同组内的标题层级，撑不开组）
            Spacer(Modifier.height(Dims.GroupGap))
            CompactTileRow(onClick = onOpenScripts) {
                Icon(
                    Icons.Filled.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    stringResource(R.string.profile_scripts_title),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    stringResource(R.string.profile_scripts_desc),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
            Spacer(Modifier.height(Dims.SpacingXS))
            CompactTileRow(onClick = onOpenSettings) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    stringResource(R.string.profile_settings_title),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    stringResource(R.string.profile_settings_desc),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
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

    // 点行 = 改目标价（文档 UX 列表操作之一）；点按/滑动之外没有隐藏入口
    editingTarget?.let { target ->
        EditTargetPriceDialog(
            target = target,
            onDismiss = { editingTarget = null },
            onConfirm = { price ->
                priceWatchViewModel.updateTargetPrice(target.productId, price)
                editingTarget = null
            }
        )
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
    Spacer(Modifier.height(Dims.SpacingS))
    // 三张卡片 → 一行三段：数字要看的只是"多少"，不需要每张卡各占 60dp
    CompactTileRow {
        StatCell(stringResource(R.string.profile_stat_favorites), pinnedCount.toString())
        StatCell(stringResource(R.string.profile_stat_watching), targetCount.toString())
        StatCell(stringResource(R.string.profile_stat_cache), cacheStats, grow = true)
    }
}

/** 一格统计：标签（labelSmall，次要色）+ 值（等宽数字，主色）；[grow] 的那格吸收剩余宽度并省略号 */
@Composable
private fun RowScope.StatCell(label: String, value: String, grow: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(Dims.SpacingXS))
        Text(
            value,
            style = PriceType.PriceRowCompact,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (grow) Modifier.weight(1f, fill = false) else Modifier
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

/** 收藏行：紧凑单行（图 + 标题 1 行 + 价），点按 = 用标题重新搜（并顺手打开链接） */
@Composable
private fun PinnedRow(title: String, price: Double, image: String, url: String, onClick: () -> Unit) {
    val context = LocalContext.current
    CompactTileRow(onClick = {
        onClick()
        if (url.startsWith("http")) UrlOpener.open(context, url)
    }) {
        AppImage(
            url = image,
            contentDescription = title,
            modifier = Modifier.size(Dims.ThumbRow)
        )
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            PriceFormatter.format(price),
            style = PriceType.PriceRowCompact,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1
        )
    }
    Spacer(Modifier.height(Dims.SpacingXS))
}

/**
 * 盯价目标行（文档 UX 列表操作）：
 *  - 点按 → 修改目标价；
 *  - 右滑（StartToEnd）→ 暂停 / 恢复（行留在原地，只换状态）；
 *  - 左滑（EndToStart）→ 彻底删除（列表随 Room 流消失）；
 *  - 暂停中的行显示「已暂停」徽标与「恢复」按钮（滑动手势不好发现时还有明路可走）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetSwipeRow(
    target: PriceTargetEntity,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
    onEditPrice: () -> Unit
) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    if (target.active) onPause() else onResume()
                    false // 不真的滑走：暂停/恢复只是换状态
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    onDelete()
                    true
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        }
    )
    SwipeToDismissBox(
        state = state,
        backgroundContent = { SwipeBackground(state.dismissDirection, target) },
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true
    ) {
        // RowScope 下用 Column 包一层，避免卡片与 Spacer 被并排摆放
        Column {
            TargetRow(target = target, onClick = onEditPrice, onResume = onResume, onDelete = onDelete)
        }
    }
}

@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue, target: PriceTargetEntity) {
    if (direction == SwipeToDismissBoxValue.Settled) return
    val isDelete = direction == SwipeToDismissBoxValue.EndToStart
    val container = if (isDelete) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    val onContainer = if (isDelete) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onTertiaryContainer
    }
    val icon = when {
        isDelete -> Icons.Filled.Delete
        target.active -> Icons.Filled.Pause
        else -> Icons.Filled.PlayArrow
    }
    val label = if (isDelete) {
        stringResource(R.string.cd_swipe_delete_target)
    } else if (target.active) {
        stringResource(R.string.cd_swipe_pause_target)
    } else {
        stringResource(R.string.watch_target_resume)
    }
    Row(
        Modifier
            .fillMaxSize()
            .background(container)
            .padding(horizontal = Dims.SpacingL),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (isDelete) Arrangement.End else Arrangement.Start
    ) {
        Icon(icon, contentDescription = null, tint = onContainer)
        Spacer(Modifier.size(Dims.SpacingS))
        Text(label, style = MaterialTheme.typography.labelMedium, color = onContainer)
    }
}

/**
 * 盯价目标行（紧凑单行，替换原来的两行卡片）。
 *
 * 段位：标题 · 「已暂停」徽标 · 目标价 · 恢复 · 删除。宽度账（内容宽 302.86dp）：
 *  - 未暂停：目标价 60 + 删除键 48 + 段间距 24 = 132 → 标题拿 170dp（≈12 个 14sp 汉字）；
 *  - 已暂停：再挤进徽标 60 + 恢复键 56 = 248 → 标题只剩 ~54dp（≈4 字）会被省略号。
 *    这是"一屏多放一行"的代价，刻意接受：此时"已暂停"徽标 + 两个动作就是这一行的主要信息，
 *    标题靠点按后的改价对话框（对话框里标题给两行）再读一次。真机走查若认为不可接受，
 *    退路是把恢复/删除收进长按菜单（但那是隐藏入口，与"明路可走"的既有决定冲突，不擅自做）。
 */
@Composable
private fun TargetRow(target: PriceTargetEntity, onClick: () -> Unit, onResume: () -> Unit, onDelete: () -> Unit) {
    CompactTileRow(onClick = onClick) {
        Text(
            target.title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (!target.active) {
            PriceBadge(stringResource(R.string.watch_target_paused_badge), BadgeTone.NEUTRAL)
        }
        Text(
            stringResource(R.string.profile_target_price, PriceFormatter.format(target.targetPrice)),
            style = PriceType.PriceRowCompact,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        if (!target.active) {
            TextButton(onClick = onResume) {
                Text(stringResource(R.string.watch_target_resume))
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.cd_swipe_delete_target),
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
    Spacer(Modifier.height(Dims.SpacingXS))
}

@Composable
private fun EditTargetPriceDialog(target: PriceTargetEntity, onDismiss: () -> Unit, onConfirm: (Double) -> Unit) {
    var priceText by remember(target.productId) { mutableStateOf(formatEditable(target.targetPrice)) }
    val parsed = priceText.toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.watch_target_edit_title)) },
        text = {
            Column {
                Text(
                    target.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(Dims.SpacingS))
                OutlinedTextField(
                    value = priceText,
                    onValueChange = { priceText = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = { Text(stringResource(R.string.watch_target_edit_hint)) }
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsed != null && parsed > 0,
                onClick = { parsed?.let(onConfirm) }
            ) {
                Text(stringResource(R.string.price_watch_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

/** 目标价输入框初始值：整数不带小数点，非整数保持原样 */
private fun formatEditable(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
