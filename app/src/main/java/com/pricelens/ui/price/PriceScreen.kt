package com.pricelens.ui.price

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pricelens.ui.components.BadgeTone
import com.pricelens.ui.components.PriceBadge
import com.pricelens.ui.components.PriceCard
import com.pricelens.ui.main.MainUiState
import com.pricelens.ui.theme.Dims
import com.pricelens.util.PriceFormatter
import com.pricelens.util.PriceJudgment

/**
 * §6.2 盯价 — 历史价格曲线：手写 Canvas、
 * 当前价脉冲点、最低/最高虚线、大促节点灰竖线；
 * 长按 → BottomSheet「复制当前价 / 导出图片」。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PriceScreen(state: MainUiState) {
    val history = state.history
    var showSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current

    if (history == null) {
        com.pricelens.ui.bilibili.EmptyHint("搜索后展示历史价格曲线与买卖时机")
        return
    }

    Column(Modifier.fillMaxSize().padding(Dims.SpacingXL)) {
        PriceCard(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = {}, onLongClick = { showSheet = true })
        ) {
            Row {
                Text("历史价格", style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f))
                PriceBadge(
                    state.judgment.label,
                    tone = when (state.judgment) {
                        is PriceJudgment.LOW -> BadgeTone.POSITIVE
                        is PriceJudgment.SUSPICIOUS -> BadgeTone.NEGATIVE
                        else -> BadgeTone.NEUTRAL
                    }
                )
            }
            Spacer(Modifier.height(Dims.SpacingM))
            PriceChartCanvas(
                history = history,
                modifier = Modifier.fillMaxWidth().height(200.dp)
            )
            Spacer(Modifier.height(Dims.SpacingM))
            Row {
                Text("最低 ${PriceFormatter.format(history.lowest)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f))
                Text("最高 ${PriceFormatter.format(history.highest)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("price", PriceFormatter.format(history.current))
                )
                showSheet = false
            }) { Text("复制当前价 ${PriceFormatter.format(history.current)}") }
            TextButton(onClick = { showSheet = false }) { Text("导出图片（长按曲线卡片）") }
        }
    }
}
