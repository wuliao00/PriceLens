package com.pricelens.accessibility

import kotlin.math.roundToInt

/**
 * 浮窗（面板 / 胶囊 / 小圆球）的**尺寸真相源**：纯数字 + 纯函数，零 Compose / WindowManager 依赖。
 *
 * 为什么要有这个文件（2026-10-03 设计债审计）：改一次面板宽度要动**两个文件**，而把它们绑在一起的
 * 只有一句注释 —— `OverlayManager` 里写着 `PANEL_MAX_WIDTH_DP = 304f // = widthIn(max = 280.dp) + 左右各 12dp`，
 * 而那个 280/12 在 `PriceOverlay.kt` 里各写一遍。这类"注释维持的一致性"是最脆的一种：
 * 改 UI 的人看不见那条注释，改完窗口宽度还是 304 ⇒ 面板右边缘被窗口裁掉，而且只在数据最长的时候露出来。
 * 现在关系变成函数：`panelWindowMaxWidthDp()` 由这两个数**算出来**，两边都调它。
 *
 * 单位一律是 dp 整数（与 [BallGeometry] 同一条约定：`const val ..._DP` + 调用点 `.dp`），
 * 因为 `Dp` 不是编译期常量，放这儿会让每个值都变成运行时对象。
 */

/** 展开面板的内容最大宽（dp）。窗口比它窄时按窗口收，见 [panelContentMaxWidthDp] */
const val PANEL_CONTENT_MAX_DP = 280

/**
 * 窗口比内容多出来的一圈（dp）：`PriceOverlay` 根 `Column` 的 padding。
 * 它不是留白装饰 —— 真机 `mFrame=[544,284][1048,500]` 而可见胶囊只有 `[580,320][1012,464]`，
 * 这 12dp 是**阴影 + 命中区**：手势必须挂在 padding 之前才吃得到这一圈（"点胶囊没反应"的成因）。
 */
const val WINDOW_GUTTER_DP = 12

/** 折叠态胶囊的高度上限（dp）：胶囊是 pill 形，圆角取它的一半（见 [capsuleCornerDp]） */
const val CAPSULE_MAX_HEIGHT_DP = 48

/** 胶囊里那枚关闭图标的边长（dp，比正文小一档、又不低于可读面积） */
const val CAPSULE_ICON_DP = 14

/** 「收起」按钮的圆角（dp）：它嵌在 pill 形胶囊里，圆角比胶囊本体小一档才看不出"方块塞进圆条" */
const val COLLAPSE_BUTTON_CORNER_DP = 16

/** 行间距（dp）：面板里"上一行结束到下一行开始"的唯一值，出现 5 次所以值得一个名字 */
const val ROW_GAP_DP = 6

/** 胶囊内文字与胶囊边的水平内距（dp） */
const val CHIP_H_PADDING_DP = 6

/** 胶囊内文字与胶囊边的垂直内距（dp） */
const val CHIP_V_PADDING_DP = 3

/** 胶囊之间 / 价格与按钮之间的小缝（dp）：比 [ROW_GAP_DP] 再小一档 */
const val CHIP_GAP_DP = 2

/**
 * 小圆球内文字到球边的内距（dp）。
 * 球是固定直径（[BALL_DIAMETER_DP]）的圆，文字必须在圆里排，所以这一档比胶囊里更窄；
 * 两个值不一样是因为竖排两行需要的高度比一行宽。
 */
const val BALL_H_PADDING_DP = 3

/** 见 [BALL_H_PADDING_DP] */
const val BALL_V_PADDING_DP = 4

/** 形态切换（面板 ↔ 小球）入场时从下方浮起的距离（dp）：只喂 graphicsLayer.translationY */
const val FORM_ENTER_OFFSET_DP = 12

/** 面板窗口的宽度上限（dp）= 内容最宽 + 左右各一圈 [WINDOW_GUTTER_DP] */
fun panelWindowMaxWidthDp(): Int = PANEL_CONTENT_MAX_DP + WINDOW_GUTTER_DP * 2

/**
 * 胶囊那条"点一下就展开/折叠"的可点带高度（dp，从窗口顶算）。
 * = 外圈 [WINDOW_GUTTER_DP] + 胶囊本体 [CAPSULE_MAX_HEIGHT_DP]。
 * 窗口里这条带以下的部分归面板内控件，不再触发折叠。
 */
fun capsuleBandDp(): Int = WINDOW_GUTTER_DP + CAPSULE_MAX_HEIGHT_DP

/** 胶囊的 pill 圆角（dp）：正好是胶囊高度的一半，所以永远跟随 [CAPSULE_MAX_HEIGHT_DP] */
fun capsuleCornerDp(): Int = CAPSULE_MAX_HEIGHT_DP / 2

/**
 * 折叠态胶囊的宽度上限（dp）。
 *
 * 旧写法是 `screenWidthDp * 0.4f` 一个表达式写完：在小屏（320dp）上它比内容需要窄很多，
 * 在平板/分屏上又跟着**整块屏幕**长（`LocalConfiguration` 给的是设备屏宽，不是本窗口的宽），
 * 于是"窗口 304dp、胶囊按 1280×0.4=512dp 排"这种自己跟自己打架的形态。
 * 现在两个上界都取：屏宽比例 与 [PANEL_CONTENT_MAX_DP] 里的较小者。
 */
fun capsuleMaxWidthDp(screenWidthDp: Int): Int =
    minOf(PANEL_CONTENT_MAX_DP, (screenWidthDp * 0.4f).roundToInt()).coerceAtLeast(120)

/**
 * 展开面板在本窗口里最多能占多宽（dp）。
 *
 * 窗口比 280+2×12 窄的时候（分屏、折叠屏半屏、小窗模式）必须跟着窗口收，
 * 否则 `widthIn(max = 280.dp)` 会画出比窗口还宽的内容，被窗口边界**静默裁掉**
 * —— 数据长的时候才露馅，正是最难复现的那类问题。下限 120dp：再窄就没有一行读得懂了。
 */
fun panelContentMaxWidthDp(screenWidthDp: Int): Int =
    minOf(PANEL_CONTENT_MAX_DP, screenWidthDp - WINDOW_GUTTER_DP * 2).coerceAtLeast(120)
