package com.pricelens.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * §2 动效规格令牌（顺从原则：克制、短促、无弹跳）。
 *
 * 铁律（2026-10-02 与代码逐条对齐，注释即口径）：
 *  1. 动画值只允许 animateFloatAsState / animateDpAsState（循环指示类另加 rememberInfiniteTransition
 *     的 animateFloat）产出，且只喂**绘制通道**：graphicsLayer（alpha / scale / translationY）、
 *     Canvas 描线进度、drawBehind 高光、Surface.shadowElevation。绝不喂宽高/间距/weight 等布局属性。
 *  2. 单次转场时长 ≤ 350ms：下面每个令牌都在铁律内。
 *     - 曲线入场的旧 500ms 例外已收到 [MotionDurations.ChartReveal] = 350ms，不再是例外；
 *     - 到手价 countUp 的 500ms 例外已在 2026-10-03 收进 [PriceRoll]（`ui/product/ProductCouponSection`），
 *       清单里从此没有"转场超时"的例外；
 *     - 循环指示类不算转场：骨架 shimmer 是 [ShimmerSweep]=1500ms 无限循环（有令牌但不受 ≤350 约束），
 *       图片占位→成图由 Coil 自己 crossfade（默认 300ms，也在上限内）。
 *  3. 禁 bounce/overshoot：转场一律 [PriceLensEasing]（FastOutSlowIn）或同曲线的别名 [RevealEasing]；
 *     唯一的非转场例外是骨架高光的 LinearEasing（匀速平移，本就没有加减速，谈不上弹跳）。
 *  4. 入场只播一次：骨架→内容交叉淡入与列表项 stagger 的"要不要重播"由
 *     domain/EnterReplayGuard 判——入场键必须是**与数据无关的稳定标识**，绝不能用数据本身当 key
 *     （key 一变，AnimatedContent/remember 就会每次数据刷新都重放一次）。
 */
object MotionDurations {
    /** 快速反馈（按压态、小元素切换）：150ms */
    const val Fast = 150

    /** 标准转场（卡片展开、内容切换、列表项入场）：250ms */
    const val Standard = 250

    /** 慢速上限（大面积转场）：350ms，铁律上限 */
    const val Slow = 350

    /** 骨架→内容交叉淡入：200ms（需求区间 150~250 的中值） */
    const val Crossfade = 200

    /** 价格数字滚动：350ms（铁律上限；旧的 500ms 历史例外已收到这里） */
    const val PriceRoll = 350

    /** 曲线描线入场：350ms（同上，已从 500ms 例外收进铁律） */
    const val ChartReveal = 350

    /** 列表项 stagger 每级阶梯：40ms */
    const val StaggerStep = 40

    /** 列表项 stagger 参与累加的级数上限：第 8 项之后延迟封顶（最迟 280ms 起播） */
    const val StaggerCap = 8

    /**
     * 骨架高光的**循环**周期：1500ms。
     *
     * 它不受上面"单次转场 ≤350ms"的铁律约束，因为它根本不是转场 —— 匀速平移一整遍骨架，
     * 停了就没有下一轮。单独给个名字是为了让 `ShimmerSkeleton` 里没有裸数字，
     * 而不是为了把它当成转场时长来调（改它只会让骨架看起来更忙或更呆）。
     */
    const val ShimmerSweep = 1500
}

/**
 * 标准缓动：FastOutSlowIn 类（快速启动、缓慢收尾）。
 * 全局统一入口，禁止使用 BounceEasing / OvershootInterpolator。
 */
val PriceLensEasing = FastOutSlowInEasing

/**
 * 入场/揭示缓动别名：与 [PriceLensEasing] 是同一条曲线，只是按用途命名，
 * 日后若要给入场单独调参，只改这一行即可（现在不调，所以两者必须同值）。
 */
val RevealEasing = FastOutSlowInEasing

/** 列表项入场的起始位移：8dp，只喂 graphicsLayer.translationY（不占布局） */
val EnterOffsetY: Dp = 8.dp

/**
 * 深度令牌（深度原则）：静置轻、交互浮、悬浮最高。
 * 2026-10-02 起 [CardRest] / [CardPressed] 已有真实消费者（PriceCard + [com.pricelens.ui.components.CardMotion]，
 * CardRest → CardPressed 走 MotionDurations.Fast）；
 * [Overlay] **仍然没人用**——悬浮层在 ui/components/PriceOverlay.kt，本批归其他代理，未动。
 */
object Elevations {
    /** 卡片静置：近乎贴面，仅建立层级 */
    val CardRest: Dp = 1.dp

    /** 按压/长按浮起：卡片脱离底面 */
    val CardPressed: Dp = 8.dp

    /** 悬浮层（价格悬浮窗/覆盖层）：最高层级 */
    val Overlay: Dp = 12.dp

    /**
     * 悬浮层的**着色**高度（tonalElevation）：胶囊 / 展开面板 / 小圆球三处一直是同一个 4dp，
     * 以前各写一份字面量。注意它和 [Overlay] 不是一回事：Overlay 是投影，这个是表面染色。
     */
    val OverlayTonal: Dp = 4.dp

    /** 折叠胶囊的投影：比展开面板浅一档，折叠态不该抢视线 */
    val OverlayCapsule: Dp = 6.dp

    /** 展开面板的投影：内容多、要读，比胶囊重一档但仍低于小圆球 [Overlay] */
    val OverlayPanel: Dp = 8.dp
}
