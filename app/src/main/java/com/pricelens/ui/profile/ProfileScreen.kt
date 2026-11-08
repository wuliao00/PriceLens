package com.pricelens.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PriceCheck
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import com.pricelens.ui.components.EntryTile
import com.pricelens.ui.components.PlatformBadge
import com.pricelens.ui.components.PriceBadge
import com.pricelens.ui.components.SectionHeader
import com.pricelens.ui.components.StatTile
import com.pricelens.ui.layout.CompactTileRow
import com.pricelens.ui.price.PriceWatchViewModel
import com.pricelens.ui.theme.BadgeTone
import com.pricelens.ui.theme.Dims
import com.pricelens.ui.theme.PillShape
import com.pricelens.ui.theme.PriceType
import com.pricelens.util.PriceFormatter
import com.pricelens.util.UrlOpener

/**
 * 个人页（“我的”）：资料头 + 统计 + 搜索历史 + 我的收藏 + 盯价管理 + 设置入口。
 * 阶段4：文案全走 strings.xml、区块标题统一 SectionHeader、空态走 EmptyText。
 *
 * [onSearchKeyword] 由 MainActivity 提供，而不是本页自己调 `searchViewModel.research`：
 * 搜索框在顶栏、结果在概览页，**这一页搜完是看不见结果的**。真机复核（2026-10-07）点
 * 历史词/收藏行后页面纹丝不动，只有键盘收起——用户没有理由相信自己已经搜了。
 * 所以"发起搜索"这个动作必须连带把概览页带过去，这个决定留在知道 tab 在哪的一层。
 */
@Composable
fun ProfileScreen(
    onOpenSettings: () -> Unit,
    onSearchKeyword: (String) -> Unit,
    onOpenScripts: () -> Unit = {},
    onOpenAutoTrack: () -> Unit = {},
    onOpenPriceProtection: () -> Unit = {}
) {
    val profileViewModel: ProfileViewModel = hiltViewModel()
    val priceWatchViewModel: PriceWatchViewModel = hiltViewModel()

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
                cache = cacheStats
            )
        }
        if (history.isNotEmpty()) {
            item(key = "history_title") {
                SectionHeader(stringResource(R.string.profile_section_history))
            }
            item(key = "history") {
                HistoryChips(history = history) { onSearchKeyword(it) }
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
                    onSearchKeyword(product.title.take(30))
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
            // 组间留白走 Dims.GroupGap：入口区与"盯价目标"之间要能看出换了一组
            // （SectionHeader 自带的 20dp 只够撑开同组内的标题层级，撑不开组）
            Spacer(Modifier.height(Dims.GroupGap))
            Column(verticalArrangement = Arrangement.spacedBy(Dims.SpacingS)) {
                // 新增两个功能中心入口：自动化中心 / 价保助手（都是全屏二级页）
                EntryTile(
                    icon = Icons.Filled.PriceCheck,
                    title = stringResource(R.string.rd_profile_autotrack_title),
                    desc = stringResource(R.string.rd_profile_autotrack_desc),
                    onClick = onOpenAutoTrack
                )
                EntryTile(
                    icon = Icons.Filled.ConfirmationNumber,
                    title = stringResource(R.string.rd_profile_protect_title),
                    desc = stringResource(R.string.rd_profile_protect_desc),
                    onClick = onOpenPriceProtection
                )
                EntryTile(
                    icon = Icons.Filled.Terminal,
                    title = stringResource(R.string.profile_scripts_title),
                    desc = stringResource(R.string.profile_scripts_desc),
                    onClick = onOpenScripts
                )
                EntryTile(
                    icon = Icons.Filled.Settings,
                    title = stringResource(R.string.profile_settings_title),
                    desc = stringResource(R.string.profile_settings_desc),
                    onClick = onOpenSettings
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
    // 大改造：资料头从"白卡 + 蓝图标"换成整块品牌金卡片——App 图标、名称、标语、
    // 免费徽标全落在金上（onPrimary 排字），是"我的"页第一屏的品牌锚点。
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primary
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(Dims.SpacingL)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_brand_mark),
                contentDescription = stringResource(R.string.app_name),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(Dims.SpacingXXXL + Dims.SpacingXL)
            )
            Spacer(Modifier.size(Dims.SpacingL))
            Column {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Text(
                    stringResource(R.string.profile_tagline, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                )
                Spacer(Modifier.height(Dims.SpacingS))
                Surface(
                    shape = PillShape,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.16f)
                ) {
                    Text(
                        stringResource(R.string.profile_free_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = Dims.SpacingS, vertical = Dims.SpacingXS)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatsRow(pinnedCount: Int, targetCount: Int, cache: CacheStats) {
    Spacer(Modifier.height(Dims.SpacingS))
    // 四格统计用 FlowRow 而不是固定高度的单行 tile（真机 2026-10-07 截图复核改的）：
    // 改前是「收藏 / 盯价中 / 缓存」三格挤在一行 60dp 里，而第三格的值本身又是一句
    // 「内存 0 KB · 图片 3 MB」⇒ 标签套标签 + 单行放不下，屏上读到的是「图片 …」。
    // 现在四格各读各的，放不下就**换行**——系统字体调大时也不会再吃掉最后一个字。
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        FlowRow(
            modifier = Modifier.padding(horizontal = Dims.SpacingL, vertical = Dims.SpacingM),
            horizontalArrangement = Arrangement.spacedBy(Dims.SpacingXL),
            verticalArrangement = Arrangement.spacedBy(Dims.SpacingS)
        ) {
            StatTile(label = stringResource(R.string.profile_stat_favorites), value = pinnedCount.toString())
            StatTile(label = stringResource(R.string.profile_stat_watching), value = targetCount.toString())
            StatTile(label = stringResource(R.string.profile_stat_cache), value = cache.memoryText)
            StatTile(label = stringResource(R.string.profile_stat_images), value = cache.imageText)
        }
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
 * 盯价目标行（2026-11 真机首轮重排）：
 *  - 标题优先占宽、最多两行省略；行尾只放删除图标；
 *  - 第二行给平台徽标 + 暂停状态，右侧是目标价（不再和标题/按钮抢同一行）；
 *  - 暂停中的行在第三行给「恢复」明路按钮；
 *  - 点行 = 改目标价；右滑暂停/恢复、左滑删除的手势保留（[TargetSwipeRow]）。
 *
 * 旧版把标题、徽标、目标价、恢复、删除全塞进 60dp 单行，真机深色截图里标题被压成「…」、
 * 平台徽标消失——「我的」页与盯价页目标卡现在共用同一套「标题优先 + 徽标单独一行」的排法。
 */
@Composable
private fun TargetRow(target: PriceTargetEntity, onClick: () -> Unit, onResume: () -> Unit, onDelete: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(Modifier.padding(Dims.SpacingM)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    target.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = Dims.SpacingXS)
                )
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.cd_swipe_delete_target),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlatformBadge(target.platform)
                if (!target.active) {
                    Spacer(Modifier.size(Dims.SpacingXS))
                    PriceBadge(stringResource(R.string.watch_target_paused_badge), BadgeTone.NEUTRAL)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.profile_target_price, PriceFormatter.format(target.targetPrice)),
                    style = PriceType.PriceRowCompact,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
            }
            if (!target.active) {
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onResume) {
                        Text(stringResource(R.string.watch_target_resume))
                    }
                }
            }
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
