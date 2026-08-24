package com.pricelens.ui.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pricelens.data.remote.JdApi
import com.pricelens.data.remote.ManmanbuyApi
import com.pricelens.ui.components.BadgeTone
import com.pricelens.ui.components.PriceBadge
import com.pricelens.ui.components.PriceCard
import com.pricelens.ui.components.PriceRow
import com.pricelens.ui.components.ShimmerList
import com.pricelens.ui.main.MainUiState
import com.pricelens.ui.theme.Dims
import com.pricelens.util.PriceFormatter

/**
 * §3.5 概览页：一屏内看到 当前价 / 历史最低价 / 是否有券 / 是否建议购买。
 * §2.5 列表固定高度 + 稳定结构，避免无效重组。
 */
@Composable
fun OverviewScreen(state: MainUiState, onGoBilibili: () -> Unit = {}) {
    if (state.loading && state.product == null) {
        ShimmerList(); return
    }
    val product = state.product
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Dims.SpacingXL)
    ) {
        if (product == null) {
            item(key = "empty_title") {
                Text(
                    "未找到「${state.keyword}」的比价数据",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item(key = "guide_acc") {
                val context = androidx.compose.ui.platform.LocalContext.current
                GuideCard(
                    title = "推荐：无障碍自动比价",
                    desc = "直接打开京东/淘宝/拼多多商品页，PriceLens 用本机登录账号自动识别实时价并弹出比价浮窗，数据最准。",
                    actionLabel = "去开启"
                ) {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    )
                }
            }
            item(key = "guide_link") {
                GuideCard(
                    title = "粘贴商品链接",
                    desc = "复制京东/淘宝/拼多多的商品链接，粘贴到上方搜索框，直接查看历史价格与优惠券。",
                    actionLabel = null,
                    onAction = null
                )
            }
            item(key = "guide_bili") {
                GuideCard(
                    title = "查看B站评测",
                    desc = "B站真实用户评测不受商品数据源限制，搜索「${state.keyword}」看看大家的实际体验。",
                    actionLabel = "切换到 B站",
                    onAction = onGoBilibili
                )
            }
        } else {
            item(key = "product") { ProductHeader(product, state) }
            item(key = "meta") { QuickFacts(state) }
        }
    }
}

/** 空状态引导卡：图标 + 标题 + 说明 + 可选动作 */
@Composable
private fun GuideCard(
    title: String,
    desc: String,
    actionLabel: String?,
    onAction: (() -> Unit)? = null
) {
    Spacer(Modifier.height(Dims.SpacingL))
    androidx.compose.material3.Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(Dims.SpacingL)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(Dims.SpacingS))
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(Dims.SpacingS))
                androidx.compose.material3.TextButton(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun ProductHeader(product: JdApi.JdProduct, state: MainUiState) {
    val context = androidx.compose.ui.platform.LocalContext.current
    PriceCard(
        modifier = Modifier.fillMaxWidth(),
        // 启动关联应用：点击商品卡 → 优先唤起京东/淘宝 App，未安装回退浏览器
        onClick = { com.pricelens.util.UrlOpener.open(context, product.url) }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            com.pricelens.ui.components.AppImage(
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
                    badge = state.judgment.label.takeIf { state.history != null },
                    badgeTone = when (state.judgment) {
                        is com.pricelens.util.PriceJudgment.LOW -> BadgeTone.POSITIVE
                        is com.pricelens.util.PriceJudgment.SUSPICIOUS -> BadgeTone.NEGATIVE
                        else -> BadgeTone.NEUTRAL
                    }
                )
            }
        }
        // 本机账号实时价（无障碍读取的价格，即用户登录账号看到的价格）
        state.livePrice?.let { live ->
            Spacer(Modifier.height(Dims.SpacingS))
            PriceBadge(
                "${state.realtimeSource ?: "本机账号"} · 实时价 ¥" +
                    com.pricelens.util.PriceFormatter.formatRaw(live),
                BadgeTone.POSITIVE
            )
        }
    }
}

/** §3.5 信息密度：历史最低 / 是否有券 / 建议购买 —— 一行三块 */
@Composable
private fun QuickFacts(state: MainUiState) {
    Spacer(Modifier.height(Dims.SpacingXL))
    Row(
        horizontalArrangement = Arrangement.spacedBy(Dims.SpacingM),
        modifier = Modifier.fillMaxWidth()
    ) {
        FactCard("历史最低", state.history?.let { PriceFormatter.format(it.lowest) } ?: "—", Modifier.weight(1f))
        FactCard(
            "优惠券",
            state.coupons.maxByOrNull { it.amount }?.let { "${it.amount.toInt()} 元" } ?: "无",
            Modifier.weight(1f)
        )
        FactCard(
            "建议",
            when (state.judgment) {
                is com.pricelens.util.PriceJudgment.LOW -> "可入"
                is com.pricelens.util.PriceJudgment.SUSPICIOUS -> "观望"
                else -> "常态"
            },
            Modifier.weight(1f)
        )
    }
}

@Composable
private fun FactCard(label: String, value: String, modifier: Modifier = Modifier) {
    PriceCard(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Dims.SpacingS))
        Text(value, style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary)
    }
}
