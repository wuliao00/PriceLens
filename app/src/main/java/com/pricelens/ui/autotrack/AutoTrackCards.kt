package com.pricelens.ui.autotrack

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.PriceCheck
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.pricelens.R
import com.pricelens.ui.components.CardSectionTitle
import com.pricelens.ui.components.IconTile
import com.pricelens.ui.components.PillChoice
import com.pricelens.ui.components.PriceBadge
import com.pricelens.ui.components.PriceCard
import com.pricelens.ui.components.StatTile
import com.pricelens.ui.theme.BadgeTone
import com.pricelens.ui.theme.Dims
import com.pricelens.ui.theme.LocalSemanticColors
import com.pricelens.util.PriceFormatter
import com.pricelens.util.TimeAgo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自动追价 / 价保助手的**展示层模型与卡片**（2026-11 UI 大改）。
 *
 * 与准确性那一层同样的分界：引擎（AutoTrackRepository / PriceProtectionRepository）
 * 负责状态，本文件只定义 UI 形状与渲染；引擎落地后由 AutoTrackUiMapper 把 StateFlow
 * 映射成这里的两个 State，卡片不改一行。
 *
 * 为什么这两个卡先做成「可为 null」：引擎文件可能比 UI 晚落地，页面必须始终可编译；
 * null = 本轮拿不到引擎状态，此时卡片退化成**去中心页的入口卡**（不是占位灰块），
 * 既不给假开关，也不给"暂无数据"的空话。
 */

/** 自动追价卡的数据形状（引擎 AutoTrackState 的 UI 投影，字段以引擎源码为准） */
data class AutoTrackCardState(
    val enabled: Boolean = false,
    val intervalMinutes: Int = 60,
    val lastRunAtMs: Long? = null,
    val nextRunAtMs: Long? = null,
    val targetCount: Int = 0,
    val lastChecked: Int = 0,
    val lastHits: Int = 0,
    val running: Boolean = false,
    val lastNote: String? = null,
    val wifiOnly: Boolean = false,
    val quietPause: Boolean = false,
    val badSourceCount: Int = 0
)

/** 价保助手卡的数据形状（引擎 ProtectionState 的 UI 投影） */
data class ProtectionCardState(
    val watchCount: Int = 0,
    val claimableCount: Int = 0,
    val claimableYuan: Double = 0.0,
    val lastCheckAtMs: Long? = null,
    val nextCheckAtMs: Long? = null
)

/** 最近自动搜索记录的一行（引擎 AutoTrackRunEntity 的 UI 投影） */
data class AutoTrackRunView(
    val atMs: Long,
    val targets: Int,
    val checked: Int,
    val hits: Int,
    val note: String
)

/** 频率选项：与任务契约一致，30/60/120/180/360 分钟 */
val AutoTrackIntervals: List<Int> = listOf(30, 60, 120, 180, 360)

