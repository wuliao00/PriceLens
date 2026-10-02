package com.pricelens.ui.main

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import com.pricelens.ui.theme.MotionDurations
import com.pricelens.ui.theme.PriceLensEasing

/**
 * 底部导航项的唯一实现（tab 收敛为四个之后，导航样式只此一份）。
 *
 * 为什么要换掉裸 `NavigationBarItem`：仓库原来恒用 `Icons.Filled.*`，选中与未选中之间
 * 只有指示条颜色在变，图标本身一动不动——四个 tab 点起来"没反应"。这里补两件事：
 *  - **未选中 Outlined / 选中 Filled 交叉淡入**（Material 的常规做法，150ms）；
 *  - 图标整体 scale 0.92→1.0（`graphicsLayer`，走绘制通道，不改布局尺寸，
 *    因此不会把相邻 tab 顶得左右晃）。
 *
 * 时长取 [MotionDurations.Fast]（150ms）：导航是高频重复动作，比页面转场更快一档，
 * 与 `Motion.kt` 的"快速反馈"定义一致。禁用弹跳缓动（同铁律）。
 */
@Composable
fun NavTabItem(
    selected: Boolean,
    label: String,
    iconSelected: ImageVector,
    iconUnselected: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.92f,
        animationSpec = tween(MotionDurations.Fast, easing = PriceLensEasing),
        label = "navIconScale"
    )
    // 先算好 Modifier 再传：把 graphicsLayer 的块状 lambda 塞进 Box(...) 的参数里，
    // 换行位置会踩 ktlint 的续行规则（本仓库已经为这类格式白跑过整轮构建）
    val scaled = Modifier.graphicsLayer {
        scaleX = iconScale
        scaleY = iconScale
    }
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        icon = {
            Box(contentAlignment = Alignment.Center, modifier = scaled) {
                Crossfade(targetState = selected, animationSpec = tween(MotionDurations.Fast), label = "navIcon") { sel ->
                    Icon(
                        imageVector = if (sel) iconSelected else iconUnselected,
                        contentDescription = label
                    )
                }
            }
        },
        label = { Text(text = label) }
    )
}
