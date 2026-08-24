package com.pricelens.ui.coupon

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pricelens.data.remote.GwdangApi
import com.pricelens.ui.components.PriceCard
import com.pricelens.ui.components.ShimmerList
import com.pricelens.ui.main.MainUiState
import com.pricelens.ui.theme.Dims
import com.pricelens.ui.theme.SemanticColors
import com.pricelens.util.PriceFormatter
import kotlinx.coroutines.launch

/**
 * §6.3 找券 — 隐藏优惠券。
 * 到手价 countUp 500ms（§2.3 价格数字滚动）：数字滚动只改 Text 内容，
 * 缩放呼吸走 graphicsLayer（不触碰布局属性）。
 */
@Composable
fun CouponScreen(state: MainUiState) {
    if (state.coupons.isEmpty()) {
        if (state.loading) ShimmerList()
        else com.pricelens.ui.bilibili.EmptyHint("搜索后聚合购物党 / 京东联盟优惠券")
        return
    }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(Dims.SpacingXL)
        ) {
            item(key = "net_price") {
                NetPriceHeader(state.netPrice)
                Spacer(Modifier.height(Dims.SpacingL))
            }
            items(state.coupons, key = { "${it.amount}-${it.title}" }) { coupon ->
                CouponCard(coupon) {
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("coupon", coupon.title))
                    scope.launch { snackbar.showSnackbar("已复制券码") }
                }
                Spacer(Modifier.height(Dims.SpacingM))
            }
        }
        SnackbarHost(snackbar)
    }
}

/** 到手价大字 + 500ms countUp */
@Composable
private fun NetPriceHeader(netPrice: Double?) {
    var target by remember(netPrice) { mutableStateOf(0f) }
    LaunchedEffect(netPrice) { target = 1f }
    val progress by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(500),
        label = "netPriceCountUp"
    )
    val display = netPrice?.let { it * progress } ?: 0.0

        Row(verticalAlignment = Alignment.Bottom) {
            Text("到手价", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp))
            Spacer(Modifier.width(Dims.SpacingS))
        Text(
            PriceFormatter.format(display),
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = SemanticColors.Suspicious,
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
                "¥${coupon.amount.toInt()}",
                style = MaterialTheme.typography.titleLarge,
                color = SemanticColors.Suspicious,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(Dims.SpacingM))
            Column(Modifier.weight(1f)) {
                Text(coupon.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Text(
                    if (coupon.threshold > 0) "满 ${coupon.threshold.toInt()} 可用" else "无门槛",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(onClick = onCopy, shape = MaterialTheme.shapes.small) { Text("复制") }
        }
    }
}
