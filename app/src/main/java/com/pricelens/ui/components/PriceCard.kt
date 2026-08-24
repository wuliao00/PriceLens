package com.pricelens.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pricelens.ui.theme.Dims
import com.pricelens.ui.theme.SemanticColors
import com.pricelens.util.PriceFormatter

/**
 * §3.4 极简卡片：无粗边框、无投影，靠 tonal elevation 色差分层。
 * §2.3 按压反馈：100ms spring(stiffness=400)，scale 只走 graphicsLayer 绘制通道。
 */
@Composable
fun PriceCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(stiffness = 400f),
        label = "cardPress"
    )

    val base = Modifier.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
    val clickable = if (onClick != null) {
        base.clickable(interactionSource = interaction, indication = null, onClick = onClick)
    } else base

    Surface(
        modifier = modifier.then(clickable),
        shape = RoundedCornerShape(Dims.CardCorner),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 0.dp
    ) {
        Column(Modifier.padding(Dims.SpacingL)) { content() }
    }
}

/** §3.5 语义标签（"历史低价"/"先涨后降"）：labelSmall + 圆角背景 + 语义色 */
enum class BadgeTone(val fg: Color, val bg: Color) {
    POSITIVE(SemanticColors.LowPrice, SemanticColors.LowPrice.copy(alpha = 0.12f)),
    NEGATIVE(SemanticColors.Suspicious, SemanticColors.Suspicious.copy(alpha = 0.12f)),
    NEUTRAL(SemanticColors.Neutral, SemanticColors.Neutral.copy(alpha = 0.12f))
}

@Composable
fun PriceBadge(text: String, tone: BadgeTone, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Dims.ChipCorner),
        color = tone.bg
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = tone.fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/** §3.5 价格一行：当前价大字 + 原价划线 + 标签 */
@Composable
fun PriceRow(
    current: Double,
    original: Double?,
    badge: String?,
    badgeTone: BadgeTone = BadgeTone.POSITIVE
) {
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(Dims.SpacingS)
    ) {
        Text(
            text = PriceFormatter.format(current),
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        )
        original?.takeIf { it > current }?.let {
            Text(
                text = PriceFormatter.format(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textDecoration = TextDecoration.LineThrough,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        badge?.let {
            PriceBadge(it, badgeTone, modifier = Modifier.padding(bottom = 6.dp))
        }
    }
}
