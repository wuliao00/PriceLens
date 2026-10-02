package com.pricelens.ui.community

import com.pricelens.ui.layout.RowBudget
import com.pricelens.ui.layout.TextTruncate

/**
 * 社区列表的稳定三段（纯函数产出，渲染层只负责"一段一行"）：
 * 标题 1 行 + 摘要 2 行 + 元信息 1 行。
 *
 * 为什么要抽成纯函数：这三段的截断直接决定"卡片高度稳不稳"。以前卡片高度跟着
 * 摘要长度随机跳动，一屏能放几条全看运气；而截断本身在本仓库踩过代理对的坑
 * （emoji 被 take(n) 切成孤儿对 → 豆腐块）。现在预算来自 [RowBudget] 的真实宽度核算，
 * 截断走 [TextTruncate] 的字簇口径，两者都有 JVM 单测。
 */
data class CommunityRowParts(val title: String, val excerpt: String?, val meta: String)

object CommunityRows {
    /** 标题一行（bodyMedium 14sp）能放的单元数 */
    val TITLE_UNITS: Int = RowBudget.lineUnits(14f)

    /** 摘要两行（bodySmall 12sp） */
    val EXCERPT_UNITS: Int = RowBudget.lineUnits(12f) * 2

    /** 元信息一行（labelSmall 12sp） */
    val META_UNITS: Int = RowBudget.lineUnits(12f)

    /**
     * 组装三段。
     *  - 每段先 trim 再按各自预算截断（字簇口径，emoji 不会被切半）；
     *  - [excerpt] 为 null/空白时该段**整体消失**（不摆一行空的省略号冒充内容）；
     *  - [metaParts] 里的空白段被丢掉后再连接（"识货 · · 3 小时前" 这种脏串不再出现）。
     */
    fun of(title: String, excerpt: String?, metaParts: List<String>, separator: String = " · "): CommunityRowParts = CommunityRowParts(
        title = TextTruncate.clamp(title.trim(), TITLE_UNITS),
        excerpt = excerpt?.trim()?.takeIf { it.isNotEmpty() }?.let { TextTruncate.clamp(it, EXCERPT_UNITS) },
        meta = TextTruncate.clamp(metaParts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(separator), META_UNITS)
    )
}
