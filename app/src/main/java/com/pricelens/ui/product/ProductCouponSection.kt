package com.pricelens.ui.product

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pricelens.R
import com.pricelens.data.remote.GwdangApi
import com.pricelens.ui.common.AsyncValue
import com.pricelens.ui.common.valueOrDefault
import com.pricelens.ui.components.EmptyState
import com.pricelens.ui.components.PriceCard
import com.pricelens.ui.components.ShimmerList
import com.pricelens.ui.overview.SearchViewModel
import com.pricelens.ui.theme.Dims
import com.pricelens.ui.theme.LocalSemanticColors
import com.pricelens.ui.theme.PriceType
import com.pricelens.util.PriceFormatter
import kotlinx.coroutines.launch

/**
 * §十一 详情页「找券」段。
 *
 * 原 `ui/coupon/CouponScreen.kt` 的渲染整体搬进详情页（底部导航 6→4 之后找券不再是独立 tab，
 * 顶层页文件删除，不留两份平行实现），三条既有口径原样保留：
 *  - 券源只展示爆料里显式写出的券，不做价差反推；
 *  - 有券但都没到门槛 → 如实说"暂无可算的到手价"，不显示误导性的 ¥0；
 *  - 到手价 countUp 走 graphicsLayer alpha（§2.1 铁律），500ms 属 §2.3 的历史例外时长。
 */
@Composable
fun ProductCouponSection(searchViewModel: SearchViewModel) {
    val loading by searchViewModel.loading.collectAsStateWithLifecycle()
    val couponsAsync by searchViewModel.coupons.collectAsStateWithLifecycle()
    val netPrice by searchViewModel.netPrice.collectAsStateWithLifecycle()
    val coupons = couponsAsync.valueOrDefault(emptyList())

    when {
        couponsAsync is AsyncValue.Loading<*> || (loading && coupons.isEmpty()) -> {
            ShimmerList()
            return
        }
        couponsAsync is AsyncValue.Error<*> -> {
            // 失败：友好提示；有旧数据仍展示
            Column(Modifier.fillMaxSize().padding(Dims.SpacingXL)) {
                EmptyState(
                    icon = Icons.Filled.Warning,
                    title = stringResource(R.string.error_load_failed),
                    desc = stringResource(R.string.error_retry_hint)
                )
                if (coupons.isEmpty()) return
                Spacer(Modifier.height(Dims.SpacingL))
            }
        }
        coupons.isEmpty() -> {
            // 搜索后也可能"确实没有券"（券源只展示显式券文案，不做价差反推）
            val searched = searchViewModel.keyword.collectAsStateWithLifecycle().value.isNotBlank()
            EmptyState(
                icon = Icons.Filled.ConfirmationNumber,
                title = stringResource(
                    if (searched) R.string.coupon_empty_title else R.string.empty_search_first
                ),
                desc = stringResource(R.string.coupon_empty_hint),
                modifier = Modifier.padding(Dims.SpacingXL)
            )
            return
        }
    }

    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(Dims.SpacingXL),
            modifier = Modifier.weight(1f)
        ) {
            // 委托属性无法智能转换，先取本地值（同时避免 !!）
            val applicableNet = netPrice
            if (applicableNet != null) {
                item(key = "net_price") {
                    NetPriceHeader(applicableNet)
                    Spacer(Modifier.height(Dims.SpacingL))
                }
            } else if (coupons.isNotEmpty()) {
                // 有券但都不适用于当前商品价（未达门槛）：如实说明，不再显示误导性的 ¥0
                item(key = "net_price_hint") {
                    Text(
                        stringResource(R.string.coupon_not_applicable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(Dims.SpacingL))
                }
            }
            itemsIndexed(coupons, key = { index, coupon -> "cpn:${index}_${coupon.amount}-${coupon.title}" }) { _, coupon ->
                CouponCard(coupon) {
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("coupon", coupon.title))
                    scope.launch { snackbar.showSnackbar(context.getString(R.string.coupon_copied)) }
                }
                Spacer(Modifier.height(Dims.SpacingM))
            }
        }
        SnackbarHost(snackbar)
    }
}

/** 到手价大字（PriceType.PriceHero 等宽数字）+ countUp */
@Composable
private fun NetPriceHeader(netPrice: Double) {
    var target by remember(netPrice) { mutableStateOf(0f) }
    LaunchedEffect(netPrice) { target = 1f }
    val progress by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(500),
        label = "netPriceCountUp"
    )
    val display = netPrice * progress

    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            stringResource(R.string.coupon_net_price),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = Dims.SpacingS)
        )
        Spacer(Modifier.width(Dims.SpacingS))
        Text(
            PriceFormatter.format(display),
            style = PriceType.PriceHero,
            color = LocalSemanticColors.current.suspicious,
            modifier = Modifier.graphicsLayer {
                // §2.1：动画值只影响绘制通道
                alpha = 0.4f + 0.6f * progress
            }
        )
    }
}

@Composable
private fun CouponCard(coupon: GwdangApi.Coupon, onCopy: () -> Unit) {
    PriceCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.coupon_amount, coupon.amount.toInt()),
                style = PriceType.PriceLarge,
                color = LocalSemanticColors.current.suspicious
            )
            Spacer(Modifier.width(Dims.SpacingM))
            Column(Modifier.weight(1f)) {
                Text(coupon.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Text(
                    if (coupon.threshold > 0) {
                        stringResource(R.string.coupon_threshold, coupon.threshold.toInt())
                    } else {
                        stringResource(R.string.coupon_no_threshold)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(onClick = onCopy, shape = MaterialTheme.shapes.small) {
                Text(stringResource(R.string.coupon_copy))
            }
        }
    }
}
