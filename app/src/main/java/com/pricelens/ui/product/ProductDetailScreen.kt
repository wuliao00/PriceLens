package com.pricelens.ui.product

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pricelens.R
import com.pricelens.data.local.entity.PriceTargetEntity
import com.pricelens.domain.PrefillReason
import com.pricelens.domain.PriceAdvice
import com.pricelens.domain.RejectReason
import com.pricelens.domain.TargetPrefill
import com.pricelens.domain.WatchDecision
import com.pricelens.domain.WatchTargetPolicy
import com.pricelens.ui.common.AsyncValue
import com.pricelens.ui.common.valueOrNull
import com.pricelens.ui.components.AppImage
import com.pricelens.ui.components.EmptyState
import com.pricelens.ui.components.PriceBadge
import com.pricelens.ui.components.PriceCard
import com.pricelens.ui.components.SourceStatusRow
import com.pricelens.ui.overview.SearchViewModel
import com.pricelens.ui.price.PriceChartCanvas
import com.pricelens.ui.price.PriceWatchViewModel
import com.pricelens.ui.price.adviceStringRes
import com.pricelens.ui.theme.BadgeTone
import com.pricelens.ui.theme.Dims
import com.pricelens.ui.theme.LocalSemanticColors
import com.pricelens.ui.theme.MotionDurations
import com.pricelens.ui.theme.PriceLensEasing
import com.pricelens.ui.theme.PriceType
import com.pricelens.util.PriceFormatter
import com.pricelens.util.UrlOpener

/**
 * §十一 商品详情页（底部导航 6→4 的落地形态）：一屏之内把「价格 / 找券 / 评测」三段
 * 装进来，取代原来的 B站 tab 与找券 tab。
 *
 *  - 导航沿用 `MainActivity` 既有的全屏覆盖路由（与 showSettings / showKeepAlive 同一套机制），
 *    不引入 Navigation 库；
 *  - 数据一律复用 [SearchViewModel] 已持有的本轮状态（product / history / advice / coupons /
 *    videos / netPrice / livePrice / posts / shihuo）——本页只重新排版，不新建数据层；
 *  - 三段内容各自成模块：价格 = 本页 [ProductPriceSection]（曲线只调用 [PriceChartCanvas]，
 *    不改它一行）、找券 = [ProductCouponSection]、评测 = [BiliReviewSection]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductDetailScreen(searchViewModel: SearchViewModel, onBack: () -> Unit) {
    // Activity 作用域的盯价 VM：MainActivity 里 hiltViewModel() 拿到的就是同一个实例，
    // 所以这里改目标价，「盯价」页与后台轮次看到的是同一份数据。
    val watchViewModel: PriceWatchViewModel = hiltViewModel()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.product_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                }
            )
        }
    ) { inner ->
        Column(
            Modifier
                .padding(inner)
                .fillMaxSize()
        ) {
            ProductHeadCard(searchViewModel)
            var section by rememberSaveable { mutableStateOf(ProductSection.PRICE) }
            SectionTabRow(current = section, onSelect = { section = it })
            AnimatedContent(
                targetState = section,
                transitionSpec = {
                    // §2 铁律：只做淡入淡出 + 水平微位移、标准时长，绝不 bounce/overshoot
                    val fade = tween<Float>(MotionDurations.Standard, easing = PriceLensEasing)
                    val shift = tween<IntOffset>(MotionDurations.Standard, easing = PriceLensEasing)
                    (fadeIn(fade) + slideInHorizontally(shift) { it / 10 })
                        .togetherWith(fadeOut(fade) + slideOutHorizontally(shift) { -it / 10 })
                },
                label = "productSection",
                modifier = Modifier.weight(1f)
            ) { target ->
                when (target) {
                    ProductSection.PRICE -> ProductPriceSection(searchViewModel, watchViewModel)
                    ProductSection.COUPON -> ProductCouponSection(searchViewModel)
                    ProductSection.REVIEW -> BiliReviewSection(searchViewModel)
                }
            }
        }
    }
}

/** 详情页三段模块（枚举序即展示序） */
enum class ProductSection { PRICE, COUPON, REVIEW }

/** 「找券」沿用 tab_coupon：6→4 之后它不再出现在底部导航，改出现在这里的分段标签上 */
private fun ProductSection.labelRes(): Int = when (this) {
    ProductSection.PRICE -> R.string.product_tab_price
    ProductSection.COUPON -> R.string.tab_coupon
    ProductSection.REVIEW -> R.string.product_tab_review
}

