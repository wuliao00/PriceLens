package com.pricelens.ui.price

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.pricelens.ui.common.AsyncValue
import com.pricelens.ui.common.valueOrNull
import com.pricelens.ui.components.EmptyState
import com.pricelens.ui.components.PriceBadge
import com.pricelens.ui.components.PriceCard
import com.pricelens.ui.components.ShimmerList
import com.pricelens.ui.components.SkeletonCrossfade
import com.pricelens.ui.components.enterReveal
import com.pricelens.ui.overview.SearchViewModel
import com.pricelens.ui.theme.BadgeTone
import com.pricelens.ui.theme.Dims
import com.pricelens.util.PriceFormatter
import com.pricelens.util.PriceJudgment
import com.pricelens.worker.WatchCheckRunner
import com.pricelens.worker.WatchCountdown
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * §6.2 盯价 — 历史价格曲线：手写 Canvas、
 * 当前价脉冲点、最低/最高虚线、大促节点灰竖线；
 * 长按 → BottomSheet「复制当前价 / 导出图片」（长按同时触发卡片浮起）。
 * 阶段4：AsyncValue 三态渲染（加载骨架 / 空态引导 / 失败提示+旧数据兜底）。
 *
 * §2.4 丝滑动画（2026-10-02）：
 *  - 骨架→内容 200ms 交叉淡入（[SkeletonCrossfade]，只走 alpha）；
 *  - 区块/身份行按索引阶梯入场（[enterReveal]，alpha + translateY）；
 *  - 当前价数字滚动（[RollingPriceText] + domain/NumberRoll，tnum 等宽 + 占位格 → 宽度不抖）；
 *  - 曲线描线入场 350ms（[PriceChartCanvas] + 纯函数 [curveReveal]）；
 *  - 以上"播几次"由屏幕级 [EnterReplayGuard] 判，入场键见 [PriceEnterKeys]，只用稳定标识。
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
fun PriceScreen(searchViewModel: SearchViewModel, watchViewModel: PriceWatchViewModel, onOpenProduct: (String) -> Unit = {}) {
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

    // 只有"正在取历史"才整页骨架。**没有历史曲线绝不能提前 return**：
    // 盯价入口与「盯价检查状态」卡必须在没有曲线时照样出现——用户搜到的常常是
    // 当当/值得买候选（归属不到京东 SKU），旧实现在这里 return，于是这些商品
    // 永远看不到盯价入口，只看到一句"请先搜索商品"（真机 2026-09-28 实测）。
    if (historyAsync is AsyncValue.Loading<*> || (loading && productAsync.valueOrNull() == null)) {
        ShimmerList()
        return
    }

    // B9：盯价页卡片最多（曲线卡 + 盯价入口 + 检查状态卡），小屏/大字号下旧实现会直接画到屏幕外，
    // 且没滚动事件 → MainActivity 的 enterAlways 顶栏在这一 tab 永不收起。
    // 卡片数量有限，不必改 LazyColumn；图表面定高 200dp、无自定义手势，不与此纵向滚动争抢。
    // §2.4 骨架 → 内容：首次数据到达时交叉淡入（200ms，只走 alpha；防重播判据见 SkeletonCrossfade）
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
            // 失败但持有旧数据：顶部提示，曲线照常展示
            if (historyAsync is AsyncValue.Error<*>) {
                EmptyState(
                    icon = Icons.Filled.Warning,
                    title = stringResource(R.string.error_load_failed),
                    desc = stringResource(R.string.error_retry_hint)
                )
                Spacer(Modifier.height(Dims.SpacingM))
            }
            // 三种"没有曲线"的成因分开说，否则用户按提示去做的事是白做：
            //  ① 取历史失败（Error）② 还没搜过（关键词为空）
            //  ③ 搜过了但候选归属不到京东 SKU（SearchViewModel 此时把 history 置回 Idle）
            if (history == null) {
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
                    modifier = Modifier
                        .padding(vertical = Dims.SpacingXL)
                        .enterReveal(0, enterGuard, PriceEnterKeys.Curve)
                )
            } else {
                PriceCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .enterReveal(0, enterGuard, PriceEnterKeys.Curve),
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
            // 盯价入口：可盯 → 按钮；不可盯 → 明确写出原因（旧实现直接隐藏入口，用户以为功能坏了）
            if (watchable != null) {
                Button(
                    onClick = { showWatchDialog = true },
                    // 说明文字保持静态，只有按钮参加阶梯：它们是附属说明，不是一个列表项
                    modifier = Modifier
                        .fillMaxWidth()
                        .enterReveal(1, enterGuard, PriceEnterKeys.Watch)
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
            // §免凭证曲线：浮窗确认过的身份（本机自采曲线的管理入口；列表为空整节不出现，不留空占位）
            if (identities.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Dims.SpacingM)) {
                        Text(
                            stringResource(R.string.watch_identity_section_title),
                            style = MaterialTheme.typography.titleSmall
                        )
                        // 列表项入场（§2.4）：按行索引阶梯，key 用身份自己的 productId（稳定标识），
                        // 所以同一条目滚出滚回、或它的天数/标题刷新都不会重播一次
                        identities.forEachIndexed { index, identity ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                // §十一 盯价目标行：点整行打开这件商品的详情页（先按标题搜一轮，再盖详情页）
                                modifier = Modifier
                                    .padding(top = Dims.SpacingS)
                                    .enterReveal(index, enterGuard, PriceEnterKeys.IdentityPrefix + identity.productId)
                                    .clickable { onOpenProduct(identity.title) }
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
                Spacer(Modifier.height(Dims.SpacingM))
            }
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
                    // 不再依赖"上面已经 return 过"这种隐式非空（曲线区改成局部 if 后
                    // 整页不再有 history 的 smart cast）。
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
    enterIndex: Int = 2
) {
    // §2.4 区块入场：本卡排阶梯第 3 级（enterIndex=2 → 80ms 起播），只走 graphicsLayer
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
 * 0.38 alpha，标签也压成淡灰，放在这张 `surfaceVariant` 底色的卡片里，真机截图上
 * 它和上面那行说明文字长得一模一样（2026-10-07 复核）——用户分不清"这是个按不动的按钮"
 * 还是"这就是一句话"，而那句话并不是他能做的动作。
 * 这里自己画边框：形状、描边、满不透明的次要字色三个通道都在，一眼读得出
 * "控件在这儿，现在不可用"。高度与可用态用同一个 [Dims.TouchMin]，避免首次添加目标时
 * 这块区域上下跳一截。
 */
@Composable
private fun DisabledCheckButton(label: String) {
    val shape = MaterialTheme.shapes.small
    Row(
        modifier = Modifier
            .heightIn(min = Dims.TouchMin)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            // 读屏不能只看画出来的形状：可用态是 OutlinedButton，TalkBack 会报"按钮"；
            // 这里若只是一段 Text，盲人用户听到的是"一句话"，恰好丢掉了我刚用视觉补上的
            // 那层"这是个现在按不了的控件"。role + disabled 两个语义位把它补回来。
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

/** 「下次检查」时间格式（与盯价轮次同口径：HH:mm） */
private val NEXT_CHECK_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())

/**
 * §2.4 入场键：必须与数据无关且**稳定** —— 拿数据当 key 就等于"每次刷新都重播一次入场"
 * （[EnterReplayGuard] 只按 key 放行一次，键一变它又被当成首次，详见 domain/MotionEnter.kt）。
 * 列表项用条目自己的稳定标识（身份行 = [IdentityPrefix] + productId）。
 */
private object PriceEnterKeys {
    const val Content = "price:content"
    const val Curve = "price:block:curve"
    const val Watch = "price:block:watch"
    const val Status = "price:block:status"
    const val IdentityPrefix = "price:identity:"
}
