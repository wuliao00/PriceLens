package com.pricelens.ui.accuracy

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pricelens.R
import com.pricelens.ui.components.HairLine
import com.pricelens.ui.components.PriceBadge
import com.pricelens.ui.components.SourceHealthBoard
import com.pricelens.ui.components.SourceHealthCell
import com.pricelens.ui.theme.BadgeTone
import com.pricelens.ui.theme.Dims
import com.pricelens.ui.theme.LocalSemanticColors
import com.pricelens.ui.theme.MotionDurations
import com.pricelens.ui.theme.PillShape
import com.pricelens.ui.theme.PriceLensEasing
import com.pricelens.util.PriceFormatter
import com.pricelens.util.TimeAgo

/**
 * 数据源准确性引擎（com.pricelens.accuracy）的**展示层模型**（2026-11 UI 大改）。
 *
 * 为什么 UI 侧再定义一套而不是直接吃引擎类型：
 *  - 边界清楚：引擎可以独立重构字段，UI 只认这里的形状，映射集中在 `AccuracyUiMapper.kt`；
 *  - 可先用后接：引擎文件比 UI 晚落地时，页面先按本模型写（传 null = 不显示判定），
 *    接上引擎只补映射函数，不动任何排版代码；
 *  - 纯 UI 字段（标签文案、相关度百分比）在这里一次定型，避免同一句话在两个屏幕各写一份。
 *
 * 契约对齐（以引擎源码为准，若字段不一致以源码为准并回报 Lead）：
 *  - ConfidenceLevel { HIGH, MEDIUM, LOW, UNKNOWN }
 *  - PriceBasis { PAGE, COUPON_AFTER, NET, REFERENCE, UNKNOWN }
 *  - SourceState { OK, DEGRADED, BLOCKED, STALE, UNKNOWN }
 *  - AccuracyIssueKind { WRONG_PRICE, WRONG_PRODUCT, STALE, WRONG_BASIS }
 */
data class AccuracyView(
    val level: ConfidenceLevelView,
    val score: Int,
    val headline: String,
    val reasons: List<String>,
    val basisLabel: String,
    val evidence: List<PriceEvidenceView>,
    val conflicts: List<AccuracyConflictView>,
    val sources: List<SourceHealthCell>,
    val generatedAtMs: Long
)

/** 置信档（UI 枚举，与引擎同名同序） */
enum class ConfidenceLevelView { HIGH, MEDIUM, LOW, UNKNOWN }

/** 一条价格证据（引擎 [com.pricelens.accuracy.PriceEvidence] 的展示投影） */
data class PriceEvidenceView(
    val sourceId: String,
    val label: String,
    val price: Double?,
    val basisLabel: String,
    val ageMs: Long?,
    val stale: Boolean,
    val relevance: Int
)

/** 一处来源冲突（如两个源对同一商品报价差很多） */
data class AccuracyConflictView(val label: String, val detail: String)

/**
 * 「这条不对」的四种反馈口径（UI 枚举；映射到引擎 AccuracyIssueKind 由 mapper 做）。
 * 标签放资源里，枚举只带 resId——理由与 BadgeTone 一致：颜色/文案都归主题层。
 */
enum class AccuracyIssueView(@StringRes val labelRes: Int) {
    WRONG_PRICE(R.string.rd_accuracy_issue_wrong_price),
    WRONG_PRODUCT(R.string.rd_accuracy_issue_wrong_product),
    STALE(R.string.rd_accuracy_issue_stale),
    WRONG_BASIS(R.string.rd_accuracy_issue_wrong_basis)
}

/** 置信档 → 文案资源 */
fun confidenceLevelLabelRes(level: ConfidenceLevelView): Int = when (level) {
    ConfidenceLevelView.HIGH -> R.string.rd_accuracy_high
    ConfidenceLevelView.MEDIUM -> R.string.rd_accuracy_medium
    ConfidenceLevelView.LOW -> R.string.rd_accuracy_low
    ConfidenceLevelView.UNKNOWN -> R.string.rd_accuracy_unknown
}

/** 置信档 → 语义色：高=绿、中=品牌金、低=红、未知=暖灰（与全库语义六色一致） */
@Composable
fun confidenceLevelColor(level: ConfidenceLevelView): Color = when (level) {
    ConfidenceLevelView.HIGH -> LocalSemanticColors.current.lowPrice
    ConfidenceLevelView.MEDIUM -> MaterialTheme.colorScheme.primary
    ConfidenceLevelView.LOW -> LocalSemanticColors.current.suspicious
    ConfidenceLevelView.UNKNOWN -> LocalSemanticColors.current.neutral
}

