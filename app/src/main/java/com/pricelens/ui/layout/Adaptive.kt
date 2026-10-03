package com.pricelens.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * 窗口宽度分档（自适应布局的唯一判据）。
 *
 * 为什么现在需要它：全仓 `WindowSizeClass` 出现 0 次（2026-10-03 审计实测），也就是说
 * 折叠屏展开、平板、分屏这些形态下 App 用的还是**手机那一套排版** —— 一条 60 字的中文标题
 * 在 1280dp 宽的窗口上拉成一整行，阅读宽度约 90 个字，Material 给的舒适值是 45~75 个字符。
 *
 * 阈值不是我拍的：600dp / 840dp 是 Material 3 官方的 compact / medium / expanded
 * 三档窗口分类（也是 `WindowSizeClass` 的分界）。引用现成标准而不是自己调数，
 * 正是这批"去僵硬化"要的做法。
 *
 * 输入用 [LocalConfiguration] 的 `screenWidthDp`：它就是"本 Activity 窗口"的宽（多窗口/折叠
 * 切换时系统会重报），与浮窗侧 [com.pricelens.accessibility.capsuleMaxWidthDp] 读的是同一个来源。
 * 没用 `androidx.compose.ui.window.LocalWindowInfo` —— 本项目的 Compose 版本里那个符号解析不到
 * （第一次编译就红在这里），为了一个读数去动 BOM 版本不值得，且它并不比 Configuration 更准。
 */
enum class WindowWidthBucket {
    /** < 600dp：手机竖屏。排版与历史行为逐字一致（不加宽度上限） */
    COMPACT,

    /** 600~839dp：折叠屏展开、小平板竖屏、分屏。单栏但限宽居中 */
    MEDIUM,

    /** ≥ 840dp：平板横屏、桌面自由窗口。侧边导航 + 限宽内容 */
    EXPANDED
}

object Adaptive {

    /** Material 3 分档下界（dp） */
    const val MEDIUM_MIN_DP = 600f
    const val EXPANDED_MIN_DP = 840f

    /**
     * 内容最大宽（dp）。600 是"手机竖屏最宽的正文行"（Pixel 7 是 411dp，600 已经松出一档），
     * 840 留给展开档：再宽就该走多栏而不是继续拉行。
     */
    const val CONTENT_MAX_MEDIUM_DP = 600f
    const val CONTENT_MAX_EXPANDED_DP = 840f

    fun bucketOf(widthDp: Float): WindowWidthBucket = when {
        widthDp >= EXPANDED_MIN_DP -> WindowWidthBucket.EXPANDED
        widthDp >= MEDIUM_MIN_DP -> WindowWidthBucket.MEDIUM
        else -> WindowWidthBucket.COMPACT
    }

    /** null = 不设上限（COMPACT 必须与改造前逐像素一致，所以是 null 而不是"很大的数"） */
    fun contentMaxWidthDp(bucket: WindowWidthBucket): Float? = when (bucket) {
        WindowWidthBucket.COMPACT -> null
        WindowWidthBucket.MEDIUM -> CONTENT_MAX_MEDIUM_DP
        WindowWidthBucket.EXPANDED -> CONTENT_MAX_EXPANDED_DP
    }

    /*
     * 这里**故意没有** `useSideNav(bucket)` 之类的"该不该换 NavigationRail"判据：
     * 换侧边导航要把 Scaffold 拆进 Row、再补一套 rail 版 tab 项，而本机既起不了模拟器
     * （无 hypervisor，见 docs/ROADMAP §9.4 与任务 #53）也没有连着的大屏设备，
     * 那条分支改完**一眼都看不到**。留一个没人消费的判据，比不写更容易骗到未来的我。
     */
}

/**
 * 当前窗口的宽度分档。
 *
 * `screenWidthDp` 是**窗口**宽而不是物理屏宽（Android 的 Configuration 自多窗口起就是按窗口给的），
 * 分屏与折叠切换时系统会重报，Compose 会因此重组 —— 与浮窗侧读的是同一个来源
 * （[com.pricelens.accessibility.capsuleMaxWidthDp] 的入参）。
 */
@Composable
fun rememberWindowWidthBucket(): WindowWidthBucket = Adaptive.bucketOf(LocalConfiguration.current.screenWidthDp.toFloat())
