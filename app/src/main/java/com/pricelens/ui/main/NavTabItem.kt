package com.pricelens.ui.main

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
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
 *
 * **必须是 `RowScope` 扩展**：Material3 1.3.1 里 `NavigationBarItem` 只有一个签名，
 * 首参是 `RowScope`（`javap androidx.compose.material3.NavigationBarKt` 实测），
 * 也就是它只能在 `NavigationBar { … }` 的内容 lambda 里调。
 * 包装函数不接 RowScope 会报 `Unresolved reference 'NavigationBarItem'`，
 * 并且**同一文件里再级联出两条 `@Composable invocations can only happen from the context of a @Composable function`**
 * ——那两条是假错，别照着它们改代码（本仓库为此白跑了两轮构建）。
 *
 * [iconUnselected] 可空：`material-icons-core` 只出 Filled 变体（实测 jar 里
 * `icons/outlined/PersonKt` 不存在，extended 又为了不重复类而跳过 core 里已有的图标），
 * 所以 `Icons.Filled.Person` 这类 core 图标**没有** Outlined 版可配对。传 null 时这一格只做
 * 缩放不做形变；想给它配未选中态得换一个语义不同的图标（如 ManageAccounts），那属于改图标含义，
 * 不该由动效顺手决定。
 */
@Composable
fun RowScope.NavTabItem(
    selected: Boolean,
    label: String,
    iconSelected: ImageVector,
    iconUnselected: ImageVector? = null,
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
                val unselected = iconUnselected
                if (unselected == null) {
                    Icon(imageVector = iconSelected, contentDescription = label)
                } else {
                    Crossfade(targetState = selected, animationSpec = tween(MotionDurations.Fast), label = "navIcon") { sel ->
                        Icon(
                            imageVector = if (sel) iconSelected else unselected,
                            contentDescription = label
                        )
                    }
                }
            }
        },
        // 选中态要能在标签上也读出来（真机 2026-10-07 截图复核）：
        // 原来这里塞的是裸 `Text(label)`，Material 的 item colors 不会作用到它身上，
        // 于是四个页签的字一模一样、只靠图标后面那枚淡紫胶囊区分——室外强光下几乎看不出在哪个页。
        // 字重 + 前景色双通道，胶囊只是第三重提示。
        // 字号仍取 labelSmall（Material 给 NavigationBarItem 的默认档，本主题=12sp SemiBold）：
        // 这里只覆盖 fontWeight，不改字号——四个页签的宽度预算是按 12sp 算的。
        label = {
            Text(
                text = label,
                maxLines = 1,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                ),
                color = if (selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    )
}
