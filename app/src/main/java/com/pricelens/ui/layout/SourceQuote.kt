package com.pricelens.ui.layout

import com.pricelens.data.remote.CrawlerBlockedException
import com.pricelens.data.remote.CrawlerResult
import com.pricelens.ui.common.AsyncValue
import com.pricelens.util.PriceFormatter

/**
 * 报价来自哪一类通道：决定同一状态内的固定次序——
 * 越贴近"用户自己账号在 App 里看到的价格"越靠前（决策权重最高），外站补充源靠后。
 */
enum class QuoteKind(val rank: Int) {
    /** 无障碍读到的本机登录账号实时价 */
    ACCOUNT(0),

    /** 主候选现价（京东直查 / 当当） */
    MAIN(1),

    /** 慢慢买历史价（现价口径；历史最低另在头卡那一行） */
    HISTORY(2),

    /** 券后到手价 */
    COUPON(3),

    /** 识货报价（外站补充源） */
    SHIHUO(4),

    /** 值得买爆料价（外站补充源） */
    POST(5)
}

/**
 * 这一行的结局。与 `ui/components/SourceStatusRow` 的徽标同源，但对"有没有价"敏感：
 * 徽标讲的是"这个源够着没够着"，报价行讲的是"这一行值不值得占一行版面"。
 */
enum class QuoteState(val rank: Int) {
    PRICED(0),
    LOADING(1),
    NO_RESULT(2),
    FAILED(3),
    BLOCKED(4),

    /** 本轮根本没查过：不占版面，[SourceQuotes.of] 直接丢弃 */
    IDLE(5)
}

/** 报价行输入（[QuoteInputs.of] 的产物，纯数据） */
data class QuoteInput(
    val kind: QuoteKind,
    val label: String,
    val price: Double?,
    val state: QuoteState,
    val stamp: String? = null,
    val note: String? = null
)

/** 渲染用的报价行：源名/价/时间戳/备注都已按版面预算收敛 */
data class SourceQuote(
    val kind: QuoteKind,
    val label: String,
    val price: Double?,
    val priceText: String?,
    val stampText: String?,
    val note: String?,
    val state: QuoteState
)

/** 报价列表的两段：有价的在前，无价/异常的沉底但**保留**（失败不是没发生） */
enum class QuoteSection { PRICED, DIAGNOSTIC }

/** 一段报价行（分组的渲染单位） */
data class QuoteGroup(val section: QuoteSection, val rows: List<SourceQuote>)

/** 各通道的显示文案（由屏幕用 stringResource 填，纯函数层不碰资源） */
data class QuoteLabels(
    val account: String,
    val main: String,
    val history: String,
    val coupon: String,
    val shihuo: String,
    val post: String,
    val countWord: String
) {
    fun forKind(kind: QuoteKind): String = when (kind) {
        QuoteKind.ACCOUNT -> account
        QuoteKind.MAIN -> main
        QuoteKind.HISTORY -> history
        QuoteKind.COUPON -> coupon
        QuoteKind.SHIHUO -> shihuo
        QuoteKind.POST -> post
    }
}

/**
 * 某个源这一轮的观测。
 *
 *  - [price] 为 null 或 ≤0 都不算"有价"（2026-09 那条教训：京东公开通道不可达时会给出 ¥0，
 *    展示 ¥0 比不展示更误导）；
 *  - [attribution] 是"这个价是谁给的"（如"本机京东 App 登录账号"），进备注而不是源名——
 *    源名是版面最紧的字段（见 [RowBudget]）；
 *  - [itemCount] > 1 时说明这一行代表 N 条同通道报价（取最低），必须说清是最低而不是唯一。
 */
data class SourceObservation(
    val kind: QuoteKind,
    val price: Double?,
    val state: QuoteState,
    val stamp: String? = null,
    val itemCount: Int = 0,
    val attribution: String? = null
)

/** 观测 → 行输入（规则全部在这里，屏幕侧不再判断价与状态） */
object QuoteInputs {
    fun of(labels: QuoteLabels, observations: List<SourceObservation>): List<QuoteInput> = observations.map { o ->
        val usable = o.price?.takeIf { it > 0.0 }
        QuoteInput(
            kind = o.kind,
            label = labels.forKind(o.kind),
            price = usable,
            state = if (usable == null && o.state == QuoteState.PRICED) QuoteState.NO_RESULT else o.state,
            stamp = o.stamp,
            note = noteOf(o.attribution, o.itemCount, labels.countWord)
        )
    }

