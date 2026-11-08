package com.pricelens.ui.price

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pricelens.R
import com.pricelens.domain.EnterReplayGuard
import com.pricelens.domain.PrefillReason
import com.pricelens.domain.PrefillSource
import com.pricelens.domain.RejectReason
import com.pricelens.domain.TargetPrefill
import com.pricelens.domain.WatchDecision
import com.pricelens.domain.WatchTargetPolicy
import com.pricelens.ui.autotrack.AutoTrackCardState
import com.pricelens.ui.autotrack.AutoTrackCenterCard
import com.pricelens.ui.autotrack.AutoTrackHistorySection
import com.pricelens.ui.autotrack.AutoTrackRunView
import com.pricelens.ui.autotrack.ProtectionCardState
import com.pricelens.ui.autotrack.ProtectionCenterCard
import com.pricelens.ui.common.AsyncValue
import com.pricelens.ui.common.valueOrNull
import com.pricelens.ui.components.EmptyState
import com.pricelens.ui.components.PriceBadge
import com.pricelens.ui.components.PriceCard
import com.pricelens.ui.components.SectionHeader
import com.pricelens.ui.components.ShimmerList
import com.pricelens.ui.components.SkeletonCrossfade
import com.pricelens.ui.components.enterReveal
import com.pricelens.ui.components.platformLabelRes
import com.pricelens.ui.overview.SearchViewModel
import com.pricelens.ui.theme.BadgeTone
import com.pricelens.ui.theme.Dims
import com.pricelens.ui.theme.LocalSemanticColors
import com.pricelens.util.PriceFormatter
import com.pricelens.util.PriceJudgment
import com.pricelens.worker.WatchCheckRunner
import com.pricelens.worker.WatchCountdown
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 盯价页 → **自动追价中心**（2026-11 大改重排）。
 *
 * 区块顺序按「用户打开这一页最想知道的」排（Lead 指定，改造前是两张平铺卡 + 大块空白）：
 *  ① [AutoTrackCenterCard] 自动追价状态卡（开关 / 上次·下次运行 / 目标·命中 / 立即运行 / 频率 / 仅 WiFi / 夜间暂停）
 *  ② [ProtectionCenterCard] 价保助手卡（可申请笔数 / 预计金额 / 最近检查 → 进价保页）
 *  ③ 当前商品的历史价格（曲线 + 最低/最高 + 建议 + 出处；没有曲线时给成因与「去搜索」CTA）
 *  ④ 盯价入口（可盯 → 设/改目标价；不可盯 → 如实说原因）
 *  ⑤ 盯价目标列表（现价 vs 目标、进度、最近检查、来源口径、改价/暂停/删除）
 *  ⑥ 浮窗身份列表（保留原能力）
 *  ⑦ 最近自动搜索记录（引擎有记录时；空则一句说明）
 *  ⑧ [WatchStatusCard] 最近一轮检查实况 + 立即检查
 *
 * 引擎状态（自动追价 / 价保）通过 [autoTrack] / [protection] 传入：
 * null = 引擎未就绪，卡片退化成去中心页的入口卡，页面仍完整可编译；
 * 引擎落地后只改 MainActivity 的映射与回调，本文件排版不动。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PriceScreen(
    searchViewModel: SearchViewModel,
    watchViewModel: PriceWatchViewModel,
    onOpenProduct: (String) -> Unit = {},
    autoTrack: AutoTrackCardState? = null,
    protection: ProtectionCardState? = null,
    autoTrackRuns: List<AutoTrackRunView> = emptyList(),
    onAutoTrackToggle: (Boolean) -> Unit = {},
    onAutoTrackRunNow: () -> Unit = {},
    onOpenAutoTrackCenter: () -> Unit = {},
    onAutoTrackInterval: (Int) -> Unit = {},
    onAutoTrackWifiOnly: (Boolean) -> Unit = {},
    onAutoTrackQuietPause: (Boolean) -> Unit = {},
    onOpenPriceProtection: () -> Unit = {},
    onGoSearch: () -> Unit = {}
) {
    val loading by searchViewModel.loading.collectAsStateWithLifecycle()
    val historyAsync by searchViewModel.history.collectAsStateWithLifecycle()
    val judgment by searchViewModel.judgment.collectAsStateWithLifecycle()
    val adviceState by searchViewModel.advice.collectAsStateWithLifecycle()
    val advicePercentile by searchViewModel.advicePercentile.collectAsStateWithLifecycle()
    val curveProvenance by searchViewModel.curveProvenance.collectAsStateWithLifecycle()
    val productAsync by searchViewModel.product.collectAsStateWithLifecycle()
    val keyword by searchViewModel.keyword.collectAsStateWithLifecycle()
    val targets by watchViewModel.watchTargets.collectAsStateWithLifecycle()
    val lastRound by watchViewModel.lastRound.collectAsStateWithLifecycle()
    val untrackable by watchViewModel.untrackableTargets.collectAsStateWithLifecycle()
    val feedback by watchViewModel.feedback.collectAsStateWithLifecycle()
    val pendingOverwrite by watchViewModel.pendingOverwrite.collectAsStateWithLifecycle()
    val checking by watchViewModel.checking.collectAsStateWithLifecycle()
    val identities by watchViewModel.identities.collectAsStateWithLifecycle()
    val identityDays by watchViewModel.identityDays.collectAsStateWithLifecycle()
    val history = historyAsync.valueOrNull()
    val product = productAsync.valueOrNull()
    var showSheet by remember { mutableStateOf(false) }
    var showWatchDialog by remember { mutableStateOf(false) }
    var editingTarget by remember { mutableStateOf<WatchTargetRowView?>(null) }
    val context = LocalContext.current
    // §2.4 入场守卫：挂在**屏幕级** remember —— 骨架分支 return 掉整个内容组合后，
    // 守卫还活着，所以交叉淡入和阶梯入场都只在首次数据到达时播一次（键见 PriceEnterKeys）。
    val enterGuard = remember { EnterReplayGuard() }

    // 盯价入口判定（纯函数）：候选的 SKU / 链接决定平台与商品 ID，绝不默认京东
    val decision = remember(product?.skuId, product?.url) {
        WatchTargetPolicy.decide(product?.skuId, product?.url)
    }
    val watchable = decision as? WatchDecision.Watchable
    val watchedTarget = watchable?.let { w -> targets.firstOrNull { it.productId == w.productId && it.active } }
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
    // 目标列表的行模型：现价/最近检查等引擎字段落地后只改 watchTargetRowOf 的映射
    val targetRows = remember(targets) { targets.map(::watchTargetRowOf) }

    // 保存结果 → Toast（文案在 strings_watch.xml，原因由领域层枚举驱动）
    LaunchedEffect(feedback) {
        when (val current = feedback) {
            null -> return@LaunchedEffect
            is WatchFeedback.Saved -> {
                Toast.makeText(
                    context,
                    if (current.updated) {
                        context.getString(R.string.watch_saved_update, PriceFormatter.format(current.targetPrice))
                    } else {
                        context.getString(R.string.watch_saved_new, current.productId)
                    },
                    Toast.LENGTH_SHORT
                ).show()
            }
            is WatchFeedback.Rejected -> {
                Toast.makeText(
                    context,
                    context.getString(
                        R.string.watch_save_rejected_reason,
                        watchRejectionText(context, current.rejection)
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

    // 只有"正在取历史"才整页骨架。**没有历史曲线绝不能提前 return**：
    // 盯价入口、目标列表与「盯价检查状态」卡必须在没有曲线时照样出现——
    // 用户搜到的常常是当当/值得买候选（归属不到京东 SKU），旧实现在这里 return，
    // 于是这些商品永远看不到盯价入口，只看到一句"请先搜索商品"（真机 2026-09-28 实测）。
    if (historyAsync is AsyncValue.Loading<*> || (loading && productAsync.valueOrNull() == null)) {
        ShimmerList()
        return
    }

    SkeletonCrossfade(
        guard = enterGuard,
        enterKey = PriceEnterKeys.Content,
        modifier = Modifier.fillMaxSize(),
        skeleton = { ShimmerList() }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Dims.SpacingXL)
        ) {
            // ① 自动追价状态卡：本页的主卡，排在最上（开关 + 运行状态 + 立即运行 + 频率）
            // 活跃目标数从盯价 VM 数出来（引擎状态契约里没有这个字段）
            AutoTrackCenterCard(
                state = autoTrack?.copy(targetCount = targets.count { it.active }),
                onToggle = onAutoTrackToggle,
                onRunNow = onAutoTrackRunNow,
                onOpenCenter = onOpenAutoTrackCenter,
                onIntervalSelected = onAutoTrackInterval,
                onWifiOnlyChange = onAutoTrackWifiOnly,
                onQuietPauseChange = onAutoTrackQuietPause,
                modifier = Modifier.enterReveal(0, enterGuard, PriceEnterKeys.AutoTrack)
            )

            Spacer(Modifier.height(Dims.SpacingM))
            // ② 价保助手卡
            ProtectionCenterCard(
                state = protection,
                onOpen = onOpenPriceProtection,
                modifier = Modifier.enterReveal(1, enterGuard, PriceEnterKeys.Protection)
            )

            Spacer(Modifier.height(Dims.SpacingXL))
            SectionHeader(stringResource(R.string.rd_price_current_section))
            // 失败但持有旧数据：顶部提示，曲线照常展示
            if (historyAsync is AsyncValue.Error<*>) {
                EmptyState(
                    icon = Icons.Filled.Warning,
                    title = stringResource(R.string.error_load_failed),
                    desc = stringResource(R.string.error_retry_hint)
                )
                Spacer(Modifier.height(Dims.SpacingM))
            }
            if (history == null) {
                // 三种"没有曲线"的成因分开说，否则用户按提示去做的事是白做：
                //  ① 取历史失败（Error）② 还没搜过（关键词为空）
                //  ③ 搜过了但候选归属不到京东 SKU（SearchViewModel 此时把 history 置回 Idle）
                val failed = historyAsync is AsyncValue.Error<*>
                val searched = keyword.isNotBlank()
                EmptyState(
                    icon = when {
                        failed -> Icons.Filled.Warning
                        searched -> Icons.Filled.Info
                        else -> Icons.Filled.QueryStats
                    },
                    title = stringResource(
                        when {
                            failed -> R.string.error_load_failed
                            searched -> R.string.watch_no_curve_title
                            else -> R.string.empty_search_first
                        }
                    ),
                    desc = stringResource(
                        when {
                            failed -> R.string.error_retry_hint
                            searched -> R.string.watch_no_curve_desc
                            else -> R.string.price_empty_hint
                        }
                    ),
                    actionLabel = stringResource(R.string.rd_watch_targets_empty_action_search),
                    onAction = onGoSearch,
                    modifier = Modifier.enterReveal(2, enterGuard, PriceEnterKeys.Curve)
                )
            } else {
                PriceCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .enterReveal(2, enterGuard, PriceEnterKeys.Curve),
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
                    // §2.3 主价格：从"上一次展示到的值"滚到当前价（首次数据到达 = 从 0 countUp）。
                    // 只在 current>0 时占这一行 —— 有历史但取不到现价时摆个 ¥0 比不摆更误导。
                    if (history.current > 0) {
                        Spacer(Modifier.height(Dims.SpacingS))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                stringResource(R.string.motion_price_current),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = Dims.SpacingS)
                            )
                            Spacer(Modifier.width(Dims.SpacingS))
                            RollingPriceText(value = history.current)
                        }
                    }
                    Spacer(Modifier.height(Dims.SpacingM))
                    if (history.points.size < 2) {
                        // 只有 1 个采样日时不画那块 200dp 的空白：空框看起来像"又显示错了"，
                        // 而实情是点还没攒够 —— 折线至少要两个日点。把已有的那一点如实说出来。
                        val only = history.points.firstOrNull()
                        val singleDayText: String = if (only == null) {
                            stringResource(R.string.watch_curve_source_none)
                        } else {
                            stringResource(R.string.watch_curve_one_point, only.date, PriceFormatter.format(only.price))
                        }
                        Text(
                            singleDayText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        PriceChartCanvas(
                            history = history,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                        )
                    }
                    Spacer(Modifier.height(Dims.SpacingM))
                    // 最低/最高图例与曲线虚线同色（绿/红圆点），一眼对上哪条线是哪个值
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LegendDot(LocalSemanticColors.current.lowPrice)
                        Text(
                            stringResource(R.string.price_lowest, PriceFormatter.format(history.lowest)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = Dims.SpacingXS)
                        )
                        LegendDot(LocalSemanticColors.current.suspicious)
                        Text(
                            stringResource(R.string.price_highest, PriceFormatter.format(history.highest)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = Dims.SpacingXS)
                        )
                    }
                    Spacer(Modifier.height(Dims.SpacingS))
                    // 购买建议（文档 §10）：把"当前价处在历史什么位置"翻成一句能拍板的话。
                    // UNKNOWN（采样点不够）不显示 —— 徽章位不摆"暂无判断"这种废话。
                    val adviceNow = adviceState
                    if (adviceNow != null && adviceNow != com.pricelens.domain.PriceAdvice.Advice.UNKNOWN) {
                        val pct = advicePercentile
                        Text(
                            text = if (pct != null) {
                                stringResource(R.string.advice_percentile, pct) + " · " +
                                    stringResource(adviceStringRes(adviceNow))
                            } else {
                                stringResource(adviceStringRes(adviceNow))
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(Dims.SpacingS))
                    }
                    // 曲线出处脚注（2026-09-30 盯价自采）：这条线是本机一轮轮攒的还是慢慢买给的，
                    // 必须看得出来 —— 否则"历史最低"到底是谁的低点就没人说得清。
                    val curveDays = curveProvenance?.dayPairsText().orEmpty()
                    if (curveDays.isBlank()) {
                        Text(
                            stringResource(R.string.watch_curve_source_none),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            stringResource(R.string.watch_curve_source_footnote, curveDays),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (curveProvenance?.isSelfCollected == true) {
                            Text(
                                stringResource(R.string.watch_curve_source_self_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(Dims.SpacingM))
            // ④ 盯价入口：可盯 → 按钮；不可盯 → 明确写出原因（旧实现直接隐藏入口，用户以为功能坏了）
            if (watchable != null) {
                Button(
                    onClick = { showWatchDialog = true },
                    // 说明文字保持静态，只有按钮参加阶梯：它们是附属说明，不是一个列表项
                    modifier = Modifier
                        .fillMaxWidth()
                        .enterReveal(3, enterGuard, PriceEnterKeys.Watch)
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

            // ⑤ 盯价目标列表：本页的核心列表；空态 CTA 指向"设目标价 / 去搜索"
            Spacer(Modifier.height(Dims.SpacingXL))
            val emptyAction: () -> Unit = {
                if (watchable != null) showWatchDialog = true else onGoSearch()
            }
            WatchTargetsSection(
                targets = targetRows,
                emptyActionLabel = stringResource(
                    if (watchable != null) R.string.price_watch_button else R.string.rd_watch_targets_empty_action_search
                ),
                onEditPrice = { editingTarget = it },
                onPause = watchViewModel::pauseTarget,
                onResume = watchViewModel::resumeTarget,
                onDelete = watchViewModel::deleteTarget,
                onOpenProduct = onOpenProduct,
                onEmptyAction = emptyAction,
                modifier = Modifier.enterReveal(4, enterGuard, PriceEnterKeys.Targets)
            )

            // ⑥ 浮窗身份（本机自采曲线的管理入口；列表为空整节不出现，不留空占位）
            if (identities.isNotEmpty()) {
                Spacer(Modifier.height(Dims.SpacingXL))
                SectionHeader(stringResource(R.string.watch_identity_section_title))
                identities.forEachIndexed { index, identity ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = Dims.SpacingXS)
                            .enterReveal(index, enterGuard, PriceEnterKeys.IdentityPrefix + identity.productId)
                            .clickable { onOpenProduct(identity.title) },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = Dims.SpacingM, vertical = Dims.SpacingS)
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    identity.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    stringResource(R.string.watch_identity_days, identityDays[identity.productId] ?: 0),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = { watchViewModel.cancelIdentity(identity.productId) }) {
                                Text(
                                    stringResource(R.string.watch_identity_cancel),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }

            // ⑦ 最近自动搜索记录：引擎有状态才渲染（没有引擎时不摆空区块）
            if (autoTrack != null || autoTrackRuns.isNotEmpty()) {
                Spacer(Modifier.height(Dims.SpacingXL))
                PriceCard(modifier = Modifier.fillMaxWidth()) {
                    AutoTrackHistorySection(
                        runs = autoTrackRuns.take(10),
                        title = stringResource(R.string.rd_watch_auto_records_title),
                        emptyText = stringResource(R.string.rd_watch_auto_records_empty)
                    )
                }
            }

            // ⑧ 最近一轮检查实况 + 立即检查
            Spacer(Modifier.height(Dims.SpacingXL))
            WatchStatusCard(
                summary = lastRound,
                untrackableCount = untrackable.size,
                activeTargetCount = targets.count { it.active },
                checking = checking,
                guard = enterGuard,
                onCheckNow = { watchViewModel.checkNow() }
            )
        }
    }

    // ---- 对话框与弹层（与改造前同一批能力，一个没删） ----

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
                    // 同 SKU 历史最低价：Prefilled 本身就要求有曲线，这里仍按可空取，
                    // 不再依赖"上面已经 return 过"这种隐式非空（曲线区改成局部 if 后整页不再有 history 的 smart cast）。
                    val sameSkuLowest = history?.lowest ?: 0.0
                    if (prefill is TargetPrefill.Prefilled && sameSkuLowest > 0) {
                        Text(
                            stringResource(R.string.watch_target_same_sku_low, PriceFormatter.format(sameSkuLowest)),
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

    // 目标价修改（目标列表行 → 改价）：与「我的」页同一套校验口径
    editingTarget?.let { target ->
        EditTargetDialog(
            target = target,
            onDismiss = { editingTarget = null },
            onConfirm = { price ->
                watchViewModel.updateTargetPrice(target.productId, price)
                editingTarget = null
            }
        )
    }

    // 长按复制当前价只在有曲线时成立（入口就是曲线卡的长按）；这里显式判空，
    // 顺带让 history 在块内 smart cast 回非空。
    if (showSheet && history != null) {
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

/** 修改目标价弹窗（盯价页目标列表用；校验与「我的」页一致：>0 才可保存） */
@Composable
private fun EditTargetDialog(target: WatchTargetRowView, onDismiss: () -> Unit, onConfirm: (Double) -> Unit) {
    var text by remember(target.productId) {
        mutableStateOf(
            if (target.targetPrice % 1.0 == 0.0) target.targetPrice.toLong().toString() else target.targetPrice.toString()
        )
    }
    val parsed = text.toDoubleOrNull()
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
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = { Text(stringResource(R.string.watch_target_edit_hint)) }
                )
            }
        },
        confirmButton = {
            TextButton(enabled = parsed != null && parsed > 0, onClick = { parsed?.let(onConfirm) }) {
                Text(stringResource(R.string.price_watch_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        }
    )
}

/** 检查轮次实况：查到几个价、因何跳过几个目标，替代旧的"永远静默" */
@Composable
private fun WatchStatusCard(
    summary: WatchCheckRunner.RoundSummary?,
    untrackableCount: Int,
    activeTargetCount: Int,
    checking: Boolean,
    guard: EnterReplayGuard,
    onCheckNow: () -> Unit,
    enterKey: String = PriceEnterKeys.Status,
    enterIndex: Int = 6
) {
    // §2.4 区块入场：本卡排阶梯最后一级，只走 graphicsLayer
    PriceCard(
        modifier = Modifier
            .fillMaxWidth()
            .enterReveal(enterIndex, guard, enterKey)
    ) {
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
            // F1：只有"券后历史低价"参考值的目标已被判成本轮无现价（不发通知、不进曲线），
            // 这里补一行脚注说清真实成因，避免用户以为星罗通道给的是今天的价。
            if (summary.skipped.referenceOnly > 0) {
                Text(
                    stringResource(R.string.watch_status_reference_only, summary.skipped.referenceOnly),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
        // 0 个盯价目标时"立即检查一次"是无意义操作（文档 UX）：置灰并说清为什么
        val canCheck = activeTargetCount > 0
        if (!canCheck) {
            DisabledCheckButton(stringResource(R.string.watch_check_unavailable))
            Spacer(Modifier.height(Dims.SpacingXS))
            Text(
                stringResource(R.string.watch_check_needs_target),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            // 下次检查是"约"值：基于最近一轮 + 30 分钟轮询预算（WorkManager 不暴露确切触发时刻）
            val nextAt = WatchCountdown.nextCheckAt(summary?.atMillis)
            if (nextAt != null) {
                Text(
                    text = if (WatchCountdown.isDue(nextAt, System.currentTimeMillis())) {
                        stringResource(R.string.watch_next_check_soon)
                    } else {
                        stringResource(R.string.watch_next_check_at, NEXT_CHECK_FORMAT.format(Date(nextAt)))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(Dims.SpacingS))
            OutlinedButton(
                onClick = onCheckNow,
                enabled = !checking,
                modifier = Modifier.heightIn(min = Dims.TouchMin)
            ) {
                Text(stringResource(if (checking) R.string.watch_check_running else R.string.watch_check_now))
            }
        }
    }
}

/**
 * 「立即检查一次」的禁用态：画成按钮的样子，但不响应点击。
 *
 * 为什么不直接交给 `OutlinedButton(enabled = false)`：M3 的禁用态会把边框色再乘一次
 * 0.38 alpha，标签也压成淡灰，放在这张卡片里真机截图上它和上面那行说明文字长得一模一样
 * （2026-10-07 复核）——用户分不清"这是个按不动的按钮"还是"这就是一句话"，
 * 而那句话并不是他能做的动作。这里自己画边框：形状、描边、满不透明的次要字色三个通道都在。
 */
@Composable
private fun DisabledCheckButton(label: String) {
    val shape = MaterialTheme.shapes.small
    Row(
        modifier = Modifier
            .heightIn(min = Dims.TouchMin)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            // 读屏不能只看画出来的形状：可用态是 OutlinedButton，TalkBack 会报"按钮"；
            // 这里若只是一段 Text，盲人用户听到的是"一句话"，恰好丢掉"现在按不动"这层意思。
            .semantics {
                role = Role.Button
                // compose-ui 1.7.6 里 `disabled` 是**函数**（javap：static void
                // SemanticsPropertyReceiver.disabled()），不是 Boolean 属性——写成 `disabled = true`
                // 编译报 "Function invocation 'disabled()' expected"
                disabled()
            }
            .padding(horizontal = Dims.SpacingXL),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 图例色点（6dp 圆点）：与曲线虚线同色，把"最低/最高"文字和图上那两条线钉在一起。 */
@Composable
private fun LegendDot(color: Color) {
    Spacer(
        Modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(color)
    )
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

/** 「下次检查」时间格式（与盯价轮次同口径：HH:mm） */
private val NEXT_CHECK_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())

/**
 * §2.4 入场键：必须与数据无关且**稳定** —— 拿数据当 key 就等于"每次刷新都重播一次入场"
 * （[EnterReplayGuard] 只按 key 放行一次，键一变它又被当成首次，详见 domain/MotionEnter.kt）。
 * 列表项用条目自己的稳定标识（身份行 = [IdentityPrefix] + productId）。
 */
private object PriceEnterKeys {
    const val Content = "price:content"
    const val AutoTrack = "price:block:autoTrack"
    const val Protection = "price:block:protection"
    const val Curve = "price:block:curve"
    const val Watch = "price:block:watch"
    const val Targets = "price:block:targets"
    const val Status = "price:block:status"
    const val IdentityPrefix = "price:identity:"
}
