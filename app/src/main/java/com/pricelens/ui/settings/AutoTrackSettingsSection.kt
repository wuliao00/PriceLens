package com.pricelens.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.pricelens.R
import com.pricelens.ui.autotrack.AutoTrackCardState
import com.pricelens.ui.components.PriceBadge
import com.pricelens.ui.components.PrimaryActionRow
import com.pricelens.ui.components.SourceHealthBoard
import com.pricelens.ui.components.SourceHealthCell
import com.pricelens.ui.theme.BadgeTone
import com.pricelens.ui.theme.Dims

/**
 * 设置页「自动追价」分组（2026-11 新增，只读摘要 + 去中心页）。
 *
 * 为什么不在设置页复制一整套开关：自动化中心是同一个功能的完整控制台，
 * 两处都放开关键操作会让人不确定"到底哪边算数"；设置页只回答"现在开没开、什么时候跑过"，
 * 并提供去中心页的入口。
 */
@Composable
fun AutoTrackSettingsSection(state: AutoTrackCardState?, onOpenCenter: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                stringResource(R.string.rd_autotrack_title),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            PriceBadge(
                stringResource(
                    if (state?.enabled == true) R.string.rd_autotrack_enabled else R.string.rd_autotrack_disabled
                ),
                if (state?.enabled == true) BadgeTone.POSITIVE else BadgeTone.NEUTRAL
            )
        }
        Spacer(Modifier.height(Dims.SpacingXS))
        Text(
            stringResource(R.string.rd_settings_autotrack_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Dims.SpacingXS))
        Text(
            stringResource(
                if (state?.enabled == true) {
                    R.string.rd_settings_autotrack_enabled_desc
                } else {
                    R.string.rd_settings_autotrack_disabled_desc
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = if (state?.enabled == true) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Spacer(Modifier.height(Dims.SpacingM))
        PrimaryActionRow(
            label = stringResource(R.string.rd_autotrack_open),
            onClick = onOpenCenter
        )
        Spacer(Modifier.height(Dims.SpacingS))
    }
}

/**
 * 设置页「数据源可信度」分组（只读）。
 *
 * 数据来自本轮搜索的准确性快照（同一份来源健康板）；没搜过时如实说"先搜索一次"，
 * 不摆一排灰点冒充"全部未知"。成功时间目前没有持久化数据源，这里不编造时间，
 * 只在说明里写清口径。
 */
@Composable
fun SourceTrustSection(cells: List<SourceHealthCell>) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.rd_settings_trust_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = Dims.SpacingS)
        )
        if (cells.isEmpty()) {
            Text(
                stringResource(R.string.rd_settings_trust_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            SourceHealthBoard(cells = cells)
        }
        Spacer(Modifier.height(Dims.SpacingS))
        Text(
            stringResource(R.string.rd_settings_trust_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
