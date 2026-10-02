package com.pricelens.ui.price

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.pricelens.domain.NumberRoll
import com.pricelens.ui.theme.MotionDurations
import com.pricelens.ui.theme.PriceLensEasing
import com.pricelens.ui.theme.PriceType

/**
 * §2.3 主价格滚动（countUp）。做法提炼自 ui/coupon/CouponScreen.NetPriceHeader
 * （该区归其他代理，本批只提炼不改），差别是把"取值 + 小数位 + 宽度锚点"全部交给
 * [NumberRoll]，UI 这边只剩一个 animateFloatAsState 驱动的**数值本身**（不是进度）：
 * 换目标时它天然从"当前已展示值"接着滚，中途来新目标也不会跳回起点重放。
 *
 * 首帧目标是 0f → 首次数据到达就是一次从 0 涨上来的 countUp（§2.3 口径）。
 *
 * 宽度不抖（[PriceType.PriceHero] 已开 fontFeatureSettings = "tnum"，每格同宽）：
 *  - 全程格数恒定：小数位按目标值取一次、跨千分位用 U+2007 占位补齐（见 [NumberRoll] 三点策略）；
 *  - 占位补在货币符号之后、数字之前 → 符号左缘与数字右缘都不动，兄弟节点也不会被推走；
 *  - 每帧只重组这一个 Text 节点，且它的文本格数不变 → 测量结果宽度不变，不触发布局级重排。
 */
@Composable
fun RollingPriceText(
    value: Double,
    modifier: Modifier = Modifier,
    style: TextStyle = PriceType.PriceHero,
    color: Color = MaterialTheme.colorScheme.primary
) {
    var goal by remember { mutableStateOf(0f) }
    // 本轮滚动的起点：可能是上一轮被打断时的半途值，宽度锚点要用它才算准
    var startValue by remember { mutableStateOf(0f) }
    val shown by animateFloatAsState(
        targetValue = goal,
        animationSpec = tween(MotionDurations.PriceRoll, easing = PriceLensEasing),
        label = "priceRoll"
    )
    LaunchedEffect(value) {
        val target = value.toFloat()
        if (target != goal) {
            startValue = shown
            goal = target
        }
    }

    Text(
        text = NumberRoll.rollText(shown.toDouble(), startValue.toDouble(), value),
        style = style,
        color = color,
        modifier = modifier
    )
}
