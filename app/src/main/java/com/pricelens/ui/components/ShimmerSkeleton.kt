package com.pricelens.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
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

    // §3.5 shimmer 两色从 colorScheme 派生：亮色向白提亮、暗色向 surface 提亮，
    // 保证两主题下骨架与高光都有可辨识的对比
    val darkTheme = isSystemInDarkTheme()
    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight = if (darkTheme) {
        lerp(base, MaterialTheme.colorScheme.surface, 0.35f)
    } else {
        lerp(base, Color.White, 0.16f)
    }

    Row(modifier = modifier.height(Dims.ListItemHeight).padding(Dims.SpacingM)) {
        Box(
            modifier = Modifier
                .size(Dims.ListItemHeight - Dims.SpacingXXL)
                .clip(CircleShape)
                .drawShimmer(shimmerX, base, highlight)
        )
        Spacer(Modifier.width(Dims.SpacingM))
        Column(modifier = Modifier.align(Alignment.CenterVertically)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .height(Dims.SpacingL)
                    .clip(MaterialTheme.shapes.small)
                    .drawShimmer(shimmerX, base, highlight)
            )
            Spacer(Modifier.height(Dims.SpacingM))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .height(Dims.SpacingM)
                    .clip(MaterialTheme.shapes.small)
                    .drawShimmer(shimmerX, base, highlight)
            )
        }
    }
}

/** shimmer 高亮：drawBehind 在绘制阶段画底色 + 移动高光条，不触发重组 */
private fun Modifier.drawShimmer(progress: Float, base: Color, highlight: Color): Modifier = this.then(
    Modifier.drawBehind {
        drawRect(color = base)
        val bandWidth = size.width
        val x = progress * size.width
        drawRect(
            color = highlight,
            topLeft = androidx.compose.ui.geometry.Offset(x - bandWidth / 2, 0f),
            size = androidx.compose.ui.geometry.Size(bandWidth, size.height)
        )
    }
)

@Composable
fun ShimmerList(items: Int = 4) {
    Column {
        repeat(items) {
            ShimmerSkeleton(modifier = Modifier.fillMaxWidth())
        }
    }
}
