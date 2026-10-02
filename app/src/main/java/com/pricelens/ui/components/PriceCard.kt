package com.pricelens.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
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
 * 深度原则落点（"状态 → 绘制值"一律走 [CardMotion] 的纯判据，可在 JVM 单测里钉死）：
 *  - 静置：tonal 色差 + [Elevations.CardRest] 轻阴影，仅建立层级
 *  - 按压：**只有声明了 onClick 的卡片**才有按压态——[MotionDurations.Fast] 内
 *    scale 到 [CardMotion.PressScale] 并把阴影升到 [Elevations.CardPressed]
 *  - 长按浮起：scale [CardMotion.LiftScale] + 同一深度，[MotionDurations.Slow] 后自动回落，无弹跳
 *  - 只声明 onLongClick 的卡片（如盯价曲线卡）按下**不缩不浮**：点不动却在抖会骗用户以为能点
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
    val hasClick = onClick != null
    // collectIsPressedAsState 已经把 Press/Release/Cancel 折成一个布尔；这里还原成 Set<Interaction>，
    // 好让 CardMotion 的纯判据只有一个入口（不直接读 InteractionSource 的"当前交互"——
    // 那个形态在各 Compose 版本里换过三次，而布尔版是全库唯一稳定可读的）。
    val pressInteractions = remember(pressed) {
        if (pressed) setOf<Interaction>(PressInteraction.Press(Offset.Unspecified)) else emptySet<Interaction>()
    }
    var lifted by remember { mutableStateOf(false) }
    // 浮起后自动回落：长按反馈是短暂脉冲，不需要调用方手动复位
    LaunchedEffect(lifted) {
        if (lifted) {
            kotlinx.coroutines.delay(MotionDurations.Slow.toLong())
            lifted = false
        }
    }

    // §2 铁律：动画值只喂 graphicsLayer（scale）与 Surface.shadowElevation，两样都在绘制阶段
    val scale by animateFloatAsState(
        targetValue = if (lifted) CardMotion.LiftScale else CardMotion.pressedScale(pressInteractions, hasClick),
        animationSpec = tween(CardMotion.PressDurationMillis, easing = PriceLensEasing),
        label = "cardPress"
    )
    val shadow by animateDpAsState(
        targetValue = CardMotion.cardElevation(pressInteractions, hasClick, lifted),
        animationSpec = tween(CardMotion.PressDurationMillis, easing = PriceLensEasing),
        label = "cardPressElevation"
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

/**
 * §2 卡片按压/浮起的纯判据（状态 → 绘制值：scale 走 graphicsLayer、深度走 shadowElevation，
 * 都不改布局）。抽出来的真正理由是可测性：**没有 onClick 的卡片不得有按压态**这条规则
 * 在真机上很难看出来，单测一眼看穿。
 */
object CardMotion {

    /** 按压缩放：轻微内缩，不做弹跳 */
    const val PressScale = 0.99f

    /** 长按浮起缩放 */
    const val LiftScale = 1.02f

    /** 按压/浮起的反馈时长（铁律：短促，只许 MotionDurations.Fast） */
    const val PressDurationMillis = MotionDurations.Fast

    /** 按压缩放：只有确实可点的卡片才有按压态 */
    fun pressedScale(interaction: Set<Interaction>, hasClick: Boolean): Float {
        if (!hasClick) return 1f
        return if (interaction.hasActivePress()) PressScale else 1f
    }

    /** 阴影深度：按压（需有点击能力）或长按浮起时升到 [Elevations.CardPressed]，否则静置 */
    fun cardElevation(interaction: Set<Interaction>, hasClick: Boolean, lifted: Boolean): Dp {
        val pressedClick = hasClick && interaction.hasActivePress()
        return if (lifted || pressedClick) Elevations.CardPressed else Elevations.CardRest
    }

    /** 只认"正按住"：Release/Cancel/焦点之类交互都不算按压（无 onClick 的卡片据此恒 1f） */
    private fun Set<Interaction>.hasActivePress(): Boolean = any { it is PressInteraction.Press }
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