    /** 备注 = 出处 + "N 条"（都缺失时返回 null，不摆一个空的括号） */
    private fun noteOf(attribution: String?, itemCount: Int, countWord: String): String? {
        val parts = listOfNotNull(
            attribution?.trim()?.takeIf { it.isNotEmpty() },
            if (itemCount > 1) "$itemCount $countWord" else null
        )
        return parts.joinToString(" ").takeIf { it.isNotEmpty() }
    }
}

/**
 * 各源报价 → 单行紧凑列表的排序 / 分组 / 收敛（纯函数）。
 *
 * 版面约束（最坏负载核算见 [RowBudget]）：源名以 [LABEL_UNITS] 个 14sp 汉字封顶，
 * 时间戳与备注同样按 12sp 行收敛；Compose 侧另有 maxLines=1 + Ellipsis 兜底，双保险不溢出。
 */
object SourceQuotes {
    /** 源名预算：由 RowBudget 按真机最窄可用宽派生（不是拍脑袋的数字） */
    val LABEL_UNITS: Int = RowBudget.labelUnits()

    /** 时间戳预算：绝对日期 "2026-10-01" 恰好 10 字符 */
    val STAMP_UNITS: Int = RowBudget.lineUnits(12f)

    /** 备注预算 */
    val NOTE_UNITS: Int = RowBudget.lineUnits(12f)

    /**
     * 输入 → 可渲染行：
     *  1. 丢弃本轮未查询的源（IDLE 不占版面）；
     *  2. 文案收敛到版面预算；
     *  3. 按 (状态, 通道, 价格) 排序——有价在前、失败沉底、同通道便宜在前、缺价排最后。
     * 排序用稳定排序（Kotlin sortedWith 稳定），同键保持输入顺序，重组时行不会跳。
     */
    fun of(inputs: List<QuoteInput>, priceFormat: (Double) -> String = PriceFormatter::format): List<SourceQuote> =
        inputs.filter { it.state != QuoteState.IDLE }
            .map {
                SourceQuote(
                    kind = it.kind,
                    label = TextTruncate.clamp(it.label, LABEL_UNITS),
                    price = it.price,
                    priceText = it.price?.let(priceFormat),
                    stampText = it.stamp?.trim()?.takeIf { s -> s.isNotEmpty() }?.let { s -> TextTruncate.clamp(s, STAMP_UNITS) },
                    note = it.note?.trim()?.takeIf { s -> s.isNotEmpty() }?.let { s -> TextTruncate.clamp(s, NOTE_UNITS) },
                    state = it.state
                )
            }
            .sortedWith(compareBy({ it.state.rank }, { it.kind.rank }, { it.price ?: Double.MAX_VALUE }))

    /** 分成「有价」与「诊断」两段；空段不返回（不给列表留一条只有分隔线的段） */
    fun sections(quotes: List<SourceQuote>): List<QuoteGroup> {
        val priced = quotes.filter { it.state == QuoteState.PRICED && it.priceText != null }
        val rest = quotes.filterNot { it.state == QuoteState.PRICED && it.priceText != null }
        return listOfNotNull(
            priced.takeIf { it.isNotEmpty() }?.let { QuoteGroup(QuoteSection.PRICED, it) },
            rest.takeIf { it.isNotEmpty() }?.let { QuoteGroup(QuoteSection.DIAGNOSTIC, it) }
        )
    }

    /**
     * AsyncValue（+ 域名诊断结果）→ 行状态。
     * 判据与 `sourceChipStateOf` 一致，但**不共用**那个函数：它归另一个代理，
     * 语义是"够着没够着"，这里要多带一层"有没有价"（交给 [QuoteInputs.of] 判 0 价）。
     */
    fun stateOf(value: AsyncValue<*>, outcome: CrawlerResult<String>? = null): QuoteState = when (value) {
        is AsyncValue.Loading -> QuoteState.LOADING
        is AsyncValue.Idle -> QuoteState.IDLE
        is AsyncValue.Error ->
            if (value.cause is CrawlerBlockedException || outcome is CrawlerResult.Blocked) QuoteState.BLOCKED else QuoteState.FAILED
        is AsyncValue.Success -> if ((value.data as? Collection<*>)?.isEmpty() == true) QuoteState.NO_RESULT else QuoteState.PRICED
    }
}
