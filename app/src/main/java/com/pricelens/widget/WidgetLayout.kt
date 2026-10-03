package com.pricelens.widget

/**
 * 小组件按**可用高度**决定显几行（纯函数，JVM 可测）。
 *
 * 为什么需要：`WatchWidget` 用的是 [androidx.glance.appwidget.SizeMode.Exact]，用户可以把它拖成
 * 2x1 或 5x4；而内容以前是**三行写死**——拖扁之后第三行被窗口裁掉，用户看到的是"信息少了一半
 * 且不知道为什么"，拖高则是上面三行下面一大片空白。行数改为由高度算出来，
 * 这一档判据不需要连手机也能测（`WidgetLayoutTest`）。
 *
 * 阈值不是拍的，全部由两个可查的数派生：内边距 [VerticalPaddingDp]（= 内容里 `padding(12.dp)`，
 * 两处必须一致，所以钉在这里）与一行 16sp 粗体的行高上限 [RowLineDp]。
 * `android:minHeight=110dp`（4x2 承诺形态）代入 → 110 ≥ 96 → **仍是 3 行**，
 * 也就是说这条改动对已经验收过的默认尺寸是零变化，只影响"被用户拖小"的情形。
 */
object WidgetLayout {

    /** 内容上下内距（dp）：与 `WatchWidgetContent` 里的 `padding(12.dp)` 同一个数 */
    const val VerticalPaddingDp = 12f

    /**
     * 一行的行高上限（dp）：正文最大那行是 16sp 粗体，Compose 行高 ≈ 字号 ×1.5 → 24dp。
     * 取最大行而不是各行实测值，是为了让三档判据用同一把尺（下面第 2/3 行更小，只会更宽裕）。
     */
    const val RowLineDp = 24f

    /** 默认形态（4x2）承诺的行数：尺寸读不到时也回这一档，不猜用户想要什么 */
    const val DefaultRows = 3

    /** 装下 [rows] 行需要的总高度（dp，含上下内距） */
    fun contentHeightFor(rows: Int): Float = VerticalPaddingDp * 2 + RowLineDp * rows

    /**
     * 给定窗口高度该显几行。
     *
     * `Dp.Unspecified` 会传成 NaN（框架没给尺寸的场景）→ 返回 [DefaultRows]，
     * 保持改造前的行为，而不是把内容压成一行。
     */
    fun rowsFor(heightDp: Float): Int = when {
        heightDp.isNaN() -> DefaultRows
        heightDp >= contentHeightFor(3) -> 3
        heightDp >= contentHeightFor(2) -> 2
        else -> 1
    }
}
