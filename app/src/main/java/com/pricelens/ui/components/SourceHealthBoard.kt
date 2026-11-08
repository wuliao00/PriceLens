package com.pricelens.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pricelens.R
import com.pricelens.ui.theme.Dims
import com.pricelens.ui.theme.LocalSemanticColors
import com.pricelens.ui.theme.PillShape

/**
 * 来源健康板（2026-11 UI 大改）：把「每个源这一轮的结局」从一排徽标升级成一块可扫读的板。
 *
 * 结构：标题 + 可用计数 → 一条按源分段的健康横条 → 每个源的状态色点与原因文字。
 * 为什么保留文字原因、不只用颜色：反爬 / 网络不通 / 降级冷却对用户是三种不同的处境，
 * 「不可达」三个字必须写在屏上；颜色只做第二通道（色点 + 文字双编码，色弱也能读）。
 *
 * [SourceHealthCell] 是 UI 自己的模型，不直接引用任何引擎类型：
 * 现有的 AsyncValue 诊断结果与（后续的）准确性引擎 SourceCell 都映射到它，
 * 这样两种来源共用同一套视觉，换引擎不动组件。
 */
data class SourceHealthCell(
    val sourceId: String,
    val label: String,
    val state: SourceHealthState,
    /** 不可达/降级的具体原因（如「反爬拦截」「请求超时」），正常源为 null */
    val note: String? = null
)

/** 源状态（与准确性引擎契约同集合，屏幕侧只消费这个 UI 枚举） */
enum class SourceHealthState { OK, DEGRADED, BLOCKED, STALE, UNKNOWN }

/** 状态 → 语义色（亮/暗主题自适应） */
@Composable
fun sourceHealthColor(state: SourceHealthState): Color = when (state) {
    SourceHealthState.OK -> LocalSemanticColors.current.lowPrice
    SourceHealthState.DEGRADED -> MaterialTheme.colorScheme.primary
    SourceHealthState.BLOCKED -> LocalSemanticColors.current.suspicious
    SourceHealthState.STALE -> LocalSemanticColors.current.neutral
    SourceHealthState.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
}

/** 状态 → 文案资源 */
fun sourceHealthLabelRes(state: SourceHealthState): Int = when (state) {
    SourceHealthState.OK -> R.string.rd_source_state_ok
    SourceHealthState.DEGRADED -> R.string.rd_source_state_degraded
    SourceHealthState.BLOCKED -> R.string.rd_source_state_blocked
    SourceHealthState.STALE -> R.string.rd_source_state_stale
    SourceHealthState.UNKNOWN -> R.string.rd_source_state_unknown
}

/**
 * 来源健康板卡片。
 *
 * [compact] = true 时不画标题行（嵌进别的卡片里当一段用）；
 * 默认在概览页顶部独立成卡，标题右侧直接给「N/M 源可用」。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SourceHealthBoard(cells: List<SourceHealthCell>, modifier: Modifier = Modifier, compact: Boolean = false) {
    if (cells.isEmpty()) return
    val okCount = cells.count { it.state == SourceHealthState.OK }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(Modifier.padding(Dims.SpacingM)) {
            if (!compact) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.rd_source_board_title),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        stringResource(R.string.rd_source_board_summary, okCount, cells.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (okCount == cells.size) {
                            LocalSemanticColors.current.lowPrice
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                Spacer(Modifier.height(Dims.SpacingS))
            }
            // 健康横条：每源一段等宽色条，先给"整体几绿几红"的瞬时印象
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                cells.forEach { cell ->
                    Spacer(
                        Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(PillShape)
                            .background(sourceHealthColor(cell.state))
                    )
                }
            }
            Spacer(Modifier.height(Dims.SpacingS))
            // 逐源明细：色点 + 源名 + 状态/原因（文字双通道，不只靠颜色）
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Dims.SpacingM),
                verticalArrangement = Arrangement.spacedBy(Dims.SpacingXS)
            ) {
                cells.forEach { cell -> SourceHealthLegend(cell) }
            }
        }
    }
}

@Composable
private fun SourceHealthLegend(cell: SourceHealthCell) {
    val stateLabel = stringResource(sourceHealthLabelRes(cell.state))
    val reason = cell.note?.takeIf { it.isNotBlank() }
    // 颜色必须在组合期读出来：draw 作用域里不能调 @Composable
    val stateColor = sourceHealthColor(cell.state)
    // 读屏一句话读完：源名、状态、原因；视觉上颜色只是第二通道
    val description = stringResource(
        R.string.rd_source_board_cd,
        cell.label,
        stateLabel,
        reason?.let { "，$it" } ?: ""
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description }
    ) {
        Canvas(Modifier.size(6.dp)) { drawCircle(color = stateColor) }
        Spacer(Modifier.width(Dims.SpacingXS))
        Text(
            cell.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
        Text(
            text = if (reason != null) "· $stateLabel · $reason" else "· $stateLabel",
            style = MaterialTheme.typography.labelSmall,
            color = if (cell.state == SourceHealthState.OK) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                stateColor
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
