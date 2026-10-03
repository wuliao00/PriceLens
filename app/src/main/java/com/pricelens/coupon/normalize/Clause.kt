package com.pricelens.coupon.normalize

/**
 * 一句**独立进抽取**的文案（三入口统一的最小处理单位）。
 *
 * 为什么要这个类型而不是 `List<String>`：抽取必须带上"这句话是从哪儿来的"，
 * 而两类来源信息在后续任务里各有消费者：
 *  - [nodePath] / [ancestors]：核验层要沿路径回点复探，祖先文案还要参与状态/范围判定；
 *  - [marker]：社区帖的 `步骤 / ①②③` 决定"这张券是第几步领的"，分句时**不许丢**；
 *  - [stackNote]：叠券关系（`叠plus 200-30`）不是券槽位，但必须带出去给展示层；
 *  - [stale]：历史信息标记（发帖 >30 天），置信衰减的依据。
 *
 * 文本入口（剪贴板 / 社区帖）的 [nodePath] 恒为空表 —— 这个不对称是有意的，
 * `CouponExtractorTest` 拿它反证"三入口走的确实是同一条流水线"。
 */
data class Clause(
    val text: String,
    val marker: String? = null,
    val nodePath: List<Int> = emptyList(),
    val ancestors: List<String> = emptyList(),
    val stackNote: String? = null,
    val stale: Boolean = false
)

/**
 * 分句：**每句独立进抽取**，这是治"多券混并"的第一道闸。
 *
 * 一段文案里"店铺券满100减10；品类券满300减50"如果被当成一句处理，两条券的门槛与面额
 * 会被交叉配对成一张不存在的券。分隔符按任务书口径固定为
 * 换行 / `；` / `;` / `。` —— **不收 `，`**：京东真机文案 `当前地区可领，本单可减1500元`
 * 是一个语义单元里的两个分句，按 `，` 切开会把"可领"和"可减 1500"拆成两句，
 * 状态与金额分开后反而**造出**一张"能领的 1500 券"（这一条在 `ClausesTest` 里有钉子）。
 * 同一句里出现两张券（`满199减50 满299减80`）的情况由抽取层按数字配对拆成两张，
 * 见 `CouponPipeline`。
 *
 * **调用方喂原文，别先整段规整**：本对象对每一片各跑一次 `Normalize.text`（零宽、全角、
 * emoji 都在那一趟里治），而 `Normalize.text` 会把 `\s+`（**含换行**）压成一个空格 ——
 * 调用方先整段规整，就等于在分句之前把换行这条分句符删掉，多行文案被并成一句。
 */
object Clauses {

    /** 分句符：换行（含 CR）+ 中英分号 + 句号；`+` 让连续分隔符只切一次 */
    private val SEPARATORS = Regex("[\n\r；;。]+")

    /** 整段只是一个结构标记（`步骤1` / `①` / `1)` / `1、`）：它该跟下一句合并，而不是单独成句 */
    private val MARKER_ONLY = Regex("^(?:步骤\\s*\\d+[:：]?|第\\s*\\d+\\s*步[:：]?|[①-⑳]|\\d+[)）、.])$")

    /** 句首的结构标记（`①满199减50` 的 `①`）：留在 [Clause.text] 里，同时单独记一份给 PostAdapter */
    private val LEADING_MARKER = Regex("^(?:步骤\\s*\\d+[:：]?|第\\s*\\d+\\s*步[:：]?|[①-⑳]|\\d+[)）、.])")

    /** 分句 + 逐句规整（[Normalize.text]）；空句丢弃，**不产生空 [Clause]** */
    fun split(raw: String): List<Clause> {
        val out = ArrayList<Clause>()
        var pendingMarker: String? = null
        for (piece in SEPARATORS.split(raw)) {
            val text = Normalize.text(piece)
            if (text.isEmpty()) continue
            if (MARKER_ONLY.matches(text)) {
                pendingMarker = text
                continue
            }
            val marker = pendingMarker ?: LEADING_MARKER.find(text)?.value
            out.add(Clause(text = text, marker = marker))
            pendingMarker = null
        }
        // 只有标记没有正文（"步骤3"是最后一行）：保留标记本身，抽取层拿不到金额自然不出券
        pendingMarker?.let { out.add(Clause(text = it, marker = it)) }
        return out
    }
}
