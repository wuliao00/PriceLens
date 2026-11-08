package com.pricelens.ui.price

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.pricelens.data.remote.ManmanbuyApi
import com.pricelens.ui.theme.LocalSemanticColors
import com.pricelens.ui.theme.MotionDurations
import com.pricelens.ui.theme.RevealEasing

/**
 * §6.2 手写 Canvas 历史价格曲线（禁止第三方图表库）。
 * 标注：当前价脉冲圆点、历史最低（绿虚线）、历史最高（红虚线）、大促节点（灰竖线）。
 *
 * 2026-11 大改造：主线从语义绿换成**品牌金**（行情终端的主线色），线下补一层
 * 金→透明的垂直渐变面积，背景加三条极淡水平网格——曲线从"一根绿线"变成
 * 有体积、有坐标感的行情图。最低/最高仍走语义绿/红虚线（与图例同色）。
 *
 * 入场是**纯 draw 通道**动画：animateFloatAsState 出的 progress 只在 Canvas 的绘制阶段被读，
 * 可见部分由纯函数 [curveReveal] 算（整段 + 残段插值头），每帧只重画、不重排；
 * 描线头随残段连续前进，不再是"整点跳"。时长 [MotionDurations.ChartReveal] = 350ms。
 */
@Composable
fun PriceChartCanvas(history: ManmanbuyApi.History, modifier: Modifier = Modifier, lineColor: Color = MaterialTheme.colorScheme.primary) {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    // 进度值只影响 Canvas 绘制（draw 通道）；组合槽位不变 → 换数据不会重播入场
    val progress by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(MotionDurations.ChartReveal, easing = RevealEasing),
        label = "chartEnter"
    )

    // §3.5 语义色走 LocalSemanticColors（暗色感知）；网格灰走 colorScheme，暗色自动适配
    val semantic = LocalSemanticColors.current
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    // 当前价脉冲光晕向"当前主题的表面色"混合：亮色下≈白，暗色下不再泛白刺眼（深色审查项）
    val haloColor = lerp(lineColor, MaterialTheme.colorScheme.surface, 0.6f)

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

        // 水平网格（三条极淡横线）：给曲线一个可读的坐标背景，不抢线
        for (g in 1..3) {
            val gy = top + h * g / 4f
            drawLine(
                color = gridColor.copy(alpha = 0.35f),
                start = Offset(left, gy),
                end = Offset(right, gy),
                strokeWidth = 1.dp.toPx()
            )
        }

        // 历史最低 / 最高 虚线：透明度跟入场进度一起淡进来（绘制值，不改布局）
        val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
        val guideAlpha = 0.7f * progress
        drawLine(
            color = semantic.lowPrice.copy(alpha = guideAlpha),
            start = Offset(left, yOf(history.lowest)),
            end = Offset(right, yOf(history.lowest)),
            pathEffect = dash,
            strokeWidth = 1.5.dp.toPx()
        )
        drawLine(
            color = semantic.suspicious.copy(alpha = guideAlpha),
            start = Offset(left, yOf(history.highest)),
            end = Offset(right, yOf(history.highest)),
            pathEffect = dash,
            strokeWidth = 1.5.dp.toPx()
        )

        // 大促节点（618/双11/双12 前后）：灰色竖线（网格色令牌，暗色自适应）
        points.forEachIndexed { i, p ->
            if (p.date.endsWith("06-18") || p.date.endsWith("11-11") || p.date.endsWith("12-12")) {
                drawLine(
                    color = gridColor.copy(alpha = 0.55f * progress),
                    start = Offset(xOf(i), top),
                    end = Offset(xOf(i), bottom),
                    strokeWidth = 1.dp.toPx()
                )
            }
        }

        // 折线：curveReveal 给"完整可见的最后一点 + 当前残段比例"，残段用插值头描到一半
        val reveal = curveReveal(progress, points.size)
        val headIndex = (reveal.visiblePoints - 1).coerceIn(0, points.size - 1)
        val nextIndex = (headIndex + 1).coerceAtMost(points.size - 1)
        val fraction = reveal.segmentFraction
        val headX = xOf(headIndex) + (xOf(nextIndex) - xOf(headIndex)) * fraction
        val headY = yOf(segmentPoint(points[headIndex].price, points[nextIndex].price, fraction))

        if (headIndex >= 1 || fraction > 0f) {
            // 面积渐变：与折线同一条可见路径，向下封到基线，金→透明（随入场淡入）
            val area = Path()
            area.moveTo(xOf(0), bottom)
            area.lineTo(xOf(0), yOf(points[0].price))
            for (i in 1..headIndex) area.lineTo(xOf(i), yOf(points[i].price))
            if (fraction > 0f) area.lineTo(headX, headY)
            area.lineTo(headX, bottom)
            area.close()
            drawPath(
                path = area,
                brush = Brush.verticalGradient(
                    colors = listOf(lineColor.copy(alpha = 0.22f * progress), lineColor.copy(alpha = 0f)),
                    startY = top,
                    endY = bottom
                )
            )

            val path = Path()
            path.moveTo(xOf(0), yOf(points[0].price))
            for (i in 1..headIndex) path.lineTo(xOf(i), yOf(points[i].price))
            if (fraction > 0f) path.lineTo(headX, headY)
            drawPath(path, color = lineColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        }

        // 当前价：脉冲圆点贴在**线头**上（不是终点），透明度随进度呼吸
        drawCircle(
            color = haloColor.copy(alpha = 0.45f * progress),
            radius = 9.dp.toPx(),
            center = Offset(headX, headY)
        )
        drawCircle(
            color = lineColor.copy(alpha = progress),
            radius = 4.5.dp.toPx(),
            center = Offset(headX, headY)
        )
    }
}
