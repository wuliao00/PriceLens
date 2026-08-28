package com.pricelens.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * §2 动效规格令牌（顺从原则：克制、短促、无弹跳）。
 * 铁律：
 * - 仅 animateFloatAsState + graphicsLayer/drawBehind（动画只走绘制通道）
 * - 时长 ≤ 350ms（PriceChartCanvas 的 500ms 入场为历史例外，见 §2.3）
 * - 禁止 bounce/overshoot 类缓动
 */
object MotionDurations {
    /** 快速反馈（按压态、小元素切换）：150ms */
    const val Fast = 150

    /** 标准转场（卡片展开、内容切换）：250ms */
    const val Standard = 250

    /** 慢速上限（大面积转场）：350ms，铁律上限 */
    const val Slow = 350
}

/**
 * 标准缓动：FastOutSlowIn 类（快速启动、缓慢收尾）。
 * 全局统一入口，禁止使用 BounceEasing / OvershootInterpolator。
 */
val PriceLensEasing = FastOutSlowInEasing

/**
 * 深度令牌（深度原则）：静置轻、交互浮、悬浮最高。
 * 供阶段4卡片按压浮起/悬浮层使用；搭配 MotionDurations.Standard 过渡。
 */
object Elevations {
    /** 卡片静置：近乎贴面，仅建立层级 */
    val CardRest: Dp = 1.dp

    /** 按压/长按浮起：卡片脱离底面 */
    val CardPressed: Dp = 8.dp

    /** 悬浮层（价格悬浮窗/覆盖层）：最高层级 */
    val Overlay: Dp = 12.dp
}
