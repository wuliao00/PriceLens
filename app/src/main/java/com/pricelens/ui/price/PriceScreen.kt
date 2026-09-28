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
import androidx.compose.runtime.LaunchedEffect
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
import com.pricelens.domain.PrefillReason
import com.pricelens.domain.PrefillSource
import com.pricelens.domain.RejectReason
import com.pricelens.domain.TargetPrefill
import com.pricelens.domain.WatchDecision
import com.pricelens.domain.WatchTargetPolicy
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
import com.pricelens.worker.WatchCheckRunner

/**
 * §6.2 盯价 — 历史价格曲线：手写 Canvas、
 * 当前价脉冲点、最低/最高虚线、大促节点灰竖线；
 * 长按 → BottomSheet「复制当前价 / 导出图片」（长按同时触发卡片浮起）。
 * 阶段4：AsyncValue 三态渲染（加载骨架 / 空态引导 / 失败提示+旧数据兜底）。
 *
 * 2026-09 盯价链路修复（A3）：
 *  - 入口不再写死"只要有 skuId 就显示按钮"：能否盯、以什么平台 + 商品 ID 盯，
 *    统一由 [WatchTargetPolicy.decide] 判定；不可盯时把**具体原因**显示出来。
 *  - 目标价默认值只在历史曲线确实属于同一 SKU 时预填，跨来源时留空并说明依据。
 *  - 新增「盯价检查状态」卡片：上一轮查到几个价、因何跳过几个目标，
 *    并提供"立即检查一次"，不再出现"盯了没反应"的静默态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PriceScreen(searchViewModel: SearchViewModel, watchViewModel: PriceWatchViewModel) {
    val loading by searchViewModel.loading.collectAsStateWithLifecycle()
    val historyAsync by searchViewModel.history.collectAsStateWithLifecycle()
    val judgment by searchViewModel.judgment.collectAsStateWithLifecycle()
    val productAsync by searchViewModel.product.collectAsStateWithLifecycle()
    val keyword by searchViewModel.keyword.collectAsStateWithLifecycle()
    val targets by watchViewModel.watchTargets.collectAsStateWithLifecycle()
    val lastRound by watchViewModel.lastRound.collectAsStateWithLifecycle()
    val untrackable by watchViewModel.untrackableTargets.collectAsStateWithLifecycle()
    val feedback by watchViewModel.feedback.collectAsStateWithLifecycle()
    val pendingOverwrite by watchViewModel.pendingOverwrite.collectAsStateWithLifecycle()
    val checking by watchViewModel.checking.collectAsStateWithLifecycle()
    val history = historyAsync.valueOrNull()
    val product = productAsync.valueOrNull()
    var showSheet by remember { mutableStateOf(false) }
    var showWatchDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // 盯价入口判定（纯函数）：候选的 SKU / 链接决定平台与商品 ID，绝不默认京东
    val decision = remember(product?.skuId, product?.url) {
        WatchTargetPolicy.decide(product?.skuId, product?.url)
    }
    val watchable = decision as? WatchDecision.Watchable
    val watchedTarget = watchable?.let { w -> targets.firstOrNull { it.productId == w.productId } }
    // 目标价预填依据：只有"关键词自带同一 SKU"时历史曲线才属于当前候选
    val prefill = remember(watchable?.externalId, keyword, history?.current, history?.lowest, product?.url) {
        if (watchable == null || history == null) {
            TargetPrefill.NeedsManual(PrefillReason.NO_HISTORY)
        } else {
            WatchTargetPolicy.prefillTargetPrice(
                skuId = watchable.externalId,
                keyword = keyword,
                candidateUrl = product?.url,
                historyCurrent = history.current,
                historyLowest = history.lowest
            )
        }
    }

    // 保存结果 → Toast（文案在 strings_watch.xml，原因由领域层枚举驱动）
    LaunchedEffect(feedback) {
        when (feedback) {
            null -> return@LaunchedEffect
            is WatchFeedback.Saved -> {
                val saved = feedback as WatchFeedback.Saved
                Toast.makeText(
                    context,
                    if (saved.updated) {
                        context.getString(R.string.watch_saved_update, PriceFormatter.format(saved.targetPrice))
                    } else {
                        context.getString(R.string.watch_saved_new, saved.productId)
                    },
                    Toast.LENGTH_SHORT
                ).show()
            }
            is WatchFeedback.Rejected -> {
                val rejected = feedback as WatchFeedback.Rejected
                Toast.makeText(
                    context,
                    context.getString(
                        R.string.watch_save_rejected_reason,
                        watchRejectionText(context, rejected.rejection)
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
            WatchFeedback.BlockedByInvalidId ->
                Toast.makeText(context, R.string.watch_reason_empty_id_blocked, Toast.LENGTH_LONG).show()
            WatchFeedback.InvalidTargetPrice ->
                Toast.makeText(context, R.string.watch_save_invalid_price, Toast.LENGTH_SHORT).show()
        }
        watchViewModel.acknowledgeFeedback()
    }

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

        Spacer(Modifier.height(Dims.SpacingM))
        // 盯价入口：可盯 → 按钮；不可盯 → 明确写出原因（旧实现直接隐藏入口，用户以为功能坏了）
        if (watchable != null) {
            Button(
                onClick = { showWatchDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(
                        if (watchedTarget != null) R.string.watch_button_update else R.string.price_watch_button
                    )
                )
            }
            Spacer(Modifier.height(Dims.SpacingS))
            if (watchedTarget != null) {
                Text(
                    stringResource(R.string.watch_already_tracked, PriceFormatter.format(watchedTarget.targetPrice)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 通道实况：p.3.cn 公开查价已下线，没有星罗 Key 时先讲清楚可能取不到价
            Text(
                stringResource(
                    if (watchViewModel.jdCredentialMissing()) {
                        R.string.watch_channel_jd_degraded
                    } else {
                        R.string.watch_channel_credential_ready
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (product != null && decision is WatchDecision.Rejected) {
            // 有候选但不可盯：把原因摊开讲（旧实现直接不渲染入口，用户只看到"没有盯价按钮"）
            Text(
                watchRejectionText(context, decision),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(Dims.SpacingM))
        WatchStatusCard(
            summary = lastRound,
            untrackableCount = untrackable.size,
            checking = checking,
            onCheckNow = { watchViewModel.checkNow() }
        )
    }

    if (showWatchDialog && watchable != null) {
        val productTitle = product?.title.orEmpty()
        val prefillPrice = (prefill as? TargetPrefill.Prefilled)?.price
        var targetText by remember(watchable.productId) { mutableStateOf(prefillPrice?.let { it.toInt().toString() }.orEmpty()) }
        AlertDialog(
            onDismissRequest = { showWatchDialog = false },
            title = { Text(stringResource(R.string.price_watch_dialog_title)) },
            text = {
                Column {
                    // 说清楚"盯的是哪个平台的哪个 ID"——跨平台冒用在这里可见可查
                    Text(
                        stringResource(
                            R.string.watch_dialog_target_id,
                            context.getString(platformLabelRes(watchable.platform)),
                            watchable.externalId
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(Dims.SpacingS))
                    when (prefill) {
                        is TargetPrefill.Prefilled -> Text(
                            stringResource(
                                if (prefill.source == PrefillSource.HISTORY_CURRENT) {
                                    R.string.watch_target_prefill_current
                                } else {
                                    R.string.watch_target_prefill_lowest
                                },
                                PriceFormatter.format(prefill.price)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        is TargetPrefill.NeedsManual -> Text(
                            stringResource(
                                if (prefill.reason == PrefillReason.CROSS_SOURCE_HISTORY) {
                                    R.string.watch_target_cross_source
                                } else {
                                    R.string.watch_target_no_history
                                }
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (prefill is TargetPrefill.Prefilled && history.lowest > 0) {
                        Text(
                            stringResource(R.string.watch_target_same_sku_low, PriceFormatter.format(history.lowest)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (prefill !is TargetPrefill.Prefilled && product != null && product.price > 0) {
                        Text(
                            stringResource(R.string.watch_dialog_candidate_price, PriceFormatter.format(product.price)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
                            // 平台与 productId 由 decision 决定，不再由 ViewModel 硬编码成 jd
                            watchViewModel.setTarget(decision = watchable, title = productTitle, targetPrice = target)
                            showWatchDialog = false
                        } else {
                            Toast.makeText(context, R.string.watch_save_invalid_price, Toast.LENGTH_SHORT).show()
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

    // 同 ID 不同标题：必须确认，绝不静默 REPLACE 掉用户的另一个目标
    pendingOverwrite?.let { pending ->
        AlertDialog(
            onDismissRequest = { watchViewModel.dismissOverwrite() },
            title = { Text(stringResource(R.string.watch_conflict_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.watch_conflict_desc,
                        pending.decision.productId,
                        pending.existingTitle,
                        pending.title
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        watchViewModel.confirmOverwrite()
                        showWatchDialog = false
                    }
                ) { Text(stringResource(R.string.watch_conflict_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { watchViewModel.dismissOverwrite() }) {
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

/** 检查轮次实况：查到几个价、因何跳过几个目标，替代旧的"永远静默" */
@Composable
private fun WatchStatusCard(summary: WatchCheckRunner.RoundSummary?, untrackableCount: Int, checking: Boolean, onCheckNow: () -> Unit) {
    PriceCard(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.watch_status_title), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(Dims.SpacingS))
        if (summary == null) {
            Text(
                stringResource(R.string.watch_status_never),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                stringResource(
                    R.string.watch_status_round,
                    summary.lastCheckText(),
                    summary.total,
                    summary.checked,
                    summary.triggered
                ),
                style = MaterialTheme.typography.bodySmall
            )
            if (summary.skipped.noChannel > 0) {
                Text(
                    stringResource(R.string.watch_skip_no_channel, summary.skipped.noChannel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (summary.skipped.badTargetId > 0) {
                Text(
                    stringResource(R.string.watch_skip_bad_id, summary.skipped.badTargetId),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (summary.skipped.noPrice > 0) {
                Text(
                    stringResource(R.string.watch_skip_no_price, summary.skipped.noPrice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (summary.stalled) {
                Spacer(Modifier.height(Dims.SpacingS))
                Text(
                    stringResource(R.string.watch_status_stalled),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        if (untrackableCount > 0) {
            Text(
                stringResource(R.string.watch_broken_targets_hint, untrackableCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(Modifier.height(Dims.SpacingS))
        TextButton(
            onClick = onCheckNow,
            enabled = !checking
        ) {
            Text(stringResource(if (checking) R.string.watch_check_running else R.string.watch_check_now))
        }
    }
}

/** 不可盯 / 保存被拒的原因文案（平台名与商品号都带上） */
private fun watchRejectionText(context: Context, rejection: WatchDecision.Rejected): String {
    val platform = context.getString(platformLabelRes(rejection.platform))
    return when (rejection.reason) {
        RejectReason.MISSING_ID -> context.getString(R.string.watch_reason_missing_id)
        RejectReason.MALFORMED_ID -> context.getString(R.string.watch_reason_malformed_id, rejection.displayId)
        RejectReason.PLATFORM_CONFLICT -> context.getString(R.string.watch_reason_platform_conflict, platform, rejection.displayId)
        RejectReason.NO_PRICE_CHANNEL -> if (rejection.displayId.isBlank()) {
            context.getString(R.string.watch_reason_no_channel_no_id, platform)
        } else {
            context.getString(R.string.watch_reason_no_channel, platform, rejection.displayId)
        }
    }
}

private fun platformLabelRes(platform: String): Int = when (platform) {
    WatchTargetPolicy.PLATFORM_JD -> R.string.watch_platform_jd
    WatchTargetPolicy.PLATFORM_TAOBAO -> R.string.watch_platform_taobao
    WatchTargetPolicy.PLATFORM_PDD -> R.string.watch_platform_pdd
    WatchTargetPolicy.PLATFORM_DANGDANG -> R.string.watch_platform_dangdang
    WatchTargetPolicy.PLATFORM_SHIHUO -> R.string.watch_platform_shihuo
    WatchTargetPolicy.PLATFORM_SMZDM -> R.string.watch_platform_smzdm
    else -> R.string.watch_platform_unknown
}
