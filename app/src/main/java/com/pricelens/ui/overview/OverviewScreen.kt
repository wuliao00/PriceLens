package com.pricelens.ui.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pricelens.R
import com.pricelens.data.remote.GwdangApi
import com.pricelens.data.remote.JdApi
import com.pricelens.data.remote.ManmanbuyApi
import com.pricelens.ui.common.AsyncValue
import com.pricelens.ui.common.valueOrDefault
import com.pricelens.ui.common.valueOrNull
import com.pricelens.ui.components.AppImage
import com.pricelens.ui.components.EmptyState
import com.pricelens.ui.components.PriceBadge
import com.pricelens.ui.components.PriceCard
import com.pricelens.ui.components.PriceRow
import com.pricelens.ui.components.ShimmerList
import com.pricelens.ui.components.SourceStatusRow
import com.pricelens.ui.theme.BadgeTone
import com.pricelens.ui.theme.Dims
import com.pricelens.util.PriceFormatter
import com.pricelens.util.PriceJudgment
import com.pricelens.util.UrlOpener

/**
 * §3.5 概览页：一屏内看到 当前价 / 历史最低价 / 是否有券 / 是否建议购买。
 * §2.5 列表固定高度 + 稳定结构，避免无效重组。
 *
 * 阶段4：顶部接 [SourceStatusRow] 展示各数据源真实状态；引导卡统一 [EmptyState]；
 * 数据源失败展示友好错误提示（旧数据仍兜底展示）。
 */
@Composable
fun OverviewScreen(searchViewModel: SearchViewModel, onGoBilibili: () -> Unit = {}) {
    val loading by searchViewModel.loading.collectAsStateWithLifecycle()
    val keyword by searchViewModel.keyword.collectAsStateWithLifecycle()
    val productAsync by searchViewModel.product.collectAsStateWithLifecycle()
    val historyAsync by searchViewModel.history.collectAsStateWithLifecycle()
    val judgment by searchViewModel.judgment.collectAsStateWithLifecycle()
    val couponsAsync by searchViewModel.coupons.collectAsStateWithLifecycle()
    val livePrice by searchViewModel.livePrice.collectAsStateWithLifecycle()
    val realtimeSource by searchViewModel.realtimeSource.collectAsStateWithLifecycle()

    // 适配数据源：AsyncValue → 渲染所需的纯值（Error 自动回退旧数据）
    val product = productAsync.valueOrNull()?.toJdProduct()
    val history = historyAsync.valueOrNull()
    val coupons = couponsAsync.valueOrDefault(emptyList())

    if (loading && product == null) {
        ShimmerList()
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Dims.SpacingXL)
    ) {
        item(key = "source_status") {
            SourceStatusRow(searchViewModel)
        }
        if (product == null) {
            item(key = "empty_title") {
                Spacer(Modifier.height(Dims.SpacingL))
                Text(
                    stringResource(R.string.overview_not_found, keyword),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item(key = "guide_acc") {
                val context = LocalContext.current
                Spacer(Modifier.height(Dims.SpacingL))
                EmptyState(
                    icon = Icons.Filled.Accessibility,
                    title = stringResource(R.string.overview_guide_acc_title),
                    desc = stringResource(R.string.overview_guide_acc_desc),
                    actionLabel = stringResource(R.string.overview_guide_acc_action),
                    onAction = {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        )
                    }
                )
            }
            item(key = "guide_link") {
                Spacer(Modifier.height(Dims.SpacingL))
                EmptyState(
                    icon = Icons.Filled.Link,
                    title = stringResource(R.string.overview_guide_link_title),
                    desc = stringResource(R.string.overview_guide_link_desc)
                )
            }
            item(key = "guide_bili") {
                Spacer(Modifier.height(Dims.SpacingL))
                EmptyState(
                    icon = Icons.Filled.OndemandVideo,
                    title = stringResource(R.string.overview_guide_bili_title),
                    desc = stringResource(R.string.overview_guide_bili_desc, keyword),
                    actionLabel = stringResource(R.string.overview_guide_bili_action),
                    onAction = onGoBilibili
                )
            }
        } else {
            // 数据源失败：友好提示，旧数据仍按 valueOrNull/valueOrDefault 兜底展示
            if (historyAsync is AsyncValue.Error<*> || couponsAsync is AsyncValue.Error<*>) {
                item(key = "error_hint") {
                    Spacer(Modifier.height(Dims.SpacingM))
                    EmptyState(
                        icon = Icons.Filled.Warning,
                        title = stringResource(R.string.error_load_failed),
                        desc = stringResource(R.string.error_retry_hint)
                    )
                }
            }
            item(key = "product") {
                Spacer(Modifier.height(Dims.SpacingM))
                ProductHeader(product, judgment, history, livePrice, realtimeSource)
            }
            item(key = "meta") { QuickFacts(history, coupons, judgment) }
        }
    }
}

@Composable
private fun ProductHeader(
    product: JdApi.JdProduct,
    judgment: PriceJudgment,
    history: ManmanbuyApi.History?,
    livePrice: Double?,
    realtimeSource: String?
) {
    val context = LocalContext.current
    PriceCard(
        modifier = Modifier.fillMaxWidth(),
        // 启动关联应用：点击商品卡 → 优先唤起京东/淘宝 App，未安装回退浏览器
        onClick = { UrlOpener.open(context, product.url) }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppImage(
                url = product.image,
                contentDescription = product.title,
                modifier = Modifier.size(80.dp)
            )
            Spacer(Modifier.size(Dims.SpacingM))
            Column {
                Text(
                    product.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(Dims.SpacingS))
                PriceRow(
                    current = product.price,
                    original = product.originalPrice,
                    badge = judgment.label.takeIf { history != null },
                    badgeTone = when (judgment) {
                        is PriceJudgment.LOW -> BadgeTone.POSITIVE
                        is PriceJudgment.SUSPICIOUS -> BadgeTone.NEGATIVE
                        else -> BadgeTone.NEUTRAL
                    }
                )
            }
        }
        // 本机账号实时价（无障碍读取的价格，即用户登录账号看到的价格）
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

/** §3.5 信息密度：历史最低 / 是否有券 / 建议购买 —— 一行三块 */
@Composable
private fun QuickFacts(history: ManmanbuyApi.History?, coupons: List<GwdangApi.Coupon>, judgment: PriceJudgment) {
    Spacer(Modifier.height(Dims.SpacingXL))
    Row(
        horizontalArrangement = Arrangement.spacedBy(Dims.SpacingM),
        modifier = Modifier.fillMaxWidth()
    ) {
        FactCard(
            stringResource(R.string.overview_fact_lowest),
            history?.let { PriceFormatter.format(it.lowest) }
                ?: stringResource(R.string.overview_no_data),
            Modifier.weight(1f)
        )
        FactCard(
            stringResource(R.string.overview_fact_coupon),
            coupons.maxByOrNull { it.amount }?.let {
                stringResource(R.string.overview_coupon_yuan, it.amount.toInt())
            } ?: stringResource(R.string.overview_no_coupon),
            Modifier.weight(1f)
        )
        FactCard(
            stringResource(R.string.overview_fact_advice),
            when (judgment) {
                is PriceJudgment.LOW -> stringResource(R.string.overview_advice_low)
                is PriceJudgment.SUSPICIOUS -> stringResource(R.string.overview_advice_suspicious)
                else -> stringResource(R.string.overview_advice_normal)
            },
            Modifier.weight(1f)
        )
    }
}

@Composable
private fun FactCard(label: String, value: String, modifier: Modifier = Modifier) {
    PriceCard(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Dims.SpacingS))
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
