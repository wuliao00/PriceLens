package com.pricelens.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextDecoration
import com.pricelens.ui.theme.BadgeTone
import com.pricelens.ui.theme.Dims
import com.pricelens.ui.theme.Elevations
import com.pricelens.ui.theme.MotionDurations
import com.pricelens.ui.theme.PriceLensEasing
import com.pricelens.ui.theme.PriceType
import com.pricelens.ui.theme.bg
import com.pricelens.ui.theme.fg
import com.pricelens.util.PriceFormatter

/**
 * 通用卡片（极简原则：全库卡片统一走此组件，消灭各屏私有卡片实现）。
 *
 * 深度原则落点：
 *  - 静置：tonal 色差 + [Elevations.CardRest] 轻阴影，仅建立层级
 *  - 按压：150ms 内缩 0.97（§2 铁律：仅 graphicsLayer 绘制通道）
 *  - 长按浮起：250ms scale 1.02 + 阴影升至 [Elevations.CardPressed]，无弹跳
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PriceCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var lifted by remember { mutableStateOf(false) }
    // 浮起后自动回落：长按反馈是短暂脉冲，不需要调用方手动复位
    LaunchedEffect(lifted) {
        if (lifted) {
            kotlinx.coroutines.delay(MotionDurations.Slow.toLong())
            lifted = false
        }
    }

    // §2 铁律：动画值只走 graphicsLayer（scale）与 Surface 阴影，不驱动布局
    val scale by animateFloatAsState(
        targetValue = when {
            lifted -> 1.02f
            pressed -> 0.97f
            else -> 1f
        },
        animationSpec = tween(MotionDurations.Fast, easing = PriceLensEasing),
        label = "cardPress"
    )
    val shadow by animateDpAsState(
        targetValue = if (lifted) Elevations.CardPressed else Elevations.CardRest,
        animationSpec = tween(MotionDurations.Standard, easing = PriceLensEasing),
        label = "cardElevate"
    )

    val gestureModifier = when {
        // 长按优先：只要声明了 onLongClick 就走 combinedClickable，
        // onClick 缺省时给空实现（仅浮起反馈，不触发导航）
        onLongClick != null -> Modifier.combinedClickable(
            interactionSource = interaction,
            indication = null,
            onClick = onClick ?: {},
            onLongClick = {
                lifted = true
                onLongClick()
            }
        )
        onClick != null -> Modifier.clickable(
            interactionSource = interaction,
            indication = null,
            onClick = onClick
        )
        else -> Modifier
    }

    Surface(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(gestureModifier),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = Elevations.CardRest,
        shadowElevation = shadow
    ) {
        Column(Modifier.padding(Dims.SpacingL)) { content() }
    }
}

/** §3.5 语义标签（"历史低价"/"先涨后降"）：labelSmall + 圆角背景 + 主题语义色 */
@Composable
fun PriceBadge(text: String, tone: BadgeTone, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = tone.bg()
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = tone.fg(),
            modifier = Modifier.padding(horizontal = Dims.SpacingS, vertical = Dims.SpacingXS)
        )
    }
}

/** §3.5 价格一行：当前价（PriceType 等宽数字）+ 原价划线 + 标签 */
@Composable
fun PriceRow(current: Double, original: Double?, badge: String?, badgeTone: BadgeTone = BadgeTone.POSITIVE) {
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(Dims.SpacingS)
    ) {
        Text(
            text = PriceFormatter.format(current),
            style = PriceType.PriceHero,
            color = MaterialTheme.colorScheme.primary
        )
        original?.takeIf { it > current }?.let {
            Text(
                text = PriceFormatter.format(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textDecoration = TextDecoration.LineThrough,
                modifier = Modifier.padding(bottom = Dims.SpacingXS)
            )
        }
        badge?.let {
            PriceBadge(it, badgeTone, modifier = Modifier.padding(bottom = Dims.SpacingS - Dims.SpacingXS))
        }
    }
}
