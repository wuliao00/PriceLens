package com.pricelens.accessibility

import com.pricelens.util.PriceFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * PL-29 浮窗小圆球的**纯**几何 / 形态逻辑（零 Android 依赖：不碰 WindowManager、不读系统栏、
 * 不碰 Compose）。inset 与屏幕尺寸一律由调用方（[OverlayManager]）传入，这样"球会不会压到
 * 状态栏""松手该吸哪一边"这些真机上最难验的判断，能在 JVM 层被 `BallGeometryTest` 钉死。
 *
 * 为什么不用系统 Bubble API（`android.widget.bubbles` / `Bundle`）—— 见
 * [OverlayManager] 的「形态」小节；一句话：本浮窗要的是"贴着当前商品页的一枚读价窗口"，
 * 而系统气泡把展开尺寸、吸附、层级、锁屏行为全部交给 ROM 与通知体系，我们既改不动也测不了。
 */

/** 浮窗形态：完整面板（胶囊 + 可展开面板）/ 收起后的小圆球 */
sealed class OverlayMode {
    data object Panel : OverlayMode()
    data object Ball : OverlayMode()
}

/** 形态事件。[DRAG] 显式入枚举，是为了让"拖动不改形态"成为可断言的机器规则而不是巧合 */
enum class OverlayEvent { COLLAPSE, EXPAND, DRAG }

/** 吸附目标边。[Keep] = 判不出"最近边"（球还压着中线，或输入不可用），调用方须保持原位 */
sealed class Side {
    data object Left : Side()
    data object Right : Side()
    data object Keep : Side()
}

/** 小圆球直径（dp，§四 规格 56dp）。UI 与窗口尺寸共用这一个数，别在两边各写一份 */
const val BALL_DIAMETER_DP = 56

/**
 * 球窗口比球多出来的一圈（dp）：只给 [com.pricelens.ui.theme.Elevations.Overlay] 那道阴影用。
 * 注意边界与吸附一律按**整窗口**边长算（见 [clampPosition] 的 [ballSize] 口径）——
 * 那圈透明像素照样吃掉触摸。
 */
const val BALL_SHADOW_GUTTER_DP = 8

/** 球所在窗口的边长（dp）= 直径 + 两侧留白 */
fun ballWindowSideDp(): Int = BALL_DIAMETER_DP + BALL_SHADOW_GUTTER_DP * 2

/**
 * 把球的左上角夹进可用区：横向 `[0, screenWidth - ballSize]`，
 * 纵向 `[topInset, screenHeight - bottomInset - ballSize]`。
 *
 * [ballSize] 传的是**球所在窗口的边长**（含阴影留白），不是球的直径 —— 窗口比球大的那圈
 * 透明像素照样吃掉触摸，边界必须按窗口算。
 * 负 inset 视为 0；上下边界互相矛盾（横屏 + 大球）时取上边界；任何情况下不返回负坐标。
 */
fun clampPosition(x: Int, y: Int, ballSize: Int, screenWidth: Int, screenHeight: Int, topInset: Int, bottomInset: Int): Pair<Int, Int> {
    val size = ballSize.coerceAtLeast(0)
    val top = topInset.coerceAtLeast(0)
    val bottom = bottomInset.coerceAtLeast(0)
    // 球比屏宽还长时 maxX 会是负数 —— 夹到 0（左上角对齐）而不是给出负偏移
    val maxX = (screenWidth - size).coerceAtLeast(0)
    val maxY = screenHeight - bottom - size
    val clampedY = if (maxY < top) top else y.coerceIn(top, maxY)
    return x.coerceIn(0, maxX) to clampedY
}

/**
 * 松手吸附哪一边：以球心与屏幕中线的距离判定，`|球心 - 中线| < ballSize / 2`
 * 才算"还压着中线"→ [Side.Keep]（此时左右等距，硬吸附会让球凭空跳一下）。
 * 阈值**不含**等于：恰好差一个球宽之半即判边。度量不可用（屏宽 ≤ 0、球 ≥ 屏宽）时 [Side.Keep]。
 */
fun snapEdge(x: Int, ballSize: Int, screenWidth: Int): Side {
    if (screenWidth <= 0 || ballSize <= 0 || ballSize >= screenWidth) return Side.Keep
    val center = x + ballSize / 2
    val offset = center - screenWidth / 2
    if (abs(offset) < ballSize / 2) return Side.Keep
    return if (offset < 0) Side.Left else Side.Right
}

/**
 * 位移超过 touch slop 判拖动，否则判点按。[totalDelta] 是本次手势累计位移长度（欧氏距离，px），
 * [slop] 必须是 `ViewConfiguration.get(context).scaledTouchSlop`（px）—— 不许写死常量，
 * 各厂商/各 DPI 的手势阈值差好几倍，写死会让"手抖"被当成点按或反过来。
 * 恰好等于 slop 仍算点按（阈值是"超过"）。负 slop（取不到 ViewConfiguration 时的脏值）按 0 处理。
 */
fun isDrag(totalDelta: Float, slop: Int): Boolean = totalDelta > slop.coerceAtLeast(0)

/**
 * 球上的极简文案：第一行现价，第二行可选的差值标记（`\n` 分隔，UI 按行渲染）。
 *
 * 只出价格与 `↑/↓` + 百分数，**不产任何词** —— 纯函数拿不到资源，需要本地化文案的兜底
 * 由调用方用 `strings_overlay.xml` 补（价格缺失时返回空串就是这个约定）。
 * 分母一律用 [lowest]（参考低价），与浮窗第②行「当前高 N%」同一口径，两处数字不许打架；
 * 差值不足 1% 时宁可不写标记，也不出「↑0%」。
 */
