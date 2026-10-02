package com.pricelens.ui.price

/**
 * §6.2 曲线描线进度 → 可见部分（纯几何，不碰 Compose，JVM 可测）。
 *
 * 为什么要抽出来：入场必须是 **draw 通道**动画——每帧只重画 Canvas 内容，
 * 绝不让布局重排。旧实现按 `(点数 * progress).toInt()` 整点跳，起步被
 * `coerceAtLeast(2)` 顶成"一帧就出现两段"，中段则一格一格蹦；这里改成
 * "整段 + 残段比例"，描线头落在两点之间的插值位置上，视觉上是一条连续画出来的线。
 *
 * [visiblePoints] 是完整可见的点数（含起点），[segmentFraction] 是紧接着那一段的已描比例。
 */
data class CurveReveal(val visiblePoints: Int, val segmentFraction: Float)

/**
 * 进度 → 可见部分。约定：
 *  - `pointCount <= 0` → 什么都不画（CurveReveal(0, 0f)）；
 *  - `pointCount == 1` → 恒为 (1, 0f)：单采样日是真实状态，不许索引到第 2 点；
 *  - `progress == 0` → (1, 0f)：只有起点，线还没画出来；
 *  - `progress == 1` → (pointCount, 0f)：全量、无残段；
 *  - 超出 0f..1f 的进度先钳制；可见长度对 progress 单调不减。
 */
fun curveReveal(progress: Float, pointCount: Int): CurveReveal {
    if (pointCount <= 0) return CurveReveal(0, 0f)
    if (pointCount == 1) return CurveReveal(1, 0f)
    val segments = pointCount - 1
    val travelled = progress.coerceIn(0f, 1f) * segments
    val whole = travelled.toInt()
    if (whole >= segments) return CurveReveal(pointCount, 0f)
    return CurveReveal(whole + 1, travelled - whole)
}

/**
 * 描线头在当前段内的价格插值（[fraction] 先钳制到 0f..1f，绝不外推）。
 * 脉冲圆点跟着它走，才不会出现"线头在跑、圆点已在终点等"。
 */
fun segmentPoint(from: Double, to: Double, fraction: Float): Double {
    val clamped = fraction.coerceIn(0f, 1f).toDouble()
    return from + (to - from) * clamped
}
