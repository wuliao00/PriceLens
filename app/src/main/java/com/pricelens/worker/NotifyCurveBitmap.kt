package com.pricelens.worker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.createBitmap

/**
 * 降价通知的迷你曲线（BigPictureStyle 用）。
 *
 * 纯几何 [NotifyCurveGeometry] 单独出来可 JVM 单测；[NotifyCurveBitmap.render] 只是
 * android.graphics 绘制胶水（不崩优先：任何一步失败由调用方 runCatching 回落到纯文本通知）。
 */
object NotifyCurveGeometry {

    /**
     * 把价格序列归一化到 (0..width, 0..height) 的折线点（价格高 → y 小）。
     *  - 少于 2 个点返回 null（画不出线）；
     *  - 全部等值（range 为 0）画一条水平中线，而不是贴顶。
     */
    fun pathPoints(values: List<Double>, width: Float, height: Float, pad: Float = 0f): List<Pair<Float, Float>>? {
        if (values.size < 2) return null
        val min = values.min()
        val max = values.max()
        val range = max - min
        val w = (width - 2 * pad).coerceAtLeast(1f)
        val h = (height - 2 * pad).coerceAtLeast(1f)
        return values.mapIndexed { index, value ->
            val x = pad + w * index / (values.size - 1)
            val y = if (range <= 0.0) {
                pad + h / 2f
            } else {
                pad + h * ((max - value) / range).toFloat()
            }
            x to y
        }
    }
}

object NotifyCurveBitmap {

    /** 透明底 + 折线；返回位图失败由调用方兜（此函数直接抛） */
    fun render(points: List<Pair<Float, Float>>, widthPx: Int, heightPx: Int, lineColor: Int): Bitmap {
        val bitmap = createBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1))
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = lineColor
            style = Paint.Style.STROKE
            strokeWidth = (heightPx / 32f).coerceAtLeast(2f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = Path()
        points.forEachIndexed { index, (x, y) ->
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
        return bitmap
    }
}