fun ballLabel(price: Double?, lowest: Double?): String {
    if (price == null || price <= 0.0) return ""
    val priceText = PriceFormatter.format(price)
    val base = lowest ?: return priceText
    if (base <= 0.0) return priceText
    val diff = price - base
    val percent = (abs(diff) / base * 100).roundToInt()
    if (percent <= 0) return priceText
    // ↑ = 现价高于参考低价（还不是抄底时机）；↓ = 现价击穿参考低价（真降价）
    val arrow = if (diff > 0.0) "↑" else "↓"
    return "$priceText\n$arrow$percent%"
}

/**
 * 形态转移的唯一权威判定（面板↔球不许在 UI 里各写一套 if）：
 *  - 面板 + 收起 → 球；球 + 展开 → 面板；
 *  - 对已经收起的球再点收起、对已经展开的面板再"展开" → 原地不动（幂等，双指连点不会攒出两次窗口尺寸变更）；
 *  - 任何拖动都不改形态。
 */
fun nextOverlayMode(current: OverlayMode, event: OverlayEvent): OverlayMode = when {
    event == OverlayEvent.COLLAPSE && canCollapse(current) -> OverlayMode.Ball
    event == OverlayEvent.EXPAND && canExpand(current) -> OverlayMode.Panel
    else -> current
}

/** 当前形态能否发起「收起」（球的收起 = 自己，无效动作） */
fun canCollapse(mode: OverlayMode): Boolean = mode is OverlayMode.Panel

/**
 * 松手落点：窗口当前左上角 + 手指累计位移（px）→ 先吸附最近边，再夹进可用区。
 *
 * 为什么拖动过程中不调这个、只在松手调一次：拖动时每次 `updateViewLayout` 都会把窗口搬走，
 * 而 Compose 给指针的坐标是**窗口内**坐标——窗口一动，手指的局部坐标几乎不变，
 * 于是"窗口跟着手走"会自己把自己拖死（真机症状：不跟手 + 抖动/闪烁）。
 * 所以拖动期间只用 `graphicsLayer.translationX/Y` 画位移（纯绘制通道，零 IPC），
 * 松手这一下才真正搬窗口。
 */
fun ballDropPosition(
    x: Int,
    y: Int,
    dx: Float,
    dy: Float,
    ballSize: Int,
    screenWidth: Int,
    screenHeight: Int,
    topInset: Int,
    bottomInset: Int
): Pair<Int, Int> {
    val movedX = (x + dx).roundToInt()
    val movedY = (y + dy).roundToInt()
    val snapped = when (snapEdge(movedX, ballSize, screenWidth)) {
        Side.Left -> 0
        Side.Right -> (screenWidth - ballSize).coerceAtLeast(0)
        Side.Keep -> movedX
    }
    return clampPosition(snapped, movedY, ballSize, screenWidth, screenHeight, topInset, bottomInset)
}

/**
 * 拖动**过程中**每一帧的窗口左上角：按下那一刻的窗口位置 + 屏幕坐标系的累计位移，
 * **只夹取、不吸附**（吸附只发生在松手那一下，见 [ballDropPosition]）。
 *
 * 为什么是"起点 + 累计位移"而不是"当前位置 + 本帧增量"：后者会把每一帧的四舍五入
 * 和窗口移动带来的坐标反馈累加进去，拖几帧就开始滞后（"不跟手"的数学成因）。
 * 锚在 DOWN 那一刻的浮点起点上，误差不会累积。
 *
 * @param dx/dy 来自 `MotionEvent.rawX/rawY`（或 `PointerInputChange.rawPosition`）的
 *   **屏幕坐标系**位移。窗口内坐标在这里不可用：窗口正在被搬，局部位移会被自己的移动抵消。
 */
fun ballDragPosition(
    originX: Int,
    originY: Int,
    dx: Float,
    dy: Float,
    ballSize: Int,
    screenWidth: Int,
    screenHeight: Int,
    topInset: Int,
    bottomInset: Int
): Pair<Int, Int> = clampPosition(
    (originX + dx).roundToInt(),
    (originY + dy).roundToInt(),
    ballSize,
    screenWidth,
    screenHeight,
    topInset,
    bottomInset
)

/**
 * 收起成球后，页面门控短暂失败（商详页把主价滚出屏幕、图片轮播切换）要不要**立刻**收窗。
 *
 * 真机症状（用户 2026-10-03 报）："滑动后小圆球闪烁，不跟手，又显示继续滑动查看图文详细"——
 * 面板形态下这条门控是对的（离开商详就该收窗），但球是用户**主动收起**的常驻读价器，
 * 京东页面滚动时门控会一闪一闪地失败，于是窗口被反复 remove/add，
 * 而每次新建窗口都从面板起步 → 球"变回"胶囊条。所以球形态下延迟收窗，
 * 期间任何一次重新命中都取消这次拆除。
 */
fun shouldDeferTeardown(mode: OverlayMode): Boolean = mode is OverlayMode.Ball

/** 新建窗口时该用哪种形态：跟随用户上一次的主动选择，而不是硬回面板 */
fun initialForm(userCollapsed: Boolean): OverlayMode = if (userCollapsed) OverlayMode.Ball else OverlayMode.Panel

/** 当前形态能否发起「展开」 */
fun canExpand(mode: OverlayMode): Boolean = mode is OverlayMode.Ball
