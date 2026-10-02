package com.pricelens.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * §3.2 字体层级（清晰原则）：字号梯度 11/12/14/16/20/28，行高 ≈ 字号 ×1.25~1.43。
 *
 * 2026-10-02「排版/密度大改」对规格的三处**显式偏离**，逐条写清理由（不悄悄改）：
 *
 * ① labelSmall 11sp → **12sp**（11 档并入 12 档）。
 *    依据：2026-10-02 那轮深色真机截图（**vivo V2156A / 1080×2408 @480dpi / Android 11**）复核，
 *    11sp SemiBold 的灰字——状态徽标「反爬/失败」、源名、时间戳——在 OLED 黑底上笔画并拢，
 *    「反爬」和「失败」两枚徽标在正常持机距离下几乎同形。11 与 12 只差 0.7px，
 *    本来就构不成可辨层级；留一个"看不清的最小档"不如把最小档抬到 12sp，
 *    让可辨的梯度落在 12/14/16/20/28 五档上。
 *    **代价**：紧凑报价行里的徽标宽 1 个字符位，版面预算已按 12sp 重算（见 ui/layout/RowBudget.kt），
 *    不是"改完字号再看会不会溢出"。
 *    **待复核**：本轮走查机已换成 OPPO PLB110（Android 15 / 1256×2760 @560dpi），
 *    这档字在 560dpi 深色下的实际观感还没在这台上看过的（上面那条依据来自旧机那轮的截图）。
 *
 * ② 显式补上 titleSmall / labelMedium（此前是"隐形的第 7、8 档"）。
 *    PriceLensTypography 原来只覆盖 7 个槽位，而 OverviewScreen 的引导卡标题用 titleSmall、
 *    ProfileScreen 的滑动背景用 labelMedium——两者都落到 Material 3 默认值
 *    （14sp/20sp W400、12sp/16sp W500）。值本身在梯度内，但**没进规格**：审查时看不见，
 *    换 M3 版本时还会被悄悄改掉。这里按 M3 默认值原样写出（渲染不变，收编进规格）。
 *
 * ③ bodyMedium 维持 13sp —— 这是仓库里既有的第 3 处偏离，本批**只补文档不静删**：
 *    它的用处是"输入框正文 + 步骤说明"（AppTopBar / SearchBar / OnboardingStep），
 *    比 bodyLarge 矮半档、比 bodySmall 高一档，删成 12 会让搜索框正文在 560dpi 下偏小，
 *    删成 14 就与 bodyLarge 完全重合。这批不改值，是因为这些调用点不在本代理的改动范围内
 *    （AppTopBar / SearchBar 归另一个代理），改字号得连带重算它们的宽度预算。
 */
val PriceLensTypography = Typography(
    displayLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 16.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp),
    // 11 → 12：理由与代价见文件注释 ①
    labelSmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, lineHeight = 16.sp)
)

/**
 * 价格数值专用样式：大号金额强调（展示级字重）。
 * fontFeatureSettings("tnum") 启用等宽数字，数字滚动/跳动时不抖动（§2.3）。
 */
object PriceType {
    val PriceHero = TextStyle(
        fontSize = 32.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 40.sp,
        fontFeatureSettings = "tnum"
    )

    /** 列表/卡片内的主价格（次级强调，等宽数字） */
    val PriceLarge = TextStyle(
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 28.sp,
        fontFeatureSettings = "tnum"
    )

    /**
     * 紧凑行内的价格（16sp / 20sp 行高，等宽数字）。
     * 密度重排的落点：32sp 的 PriceHero 一行顶三行，概览首屏只放得下两件事；
     * 现价/历史最低/建议徽章要在同一行收纳，金额就降到 16sp，
     * 而行高压到 20sp 是为了和"两行 22dp 标题"拼成 64dp 的头卡（见 Dims.ThumbHeader）。
     */
    val PriceInline = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 20.sp,
        fontFeatureSettings = "tnum"
    )

    /** 单行报价列表里的金额（14sp / 20sp，等宽数字）：一行四段，价格不能抢过源名 */
    val PriceRowCompact = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 20.sp,
        fontFeatureSettings = "tnum"
    )
}
