package com.pricelens.ui.price

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.pricelens.data.remote.ManmanbuyApi
import com.pricelens.ui.theme.SemanticColors

/**
 * §6.2 手写 Canvas 历史价格曲线（禁止第三方图表库）。
 * 标注：当前价脉冲圆点、历史最低（绿虚线）、历史最高（红虚线）、大促节点（灰竖线）。
 * 入场 500ms tween（§2.3 价格数字滚动时长），仅绘制阶段动画，无重组开销。
 */
@Composable
fun PriceChartCanvas(
    history: ManmanbuyApi.History,
    modifier: Modifier = Modifier,
    lineColor: Color = SemanticColors.LowPrice
) {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    // §2.3 价格数字滚动 500ms；进度值只影响 Canvas 绘制（draw 通道）
    val progress by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(500),
        label = "chartEnter"
    )

    val gridColor = Color(0xFFE0E0E0)

    Canvas(modifier = modifier) {
        val points = history.points
        if (points.size < 2) return@Canvas

        val minP = history.lowest.toFloat()
        val maxP = history.highest.toFloat()
        val range = (maxP - minP).takeIf { it > 0f } ?: 1f
        val left = 8.dp.toPx()
        val right = size.width - 8.dp.toPx()
        val top = 12.dp.toPx()
        val bottom = size.height - 20.dp.toPx()
        val w = right - left
        val h = bottom - top

        fun xOf(i: Int) = left + w * i / (points.size - 1)
        fun yOf(p: Double) = bottom - h * ((p - minP) / range).toFloat()

        // 历史最低 / 最高 虚线
        val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
        drawLine(
            color = SemanticColors.LowPrice.copy(alpha = 0.7f),
            start = Offset(left, yOf(history.lowest)),
            end = Offset(right, yOf(history.lowest)),
            pathEffect = dash,
            strokeWidth = 1.5.dp.toPx()
        )
        drawLine(
            color = SemanticColors.Suspicious.copy(alpha = 0.7f),
            start = Offset(left, yOf(history.highest)),
            end = Offset(right, yOf(history.highest)),
            pathEffect = dash,
            strokeWidth = 1.5.dp.toPx()
        )

        // 大促节点（618/双11/双12 前后）：灰色竖线
        points.forEachIndexed { i, p ->
            if (p.date.endsWith("06-18") || p.date.endsWith("11-11") || p.date.endsWith("12-12")) {
                drawLine(
                    color = Color.Gray.copy(alpha = 0.35f),
                    start = Offset(xOf(i), top),
                    end = Offset(xOf(i), bottom),
                    strokeWidth = 1.dp.toPx()
                )
            }
        }

        // 折线：按入场进度截断绘制
        val visibleCount = (points.size * progress).toInt().coerceAtLeast(2)
        val path = Path()
        for (i in 0 until visibleCount) {
            val x = xOf(i)
            val y = yOf(points[i].price)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = lineColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))

        // 当前价：脉冲圆点（透明度随进度呼吸）
        val lastX = xOf(visibleCount - 1)
        val lastY = yOf(points[visibleCount - 1].price)
        drawCircle(
            color = lerp(lineColor, Color.White, 0.6f).copy(alpha = 0.45f * progress),
            radius = 9.dp.toPx(),
            center = Offset(lastX, lastY)
        )
        drawCircle(
            color = lineColor,
            radius = 4.5.dp.toPx(),
            center = Offset(lastX, lastY)
        )
    }
}