@Composable
private fun SectionTabRow(current: ProductSection, onSelect: (ProductSection) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dims.SpacingXL, vertical = Dims.SpacingS)
    ) {
        ProductSection.entries.forEachIndexed { index, item ->
            if (index > 0) Spacer(Modifier.width(Dims.SpacingS))
            SectionChip(
                label = stringResource(item.labelRes()),
                selected = item == current,
                modifier = Modifier.weight(1f)
            ) { onSelect(item) }
        }
    }
}

@Composable
private fun SectionChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(vertical = Dims.SpacingS)
        )
    }
}

/**
 * 顶部商品头卡：商品图 / 标题 / 现价 / 历史最低 / 购买建议徽章。
 * 建议文案直接走 [adviceStringRes]（口径唯一在 `ui/price/PriceAdviceCopy.kt`，它是 internal
 * 同模块可见 → 直接调用，不复制第二份映射）；UNKNOWN 不摆徽章（与「盯价」页同规矩）。
 */
@Composable
private fun ProductHeadCard(searchViewModel: SearchViewModel) {
    val product by searchViewModel.product.collectAsStateWithLifecycle()
    val history by searchViewModel.history.collectAsStateWithLifecycle()
    val advice by searchViewModel.advice.collectAsStateWithLifecycle()
    val percentile by searchViewModel.advicePercentile.collectAsStateWithLifecycle()
    val livePrice by searchViewModel.livePrice.collectAsStateWithLifecycle()
    val realtimeSource by searchViewModel.realtimeSource.collectAsStateWithLifecycle()
    val candidate = product.valueOrNull()
    val curve = history.valueOrNull()
    val context = LocalContext.current

    Column(Modifier.padding(horizontal = Dims.SpacingXL)) {
        if (candidate == null) {
            EmptyState(
                icon = Icons.Filled.QueryStats,
                title = stringResource(R.string.empty_search_first),
                desc = stringResource(R.string.search_start_hint),
                modifier = Modifier.padding(vertical = Dims.SpacingS)
            )
        } else {
            PriceCard(
                modifier = Modifier.fillMaxWidth(),
                // 点卡片 = 走既有的"唤起电商 App，未装回落浏览器"；看详情已经在这页里了
                onClick = { UrlOpener.open(context, candidate.url) }
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    AppImage(
                        url = candidate.image,
                        contentDescription = candidate.title,
                        modifier = Modifier.size(88.dp)
                    )
                    Spacer(Modifier.width(Dims.SpacingM))
                    Column(Modifier.weight(1f)) {
                        Text(
                            candidate.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(Dims.SpacingS))
                        if (candidate.price > 0) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    PriceFormatter.format(candidate.price),
                                    style = PriceType.PriceHero,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(Dims.SpacingS))
                                Text(
                                    stringResource(R.string.product_current_price),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = Dims.SpacingS)
                                )
                            }
                        } else {
                            Text(
                                stringResource(R.string.product_price_unknown),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            if (curve != null && curve.lowest > 0) {
                                stringResource(R.string.product_history_lowest, PriceFormatter.format(curve.lowest))
                            } else {
                                stringResource(R.string.product_history_none)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // 本机账号实时价（含会员价）与网络报价并列显示，不互相冒充
                        livePrice?.let { live ->
                            Spacer(Modifier.height(Dims.SpacingS))
                            PriceBadge(
                                stringResource(
                                    R.string.overview_live_price,
                                    realtimeSource ?: stringResource(R.string.overview_live_default_source),
                                    PriceFormatter.formatRaw(live)
                                ),
                                BadgeTone.POSITIVE
                            )
                        }
                    }
                }
                val shown = advice?.takeIf { it != PriceAdvice.Advice.UNKNOWN }
                if (shown != null) {
                    Spacer(Modifier.height(Dims.SpacingM))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PriceBadge(
                            stringResource(adviceStringRes(shown)),
                            when (shown) {
                                PriceAdvice.Advice.HIST_LOW, PriceAdvice.Advice.GOOD -> BadgeTone.POSITIVE
                                PriceAdvice.Advice.HIGH -> BadgeTone.NEGATIVE
                                else -> BadgeTone.NEUTRAL
                            }
                        )
                        percentile?.let { pct ->
                            Spacer(Modifier.width(Dims.SpacingS))
                            Text(
                                stringResource(R.string.product_advice_percentile, pct),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 价格段：来源状态 + 历史曲线 + 各源报价 + 盯价目标区。
 * 能不能盯、以哪个平台 + 商品 ID 盯，一律问 [WatchTargetPolicy]，本页不另判一套。
 */
@Composable
private fun ProductPriceSection(searchViewModel: SearchViewModel, watchViewModel: PriceWatchViewModel) {
    val product by searchViewModel.product.collectAsStateWithLifecycle()
    val historyAsync by searchViewModel.history.collectAsStateWithLifecycle()
    val netPrice by searchViewModel.netPrice.collectAsStateWithLifecycle()
    val livePrice by searchViewModel.livePrice.collectAsStateWithLifecycle()
    val realtimeSource by searchViewModel.realtimeSource.collectAsStateWithLifecycle()
    val keyword by searchViewModel.keyword.collectAsStateWithLifecycle()
    val targets by watchViewModel.watchTargets.collectAsStateWithLifecycle()
    val candidate = product.valueOrNull()
    val history = historyAsync.valueOrNull()

    val decision = remember(candidate?.skuId, candidate?.url) {
        WatchTargetPolicy.decide(candidate?.skuId, candidate?.url)
    }
    val watchable = decision as? WatchDecision.Watchable
    val target = watchable?.let { w -> targets.firstOrNull { it.productId == w.productId } }
    // 预填依据：曲线确实属于这个 SKU 才敢拿历史价当默认值（与「盯价」页同一个函数）
    val prefill = remember(watchable?.externalId, keyword, history, candidate?.url) {
        if (watchable == null || history == null) {
            TargetPrefill.NeedsManual(PrefillReason.NO_HISTORY)
        } else {
            WatchTargetPolicy.prefillTargetPrice(
                skuId = watchable.externalId,
                keyword = keyword,
                candidateUrl = candidate?.url,
                historyCurrent = history.current,
                historyLowest = history.lowest
            )
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Dims.SpacingXL)
    ) {
        SourceStatusRow(searchViewModel)
        Spacer(Modifier.height(Dims.SpacingL))
        if (history == null) {
            // 三种"没有曲线"的成因分开说（沿用「盯价」页口径：失败 / 搜了但归属不到 SKU / 没搜过）
            val failed = historyAsync is AsyncValue.Error<*>
            val searched = keyword.isNotBlank()
            EmptyState(
                icon = Icons.Filled.QueryStats,
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
                modifier = Modifier.padding(vertical = Dims.SpacingL)
            )
        } else {
            PriceCard(modifier = Modifier.fillMaxWidth()) {
                val only = history.points.firstOrNull()
                if (history.points.size < 2) {
                    // 点没攒够时不画那块 200dp 的空白框（空框看起来像"又显示错了"）
                    Text(
                        text = if (only == null) {
                            stringResource(R.string.watch_curve_source_none)
                        } else {
                            stringResource(
                                R.string.watch_curve_one_point,
                                only.date,
                                PriceFormatter.format(only.price)
                            )
                        },
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
                        stringResource(R.string.product_history_lowest, PriceFormatter.format(history.lowest)),
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
        }

        Spacer(Modifier.height(Dims.SpacingL))
        PriceCard(modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.product_quotes_title), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(Dims.SpacingS))
            QuoteRow(
                stringResource(R.string.product_quote_candidate),
                candidate?.takeIf { it.price > 0 }?.let { PriceFormatter.format(it.price) }
                    ?: stringResource(R.string.product_quote_none)
            )
            QuoteRow(
                stringResource(R.string.product_quote_history),
                history?.takeIf { it.current > 0 }?.let { PriceFormatter.format(it.current) }
                    ?: stringResource(R.string.product_quote_none)
            )
            QuoteRow(
                stringResource(R.string.product_quote_live),
                livePrice?.let {
                    PriceFormatter.formatRaw(it) + " · " +
                        (realtimeSource ?: stringResource(R.string.overview_live_default_source))
                } ?: stringResource(R.string.product_quote_none)
            )
            QuoteRow(
                stringResource(R.string.product_quote_net),
                netPrice?.let { PriceFormatter.format(it) } ?: stringResource(R.string.product_quote_none)
            )
        }

        Spacer(Modifier.height(Dims.SpacingL))
        WatchTargetBlock(
            watchViewModel = watchViewModel,
            watchable = watchable,
            target = target,
            // 默认值只在"曲线确实属于这个 SKU"时才给（与「盯价」页同一判据）；依据不足就留空让用户填
            seedPrice = (prefill as? TargetPrefill.Prefilled)?.price ?: 0.0,
            title = candidate?.title.orEmpty(),
            rejectedText = (decision as? WatchDecision.Rejected)?.let { rejectionText(it) }
        )
    }
}

@Composable
private fun QuoteRow(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dims.SpacingXS)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 不可盯的原因（与「盯价」页同一批字符串、同一条判据，只是没有 Toast 只有静置文本） */
@Composable
private fun rejectionText(rejected: WatchDecision.Rejected): String {
    val platform = stringResource(platformLabelRes(rejected.platform))
    return when (rejected.reason) {
        RejectReason.MISSING_ID -> stringResource(R.string.watch_reason_missing_id)
        RejectReason.MALFORMED_ID -> stringResource(R.string.watch_reason_malformed_id, rejected.displayId)
        RejectReason.PLATFORM_CONFLICT ->
            stringResource(R.string.watch_reason_platform_conflict, platform, rejected.displayId)
        RejectReason.NO_PRICE_CHANNEL -> if (rejected.displayId.isBlank()) {
            stringResource(R.string.watch_reason_no_channel_no_id, platform)
        } else {
            stringResource(R.string.watch_reason_no_channel, platform, rejected.displayId)
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

/**
 * 盯价目标区：设/改目标价、暂停与恢复、删除。
 * 写操作全走 [PriceWatchViewModel] 既有的 `setTarget / updateTargetPrice /
 * resumeTarget(→activateTarget) / pauseTarget / deleteTarget`，本页不碰 DAO、不复制校验口径。
 */
@Composable
private fun WatchTargetBlock(
    watchViewModel: PriceWatchViewModel,
    watchable: WatchDecision.Watchable?,
    target: PriceTargetEntity?,
    seedPrice: Double,
    title: String,
    rejectedText: String?
) {
    val semantic = LocalSemanticColors.current
    val errorColor = MaterialTheme.colorScheme.error
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }

    PriceCard(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.product_watch_title), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(Dims.SpacingS))
        when {
            watchable == null -> Text(
                rejectedText ?: stringResource(R.string.product_watch_no_channel),
                style = MaterialTheme.typography.bodySmall,
                color = errorColor
            )
            target == null -> {
                Text(
                    stringResource(R.string.product_watch_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Dims.SpacingS))
                TextButton(onClick = {
                    draft = seedPrice.takeIf { it > 0 }?.toInt()?.toString().orEmpty()
                    editing = true
                }) { Text(stringResource(R.string.product_watch_button)) }
            }
            else -> {
                Text(
                    stringResource(R.string.product_watch_target_price, PriceFormatter.format(target.targetPrice)),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (!target.active) {
                    Text(
                        stringResource(R.string.product_watch_paused),
                        style = MaterialTheme.typography.labelSmall,
                        color = semantic.neutral
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Dims.SpacingXS)) {
                    TextButton(onClick = {
                        draft = target.targetPrice.toInt().toString()
                        editing = true
                    }) { Text(stringResource(R.string.product_watch_edit)) }
                    TextButton(
                        onClick = {
                            if (target.active) {
                                watchViewModel.pauseTarget(target.productId)
                            } else {
                                watchViewModel.resumeTarget(target.productId)
                            }
                        }
                    ) {
                        Text(
                            stringResource(
                                if (target.active) R.string.product_watch_pause else R.string.product_watch_resume
                            )
                        )
                    }
                    TextButton(onClick = { watchViewModel.deleteTarget(target.productId) }) {
                        Text(stringResource(R.string.product_watch_delete), color = errorColor)
                    }
                }
            }
        }
        Text(
            stringResource(R.string.product_watch_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (editing) {
            Spacer(Modifier.height(Dims.SpacingS))
            val parsed = draft.toDoubleOrNull()
            val invalid = draft.isNotBlank() && (parsed == null || parsed <= 0.0)
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                isError = invalid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                supportingText = {
                    Text(
                        stringResource(
                            if (invalid) R.string.product_watch_invalid else R.string.product_watch_target_label
                        )
                    )
                }
            )
            Row {
                TextButton(
                    onClick = {
                        val price = parsed
                        if (price != null && price > 0 && watchable != null) {
                            if (target == null) {
                                watchViewModel.setTarget(watchable, title, price)
                            } else {
                                watchViewModel.updateTargetPrice(target.productId, price)
                            }
                            editing = false
                        }
                    }
                ) { Text(stringResource(R.string.product_watch_save)) }
                TextButton(onClick = { editing = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        }
    }
}
