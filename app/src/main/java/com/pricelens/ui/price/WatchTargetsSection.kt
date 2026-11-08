package com.pricelens.ui.price

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pricelens.R
import com.pricelens.data.local.entity.PriceTargetEntity
import com.pricelens.ui.components.EmptyState
import com.pricelens.ui.components.PlatformBadge
import com.pricelens.ui.components.PriceBadge
import com.pricelens.ui.components.SectionHeader
import com.pricelens.ui.theme.BadgeTone
import com.pricelens.ui.theme.Dims
import com.pricelens.ui.theme.LocalSemanticColors
import com.pricelens.ui.theme.PillShape
import com.pricelens.ui.theme.PriceType
import com.pricelens.util.PriceFormatter
import com.pricelens.util.TimeAgo

/**
 * 盯价目标列表（「自动追价中心」第三段）。
 *
 * 目标行要说清四件事：**盯的是哪件商品（平台 + 标题）→ 目标价 → 离目标还有多远/最近检查
 * → 能对它做什么（改价/暂停/恢复/删除）**。现价与最近检查时间来自自动追价引擎的轮次结果；
 * 引擎没给（[WatchTargetRowView.currentPrice] 为 null）时如实写「现价未知」，
 * 不拿目标价冒充现价、也不摆一条 0% 的假进度条。
 *
 * 版式（2026-11 真机首轮修复）：标题优先占宽、最多两行省略；平台徽标与暂停状态单独一行；
 * 删除收进行尾图标。之前标题只给 weight 的余量、又和徽标/目标价/按钮挤在同一行，
 * 真机上标题被压成「…」——「我的」页同一个毛病，两处现在共用这套排法。
 */
data class WatchTargetRowView(
    val productId: String,
    val title: String,
    val platform: String,
    val targetPrice: Double,
    val active: Boolean,
    val currentPrice: Double? = null,
    val lastCheckedAtMs: Long? = null,
    val sourceLabel: String? = null,
    val basisLabel: String? = null,
    val url: String? = null
)

/** 实体 → 行模型的最小映射；现价/检查时间等引擎字段落地后在这里补 */
fun watchTargetRowOf(target: PriceTargetEntity): WatchTargetRowView = WatchTargetRowView(
    productId = target.productId,
    title = target.title,
    platform = target.platform,
    targetPrice = target.targetPrice,
    active = target.active
)

/**
 * 盯价目标段。
 *
 * @param onEmptyAction 空态 CTA：能盯当前商品时是「设置目标价」，否则是「去搜索商品」
 */
@Composable
fun WatchTargetsSection(
    targets: List<WatchTargetRowView>,
    emptyActionLabel: String,
    onEditPrice: (WatchTargetRowView) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onDelete: (String) -> Unit,
    onOpenProduct: (String) -> Unit,
    onEmptyAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth()) {
        SectionHeader(stringResource(R.string.rd_watch_targets_title, targets.size))
        if (targets.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.QueryStats,
                title = stringResource(R.string.rd_watch_targets_empty_title),
                desc = stringResource(R.string.rd_watch_targets_empty_desc),
                actionLabel = emptyActionLabel,
                onAction = onEmptyAction
            )
        } else {
            targets.forEach { target ->
                WatchTargetCard(
                    target = target,
                    onEditPrice = { onEditPrice(target) },
                    onPause = { onPause(target.productId) },
                    onResume = { onResume(target.productId) },
                    onDelete = { onDelete(target.productId) },
                    onOpenProduct = { onOpenProduct(target.title) }
                )
                Spacer(Modifier.height(Dims.SpacingS))
            }
        }
    }
}

/**
 * 单个目标卡：标题行（标题 + 行尾删除）→ 平台/暂停徽标 + 目标价 → 现价/进度/距离 →
 * 最近检查与来源口径 → 操作行（改价 / 暂停恢复 / 看详情）。
 */
@Composable
private fun WatchTargetCard(
    target: WatchTargetRowView,
    onEditPrice: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
    onOpenProduct: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(Modifier.padding(Dims.SpacingM)) {
            // 行 1：标题优先占宽（两行上限），删除收进行尾
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
                        contentDescription = stringResource(R.string.rd_watch_target_delete),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            // 行 2：平台徽标 + 暂停状态 | 目标价
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlatformBadge(target.platform)
                if (!target.active) {
                    Spacer(Modifier.width(Dims.SpacingXS))
                    PriceBadge(stringResource(R.string.watch_target_paused_badge), BadgeTone.NEUTRAL)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.profile_target_price, PriceFormatter.format(target.targetPrice)),
                    style = PriceType.PriceInline,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
            }
            // 行 3：现价 / 进度 / 距离（进度只在确实知道现价时画）
            val now = target.currentPrice
            Spacer(Modifier.height(Dims.SpacingXS))
            if (now != null && now > 0 && target.targetPrice > 0) {
                val progress = (target.targetPrice / now).coerceIn(0.0, 1.0).toFloat()
                val progressCd = stringResource(R.string.rd_watch_target_progress, (progress * 100).toInt())
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(PillShape)
                        .semantics { contentDescription = progressCd }
                )
                Spacer(Modifier.height(Dims.SpacingXS))
                val diff = now - target.targetPrice
                Text(
                    stringResource(R.string.rd_watch_target_current, PriceFormatter.format(now)) + " · " +
                        if (diff <= 0) {
                            stringResource(R.string.rd_watch_target_reached)
                        } else {
                            stringResource(
                                R.string.rd_watch_target_distance,
                                PriceFormatter.format(diff),
                                ((diff / now) * 100).toInt().coerceIn(0, 100)
                            )
                        },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (diff <= 0) {
                        LocalSemanticColors.current.lowPrice
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Text(
                    stringResource(R.string.rd_watch_target_current_unknown),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            // 行 4：最近检查 / 来源与口径（都没有时只说"还没检查过"，不编时间）
            val checkText = target.lastCheckedAtMs?.let {
                stringResource(R.string.rd_watch_target_last_check, TimeAgo.format(it))
            } ?: stringResource(R.string.rd_watch_target_never_checked)
            val sourceText = target.sourceLabel?.let { source ->
                stringResource(
                    R.string.rd_watch_target_source,
                    source,
                    target.basisLabel ?: stringResource(R.string.rd_basis_unknown)
                )
            }
            Text(
                if (sourceText != null) "$checkText · $sourceText" else checkText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = Dims.SpacingXS)
            )
            // 行 5：操作（明路，不做隐藏手势）
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onEditPrice, modifier = Modifier.heightIn(min = Dims.TouchMin)) {
                    Text(stringResource(R.string.rd_watch_target_edit))
                }
                TextButton(
                    onClick = { if (target.active) onPause() else onResume() },
                    modifier = Modifier.heightIn(min = Dims.TouchMin)
                ) {
                    Text(
                        stringResource(
                            if (target.active) R.string.rd_watch_target_pause else R.string.rd_watch_target_resume
                        )
                    )
                }
                TextButton(onClick = onOpenProduct, modifier = Modifier.heightIn(min = Dims.TouchMin)) {
                    Text(stringResource(R.string.rd_watch_target_detail))
                }
            }
        }
    }
}