/**
 * 自动追价中心卡（盯价页顶部 / 自动化中心页共用）。
 *
 * 信息层级（Lead 指定的顺序）：开关与运行状态 → 目标/命中/上次/下次 → 立即运行
 * → 频率与运行条件 → 异常源与隐私说明。所有交互控件触控高度 ≥48dp；
 * 开关带明确的读屏标签（[R.string.rd_autotrack_switch]）。
 *
 * @param state null = 引擎状态未就绪，只渲染「打开自动化中心」入口卡
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutoTrackCenterCard(
    state: AutoTrackCardState?,
    onToggle: (Boolean) -> Unit,
    onRunNow: () -> Unit,
    onOpenCenter: () -> Unit,
    onIntervalSelected: (Int) -> Unit,
    onWifiOnlyChange: (Boolean) -> Unit,
    onQuietPauseChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    showConditions: Boolean = true,
    showOpenCenterButton: Boolean = true
) {
    if (state == null) {
        CenterEntryCard(
            icon = Icons.Filled.PriceCheck,
            title = stringResource(R.string.rd_autotrack_title),
            desc = stringResource(R.string.rd_autotrack_subtitle),
            action = stringResource(R.string.rd_autotrack_open),
            onClick = onOpenCenter,
            modifier = modifier
        )
        return
    }
    PriceCard(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Filled.PriceCheck)
            Spacer(Modifier.width(Dims.SpacingM))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.rd_autotrack_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.rd_autotrack_subtitle),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val switchCd = stringResource(R.string.rd_autotrack_switch)
            Switch(
                checked = state.enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.semantics { contentDescription = switchCd }
            )
        }
        Spacer(Modifier.height(Dims.SpacingM))
        Row(
            horizontalArrangement = Arrangement.spacedBy(Dims.SpacingL),
            modifier = Modifier.fillMaxWidth()
        ) {
            StatTile(
                label = stringResource(R.string.rd_autotrack_stat_targets),
                value = state.targetCount.toString(),
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = stringResource(R.string.rd_autotrack_stat_checked),
                value = state.lastChecked.toString(),
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = stringResource(R.string.rd_autotrack_stat_hits),
                value = state.lastHits.toString(),
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(Dims.SpacingS))
        val lastRunText = stringResource(
            R.string.rd_autotrack_last_run,
            state.lastRunAtMs?.let { TimeAgo.format(it) } ?: stringResource(R.string.rd_autotrack_never_run)
        )
        val nextRunText = if (state.enabled && state.nextRunAtMs != null) {
            stringResource(R.string.rd_autotrack_next_run, hmFormat.format(Date(state.nextRunAtMs)))
        } else {
            stringResource(R.string.rd_autotrack_next_pending)
        }
        Text(
            "$lastRunText · $nextRunText",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        state.lastNote?.let { note ->
            Spacer(Modifier.height(Dims.SpacingXS))
            Text(
                stringResource(R.string.rd_autotrack_last_note, note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (state.badSourceCount > 0) {
            Spacer(Modifier.height(Dims.SpacingXS))
            Text(
                stringResource(R.string.rd_autotrack_source_issues, state.badSourceCount),
                style = MaterialTheme.typography.labelSmall,
                color = LocalSemanticColors.current.suspicious
            )
        }
        Spacer(Modifier.height(Dims.SpacingM))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onRunNow,
                enabled = !state.running,
                modifier = Modifier.heightIn(min = Dims.TouchMin)
            ) {
                Text(
                    stringResource(
                        if (state.running) R.string.rd_autotrack_running else R.string.rd_autotrack_run_now
                    )
                )
            }
            Spacer(Modifier.weight(1f))
            if (showOpenCenterButton) {
                TextButton(
                    onClick = onOpenCenter,
                    modifier = Modifier.heightIn(min = Dims.TouchMin)
                ) { Text(stringResource(R.string.rd_autotrack_open)) }
            }
        }
        if (state.running) {
            // 运行中给一条不确定进度条：这是"正在跑"的状态反馈，不是转场动画
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = Dims.SpacingXS)
            )
        }
        if (showConditions) {
            Spacer(Modifier.height(Dims.SpacingS))
            Text(
                stringResource(R.string.rd_autotrack_interval),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dims.SpacingXS)) {
                AutoTrackIntervals.forEach { minutes ->
                    PillChoice(
                        label = stringResource(R.string.rd_autotrack_interval_option, minutes),
                        selected = state.intervalMinutes == minutes,
                        onClick = { onIntervalSelected(minutes) }
                    )
                }
            }
            Spacer(Modifier.height(Dims.SpacingXS))
            SwitchRow(
                label = stringResource(R.string.rd_autotrack_wifi_only),
                checked = state.wifiOnly,
                onCheckedChange = onWifiOnlyChange
            )
            SwitchRow(
                label = stringResource(R.string.rd_autotrack_quiet_pause),
                checked = state.quietPause,
                onCheckedChange = onQuietPauseChange
            )
        }
        Spacer(Modifier.height(Dims.SpacingXS))
        Text(
            stringResource(R.string.rd_autotrack_privacy),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 概览首屏的自动追价迷你状态卡（Lead 指定的「追价状态小卡」）。
 *
 * 只报状态、不放开控制：开关与频率都在盯价页/自动化中心，首屏只回答
 * 「它开着没、上次什么时候跑过、有多少目标」并给一个去中心页的箭头。
 * state 为 null 时不渲染（引擎未就绪就不摆空卡）。
 */
@Composable
fun AutoTrackMiniCard(state: AutoTrackCardState?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (state == null) return
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(Dims.SpacingM)
        ) {
            IconTile(Icons.Filled.PriceCheck)
            Spacer(Modifier.width(Dims.SpacingM))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.rd_autotrack_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.width(Dims.SpacingS))
                    PriceBadge(
                        stringResource(
                            if (state.enabled) R.string.rd_autotrack_enabled else R.string.rd_autotrack_disabled
                        ),
                        if (state.enabled) BadgeTone.POSITIVE else BadgeTone.NEUTRAL
                    )
                }
                Text(
                    stringResource(
                        R.string.rd_autotrack_last_run,
                        state.lastRunAtMs?.let { TimeAgo.format(it) }
                            ?: stringResource(R.string.rd_autotrack_never_run)
                    ) + if (state.targetCount > 0) {
                        " · " + stringResource(R.string.rd_autotrack_stat_targets) + " " + state.targetCount
                    } else {
                        ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Dims.IconInline)
            )
        }
    }
}

