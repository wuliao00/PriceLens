package com.pricelens.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.pricelens.ui.theme.MotionDurations
import com.pricelens.ui.theme.PriceLensEasing

/**
 * 全屏二级页（设置 / 保活引导 / 脚本 / 商品详情）的推进与退回转场。
 *
 * 为什么要专门做这个组件：这些页面此前都是 `if (showX) Screen(...)`，
 * 状态一翻就整块出现/消失，与主页之间没有任何过渡——这是"点设置像闪了一下"的直接原因。
 *
 * **调用方必须让本组件常驻组合**（把 `if (showX)` 换成 `visible = showX`）。
 * 原因就写在下面那句注释里：父组合一旦把孩子摘掉，exit 动画没有载体，永远播不出来。
 * 这条踩坑固化成组件，就是为了让下一个改 MainActivity 的人不必再发现一次。
 *
 * 与 `Motion.kt` 铁律的关系：进/出场用的是 `slideInHorizontally`/`slideOutHorizontally` +
 * `fadeIn`/`fadeOut`，两者都走 graphicsLayer（位移与透明度），**不动布局、不触发重排**，
 * 因此符合"动画只走绘制通道"。入场取上限 350ms（大面积转场），出场取 250ms
 * （退回应比进入更快，这是 Material 的不对称时长惯例）。
 */
@Composable
fun PageTransition(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInHorizontally(
            animationSpec = tween(MotionDurations.Slow, easing = PriceLensEasing),
            initialOffsetX = { it }
        ) + fadeIn(animationSpec = tween(MotionDurations.Standard, easing = PriceLensEasing)),
        exit = slideOutHorizontally(
            animationSpec = tween(MotionDurations.Standard, easing = PriceLensEasing),
            targetOffsetX = { it }
        ) + fadeOut(animationSpec = tween(MotionDurations.Fast, easing = PriceLensEasing)),
        label = "pageTransition"
    ) { content() }
}

/**
 * 页面内联块（搜索历史 chips、剪贴板横条、引导提示条）的显隐。
 *
 * 这里**故意**用了 `expandVertically`/`shrinkVertically`，也就是动高度、走布局通道——
 * 与 `Motion.kt` 的铁律有偏差，理由是：这类横条插在内容之上，若只淡入淡出，
 * 它下方所有内容会"瞬间跳 56dp"，比高度动画本身更刺眼。
 * 时长压在 Standard(250ms)，不做双向位移，只在页面顶部这一条窄带内变化。
 *
 * 判断准则（写给以后想照抄的人）：**位置在流内且会顶开内容的块**用本组件；
 * **覆盖在内容之上的层**（页面、弹窗、浮窗）用 [PageTransition] 或纯 fade/slide。
 */
@Composable
fun StripReveal(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = expandVertically(
            animationSpec = tween(MotionDurations.Standard, easing = PriceLensEasing),
            // 以顶边为轴往下掀开。参数类型是 Alignment.Vertical（不是 VerticalDirection——
            // 我先猜错过一次，两轮构建白跑），且不传时默认是 Bottom：
            // 这条横条插在内容之上，从下往上顶会把上方内容来回推。观感待真机复核。
            expandFrom = Alignment.Top
        ) + fadeIn(animationSpec = tween(MotionDurations.Standard, easing = PriceLensEasing)),
        exit = shrinkVertically(animationSpec = tween(MotionDurations.Fast, easing = PriceLensEasing)) +
            fadeOut(animationSpec = tween(MotionDurations.Fast, easing = PriceLensEasing)),
        label = "stripReveal"
    ) { content() }
}
