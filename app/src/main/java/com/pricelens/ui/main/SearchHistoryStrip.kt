package com.pricelens.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pricelens.R

/**
 * 顶栏搜索框聚焦时展示的「最近搜索」chips（文档 UX：聚焦时下方展示搜索历史）。
 * 点一条 = 直接用该关键词重新搜索；数据与「我的」页搜索历史同一来源（SearchViewModel.recentSearches）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchHistoryStrip(keywords: List<String>, onPick: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(
            stringResource(R.string.search_history_recent),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            keywords.take(10).forEach { kw ->
                AssistChip(onClick = { onPick(kw) }, label = { Text(kw, maxLines = 1) })
            }
        }
    }
}