/**
 * 价保助手卡（盯价页第二张卡 / 个人页入口）。
 *
 * 空态不是空白：没有记录购买时给一句"买了东西记一笔"的说明与入口；
 * 有记录时先报「可申请笔数 + 预计金额」，这两个数就是用户点进价保页的理由。
 */
@Composable
fun ProtectionCenterCard(state: ProtectionCardState?, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    if (state == null) {
        CenterEntryCard(
            icon = Icons.Filled.ConfirmationNumber,
            title = stringResource(R.string.rd_protect_title),
            desc = stringResource(R.string.rd_protect_subtitle),
            action = stringResource(R.string.rd_protect_open),
            onClick = onOpen,
            modifier = modifier
        )
        return
    }
    PriceCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onOpen
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Filled.ConfirmationNumber)
            Spacer(Modifier.width(Dims.SpacingM))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.rd_protect_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.rd_protect_subtitle),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Dims.IconInline)
            )
        }
        Spacer(Modifier.height(Dims.SpacingM))
        if (state.watchCount == 0) {
            Text(
                stringResource(R.string.rd_protect_empty_card),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Dims.SpacingL),
                modifier = Modifier.fillMaxWidth()
            ) {
                StatTile(
                    label = stringResource(R.string.rd_protect_claimable_label_short),
                    value = stringResource(R.string.rd_protect_claimable_count, state.claimableCount),
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    label = stringResource(R.string.rd_protect_claimable_amount_label),
                    value = PriceFormatter.format(state.claimableYuan),
                    modifier = Modifier.weight(1f)
                )
                Column(Modifier.weight(1.2f)) {
                    Text(
                        stringResource(
                            R.string.rd_protect_last_check,
                            state.lastCheckAtMs?.let { TimeAgo.format(it) }
                                ?: stringResource(R.string.rd_watch_target_never_checked)
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (state.nextCheckAtMs != null) {
                        Text(
                            stringResource(R.string.rd_protect_next_check, hmFormat.format(Date(state.nextCheckAtMs))),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
                }
            }
        }
    }
}

/**
 * 最近自动搜索记录（盯价页 / 自动化中心共用）。
 * 列表为空时只说"还没有记录"，不给假行；每条给时间、目标、结果与来源。
 */
@Composable
fun AutoTrackHistorySection(
    runs: List<AutoTrackRunView>,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.rd_watch_auto_records_title),
    emptyText: String = stringResource(R.string.rd_autotrack_history_empty)
) {
    Column(modifier.fillMaxWidth()) {
        CardSectionTitle(title)
        if (runs.isEmpty()) {
            Text(
                emptyText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = Dims.SpacingXS)
            )
        } else {
            // 每行两段：时间 + 状态徽标/note。note 是引擎一句话总结（已含数字），
            // 因此摘要行不再重复写「目标 N · 查到价 N · 命中 N」——真机首轮两行数字完全重复。
            runs.forEach { run ->
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Dims.SpacingXS)
                ) {
                    Text(
                        hmFormat.format(Date(run.atMs)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(Dims.SpacingXXXL + Dims.SpacingXL)
                    )
                    Column(Modifier.weight(1f)) {
                        PriceBadge(
                            stringResource(if (run.hits > 0) R.string.rd_run_hit else R.string.rd_run_miss),
                            if (run.hits > 0) BadgeTone.POSITIVE else BadgeTone.NEUTRAL
                        )
                        Spacer(Modifier.height(Dims.SpacingXS))
                        Text(
                            run.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** 入口卡（引擎状态未就绪时用）：图标 + 标题 + 说明 + 箭头，整卡可点 */
@Composable
private fun CenterEntryCard(
    icon: ImageVector,
    title: String,
    desc: String,
    action: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(Dims.SpacingL)
        ) {
            IconTile(icon)
            Spacer(Modifier.width(Dims.SpacingM))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(Dims.SpacingXS))
                Text(
                    action,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Dims.IconInline)
            )
        }
    }
}

/** 开关行：文案在左、开关在右，整行高 ≥48dp（点标题也能切） */
@Composable
fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dims.TouchMin)
            .clickable { onCheckedChange(!checked) }
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = label }
        )
    }
}

/** 「HH:mm」时间格式：自动追价/价保的"上次/下次"口径统一（WorkManager 不暴露确切触发时刻，故不写秒） */
private val hmFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
