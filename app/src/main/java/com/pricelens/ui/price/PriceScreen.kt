package com.pricelens.ui.price

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pricelens.R
import com.pricelens.ui.common.AsyncValue
import com.pricelens.ui.common.valueOrNull
import com.pricelens.ui.components.EmptyState
import com.pricelens.ui.components.PriceBadge
import com.pricelens.ui.components.PriceCard
import com.pricelens.ui.components.ShimmerList
import com.pricelens.ui.overview.SearchViewModel
import com.pricelens.ui.theme.BadgeTone
import com.pricelens.ui.theme.Dims
import com.pricelens.util.PriceFormatter
import com.pricelens.util.PriceJudgment

/**
 * §6.2 盯价 — 历史价格曲线：手写 Canvas、
 * 当前价脉冲点、最低/最高虚线、大促节点灰竖线；
 * 长按 → BottomSheet「复制当前价 / 导出图片」（长按同时触发卡片浮起）。
 * 阶段4：AsyncValue 三态渲染（加载骨架 / 空态引导 / 失败提示+旧数据兜底）。
 * 「盯价」按钮 → 设定目标价（仅京东 SKU 有后台查价通道），
 * 保存后由 WatchForegroundService 常驻通知栏并每 30 分钟检查。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PriceScreen(
    searchViewModel: SearchViewModel,
    watchViewModel: PriceWatchViewModel
) {
    val loading by searchViewModel.loading.collectAsStateWithLifecycle()
    val historyAsync by searchViewModel.history.collectAsStateWithLifecycle()
    val judgment by searchViewModel.judgment.collectAsStateWithLifecycle()
    val productAsync by searchViewModel.product.collectAsStateWithLifecycle()
    val history = historyAsync.valueOrNull()
    val product = productAsync.valueOrNull()
    var showSheet by remember { mutableStateOf(false) }
    var showWatchDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    when {
        historyAsync is AsyncValue.Loading<*> || (loading && history == null) -> {
            ShimmerList()
            return
        }
        history == null -> {
            EmptyState(
                icon = if (historyAsync is AsyncValue.Error<*>) {
                    Icons.Filled.Warning
                } else {
                    Icons.Filled.QueryStats
                },
                title = stringResource(
                    if (historyAsync is AsyncValue.Error<*>) {
                        R.string.error_load_failed
                    } else {
                        R.string.empty_search_first
                    }
                ),
                desc = stringResource(
                    if (historyAsync is AsyncValue.Error<*>) {
                        R.string.error_retry_hint
                    } else {
                        R.string.price_empty_hint
                    }
                ),
                modifier = Modifier.padding(Dims.SpacingXL)
            )
            return
        }
    }

    Column(Modifier.fillMaxSize().padding(Dims.SpacingXL)) {
        // 失败但持有旧数据：顶部提示，曲线照常展示
        if (historyAsync is AsyncValue.Error<*>) {
            EmptyState(
                icon = Icons.Filled.Warning,
                title = stringResource(R.string.error_load_failed),
                desc = stringResource(R.string.error_retry_hint)
            )
            Spacer(Modifier.height(Dims.SpacingM))
        }
        PriceCard(
            modifier = Modifier.fillMaxWidth(),
            onLongClick = { showSheet = true }
        ) {
            Row {
                Text(
                    stringResource(R.string.price_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                PriceBadge(
                    judgment.label,
                    tone = when (judgment) {
                        is PriceJudgment.LOW -> BadgeTone.POSITIVE
                        is PriceJudgment.SUSPICIOUS -> BadgeTone.NEGATIVE
                        else -> BadgeTone.NEUTRAL
                    }
                )
            }
            Spacer(Modifier.height(Dims.SpacingM))
            PriceChartCanvas(
                history = history,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
            )
            Spacer(Modifier.height(Dims.SpacingM))
            Row {
                Text(
                    stringResource(R.string.price_lowest, PriceFormatter.format(history.lowest)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    stringResource(R.string.price_highest, PriceFormatter.format(history.highest)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // 仅京东 SKU 有后台查价通道（p.3.cn），其他来源不显示入口避免死按钮
        if (product?.skuId != null) {
            Spacer(Modifier.height(Dims.SpacingM))
            Button(
                onClick = { showWatchDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.price_watch_button))
            }
        }
    }

    val watchSkuId = product?.skuId
    if (showWatchDialog && watchSkuId != null) {
        val productTitle = product?.title.orEmpty()
        var targetText by remember { mutableStateOf(history.current.toInt().toString()) }
        AlertDialog(
            onDismissRequest = { showWatchDialog = false },
            title = { Text(stringResource(R.string.price_watch_dialog_title)) },
            text = {
                Column {
                    Text(
                        stringResource(
                            R.string.price_watch_dialog_desc,
                            PriceFormatter.format(history.current)
                        )
                    )
                    Spacer(Modifier.height(Dims.SpacingM))
                    OutlinedTextField(
                        value = targetText,
                        onValueChange = { targetText = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        supportingText = { Text(stringResource(R.string.price_watch_hint)) }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = targetText.toDoubleOrNull()
                        if (target != null && target > 0) {
                            watchViewModel.setTarget(watchSkuId, productTitle, target)
                            Toast.makeText(
                                context, R.string.price_watch_saved, Toast.LENGTH_SHORT
                            ).show()
                            showWatchDialog = false
                        } else {
                            Toast.makeText(
                                context, R.string.price_watch_invalid, Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                ) { Text(stringResource(R.string.price_watch_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showWatchDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            TextButton(onClick = {
                val clipboard =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("price", PriceFormatter.format(history.current))
                )
                showSheet = false
            }) {
                Text(stringResource(R.string.price_copy_current, PriceFormatter.format(history.current)))
            }
            TextButton(onClick = { showSheet = false }) {
                Text(stringResource(R.string.price_export_hint))
            }
        }
    }
}
