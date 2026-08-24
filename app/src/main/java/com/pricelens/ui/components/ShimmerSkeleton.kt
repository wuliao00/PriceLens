package com.pricelens.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.dp
import com.pricelens.ui.theme.Dims

/**
 * §2.3 骨架屏 shimmer：1500ms 循环、LinearEasing。
 * 动画值只通过 drawBehind 的绘制偏移走 draw 通道（§2.1 铁律），
 * 不驱动任何布局/组合属性（§2.2 禁止 background 动画陷阱）。
 */
@Composable
fun ShimmerSkeleton(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val shimmerX by transition.animateFloat(
        label = "shimmerX",
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    Row(modifier = modifier.height(Dims.ListItemHeight).padding(Dims.SpacingM)) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .drawShimmer(shimmerX)
        )
        Spacer(Modifier.width(Dims.SpacingM))
        Column(modifier = Modifier.align(Alignment.CenterVertically)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .height(16.dp)
                    .clip(MaterialTheme.shapes.small)
                    .drawShimmer(shimmerX)
            )
            Spacer(Modifier.height(Dims.SpacingM))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .height(12.dp)
                    .clip(MaterialTheme.shapes.small)
                    .drawShimmer(shimmerX)
            )
        }
    }
}

/** shimmer 高亮：drawBehind 在绘制阶段画底色 + 移动高光条，不触发重组 */
private fun Modifier.drawShimmer(progress: Float): Modifier =
    this.then(
        Modifier.drawBehind {
            drawRect(color = shimmerBase)
            val bandWidth = size.width
            val x = progress * size.width
            drawRect(
                color = shimmerHighlight,
                topLeft = androidx.compose.ui.geometry.Offset(x - bandWidth / 2, 0f),
                size = androidx.compose.ui.geometry.Size(bandWidth, size.height)
            )
        }
    )

private val shimmerBase = androidx.compose.ui.graphics.Color(0xFFE1E2EC)
private val shimmerHighlight = androidx.compose.ui.graphics.Color(0xFFF3F4F9)

@Composable
fun ShimmerList(items: Int = 4) {
    Column {
        repeat(items) {
            ShimmerSkeleton(modifier = Modifier.fillMaxWidth())
        }
    }
}