/**
 * 准确性板块（概览结果卡 / 商品详情价格段共用同一个组件）。
 *
 * 交互：整行点按展开证据面板（默认收起，首屏只占一行）；展开区里给「证据 / 冲突 / 反馈」，
 * 反馈入口做成低调的文字链——它是纠错通道，不该和「加入价保」这类主动作抢注意力。
 * 展开/收起用 [AnimatedVisibility] 走布局通道：折叠面板收起后不能留下空白，
 * 与概览页折叠引导卡是同一个先例（时长压在 [MotionDurations.Standard] 内）。
 */
@Composable
fun AccuracyBlock(view: AccuracyView, onReportIssue: (AccuracyIssueView) -> Unit, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var showReportDialog by rememberSaveable { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(Modifier.padding(horizontal = Dims.SpacingM, vertical = Dims.SpacingS)) {
            AccuracySummaryRow(view, expanded) { expanded = !expanded }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(MotionDurations.Standard, easing = PriceLensEasing)) +
                    expandVertically(tween(MotionDurations.Standard, easing = PriceLensEasing)),
                exit = fadeOut(tween(MotionDurations.Fast, easing = PriceLensEasing)) +
                    shrinkVertically(tween(MotionDurations.Fast, easing = PriceLensEasing))
            ) {
                AccuracyEvidencePanel(
                    view = view,
                    onReportClick = { showReportDialog = true }
                )
            }
        }
    }
    if (showReportDialog) {
        ReportIssueDialog(
            onDismiss = { showReportDialog = false },
            onConfirm = { issue ->
                showReportDialog = false
                onReportIssue(issue)
            }
        )
    }
}

/**
 * 判定摘要行：置信胶囊（色点 + 高/中/低）+ 一行 headline + 展开箭头。
 * 整行是触控目标（[Dims.TouchMin]），读屏会读出「置信 + headline + 展开/收起」。
 */
@Composable
private fun AccuracySummaryRow(view: AccuracyView, expanded: Boolean, onToggle: () -> Unit) {
    val levelLabel = stringResource(confidenceLevelLabelRes(view.level))
    val actionLabel = stringResource(
        if (expanded) R.string.rd_accuracy_collapse else R.string.rd_accuracy_expand
    )
    val description = "$levelLabel，${view.headline}，$actionLabel"
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(MotionDurations.Fast, easing = PriceLensEasing),
        label = "accuracyChevron"
    )
    val levelColor = confidenceLevelColor(view.level)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dims.TouchMin)
            .clickable(onClick = onToggle)
            .semantics(mergeDescendants = true) { contentDescription = description }
    ) {
        Surface(shape = PillShape, color = levelColor.copy(alpha = 0.14f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = Dims.SpacingS, vertical = Dims.SpacingXS)
            ) {
                Canvas(Modifier.size(6.dp)) { drawCircle(color = levelColor) }
                Spacer(Modifier.width(Dims.SpacingXS))
                Text(levelLabel, style = MaterialTheme.typography.labelSmall, color = levelColor)
            }
        }
        Spacer(Modifier.width(Dims.SpacingS))
        Text(
            view.headline,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Icon(
            Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(Dims.IconInline)
                .graphicsLayer { rotationZ = chevron }
        )
    }
}

/**
 * 证据面板（展开态）：判定理由 → 口径/分数/生成时间 → 价格证据 → 冲突 → 反馈入口。
 * 顺序是按「用户先问什么」排的：为什么这么判 → 证据从哪来 → 有没有互相打架 → 我不同意。
 */
@Composable
private fun AccuracyEvidencePanel(view: AccuracyView, onReportClick: () -> Unit) {
    val dotColor = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(Dims.SpacingXS))
        HairLine()
        Spacer(Modifier.height(Dims.SpacingM))
        if (view.reasons.isNotEmpty()) {
            PanelTitle(stringResource(R.string.rd_accuracy_reasons_title))
            view.reasons.forEach { reason ->
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(bottom = Dims.SpacingXS)) {
                    Canvas(
                        Modifier
                            .padding(top = Dims.SpacingS)
                            .size(4.dp)
                    ) {
                        drawCircle(color = dotColor)
                    }
                    Spacer(Modifier.width(Dims.SpacingS))
                    Text(
                        reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(Dims.SpacingXS))
        }
        // 口径 / 分数 / 生成时间：一行读完，长就换行
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dims.SpacingXS),
            modifier = Modifier.fillMaxWidth()
        ) {
            PriceBadge(view.basisLabel, BadgeTone.INFO)
            if (view.score > 0) {
                PriceBadge(
                    stringResource(R.string.rd_accuracy_score, view.score),
                    BadgeTone.NEUTRAL
                )
            }
            Text(
                stringResource(
                    R.string.rd_accuracy_generated,
                    TimeAgo.format(view.generatedAtMs)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        if (view.evidence.isNotEmpty()) {
            Spacer(Modifier.height(Dims.SpacingM))
            PanelTitle(stringResource(R.string.rd_accuracy_evidence_title))
            view.evidence.forEach { item -> EvidenceRow(item) }
        }
        Spacer(Modifier.height(Dims.SpacingM))
        PanelTitle(stringResource(R.string.rd_accuracy_conflicts_title))
        if (view.conflicts.isEmpty()) {
            Text(
                stringResource(R.string.rd_accuracy_no_conflict),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            view.conflicts.forEach { conflict -> ConflictRow(conflict) }
        }
        // 来源健康：与概览顶部同一块板（compact：不重复标题），保证两处口径一致
        if (view.sources.isNotEmpty()) {
            Spacer(Modifier.height(Dims.SpacingM))
            PanelTitle(stringResource(R.string.rd_source_board_title))
            SourceHealthBoard(cells = view.sources, compact = true)
        }
        Spacer(Modifier.height(Dims.SpacingXS))
        TextButton(
            onClick = onReportClick,
            modifier = Modifier.heightIn(min = Dims.TouchMin)
        ) {
            Text(stringResource(R.string.rd_accuracy_report_cta))
        }
    }
}

@Composable
private fun PanelTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = Dims.SpacingXS)
    )
}

/** 一条价格证据：源名 + 价格 + 口径 + 数据年龄；陈旧/低相关给明确标记 */
@Composable
private fun EvidenceRow(item: PriceEvidenceView) {
    // ageMs 为 null = 时刻不可知，如实写「时刻未知」；绝不拿"刚刚"糊弄
    val ageText = item.ageMs?.let { TimeAgo.format(System.currentTimeMillis() - it) }
        ?: stringResource(R.string.rd_accuracy_age_unknown)
    val priceText = item.price?.let { PriceFormatter.format(it) } ?: "—"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dims.SpacingXS)
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.stale) {
                    Spacer(Modifier.width(Dims.SpacingXS))
                    PriceBadge(stringResource(R.string.rd_accuracy_stale_tag), BadgeTone.NEGATIVE)
                }
            }
            Text(
                stringResource(R.string.rd_accuracy_evidence_price, priceText, item.basisLabel) +
                    " · " + ageText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (item.relevance in 1..99) {
            Text(
                stringResource(R.string.rd_accuracy_relevance, item.relevance),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

/** 一处冲突：图标 + 标题 + 说明（红色语义，但正文仍是 onSurfaceVariant，避免整段红得读不动） */
@Composable
private fun ConflictRow(conflict: AccuracyConflictView) {
    val error = LocalSemanticColors.current.suspicious
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dims.SpacingXS)
    ) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = error,
            modifier = Modifier.size(Dims.IconInline)
        )
        Spacer(Modifier.width(Dims.SpacingS))
        Column(Modifier.weight(1f)) {
            Text(conflict.label, style = MaterialTheme.typography.bodySmall, color = error)
            Text(
                conflict.detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 「这条不对」弹窗：四选一 + 说明，确认才提交。
 * 不用下拉菜单：四个选项是互斥口径，摊开比藏起来少一步，也更好点（每行 ≥48dp）。
 */
@Composable
private fun ReportIssueDialog(onDismiss: () -> Unit, onConfirm: (AccuracyIssueView) -> Unit) {
    var selected by rememberSaveable { mutableStateOf<AccuracyIssueView?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rd_accuracy_report_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.rd_accuracy_report_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Dims.SpacingS))
                AccuracyIssueView.entries.forEach { issue ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Dims.TouchMin)
                            .clickable { selected = issue }
                    ) {
                        RadioButton(selected = selected == issue, onClick = { selected = issue })
                        Spacer(Modifier.width(Dims.SpacingXS))
                        Text(stringResource(issue.labelRes), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selected?.let(onConfirm) },
                enabled = selected != null
            ) { Text(stringResource(R.string.rd_accuracy_report_submit)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        }
    )
}
