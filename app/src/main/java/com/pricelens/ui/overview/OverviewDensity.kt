package com.pricelens.ui.overview

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.pricelens.R
import com.pricelens.data.remote.ManmanbuyApi
import com.pricelens.ui.components.EmptyText
import com.pricelens.ui.components.PriceBadge
import com.pricelens.ui.components.PriceCard
import com.pricelens.ui.components.SectionHeader
import com.pricelens.ui.layout.CompactTileRow
import com.pricelens.ui.layout.QuoteGroup
import com.pricelens.ui.layout.QuoteState
import com.pricelens.ui.layout.SourceQuote
import com.pricelens.ui.price.PriceChartCanvas
import com.pricelens.ui.theme.BadgeTone
import com.pricelens.ui.theme.Dims
import com.pricelens.ui.theme.PriceType
import com.pricelens.util.PriceFormatter

/**
 * 概览首屏的密度组件（2026-10-02 排版 / 信息密度 / 版面层级大改）。
 *
 * 单独成文件而不是塞满 OverviewScreen.kt：那个文件同批还要被另一个代理加 onOpenProduct 参数，
 * 新增符号按"一个文件一件事"聚拢在这里，合并时不会互相踩。
 */

/**
 * 各源报价 —— 单行紧凑列表（替换原先"一叠高卡片"）。
 *
 * 行的四个段位：**源名 · 价 · 时间戳/条数 · 状态徽标**。
 * 最坏负载核算（真机 560dpi、内容宽 302.86dp，全部算术在 com.pricelens.ui.layout.RowBudget）：
 *  源名 84（=6 个 14sp 汉字，由 SourceQuotes.LABEL_UNITS 收敛）
 *  + 价 70（"¥12,999" 8 个等宽数字 @16sp）
 *  + 徽标 52（"无结果" 3 个 12sp 汉字 + 左右内边距）
 *  + 三处段间距 24 = **230dp**，余 **72dp** 给时间戳段（"2026-10-01" @12sp ≈ 66dp，放得下）。
 *
 * 时间戳是行内唯一的**弹性段**（weight + maxLines=1 + Ellipsis）：系统"大字号"档（1.15）下先压它，
 * 源名与徽标的位宽不动——刻意的取舍：看不清"这行是哪个源"比看不清"几小时前"严重得多。
 *
 * 行数 = 有价的源数 + 异常行数（IDLE 不占行）。概览实测 3~5 行 × [Dims.RowCompact] = 180~300dp，
 * 替掉原来的「三张 FactCard（各含 12+20dp 文本 + 16dp 卡片内边距）+ 80dp 头图」，
 * 首屏能同时看到 头卡 / 各源报价 / 走势条 三件事。
 */
@Composable
fun CompactQuoteList(groups: List<QuoteGroup>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        SectionHeader(stringResource(R.string.layout_overview_quotes_title))
        groups.forEachIndexed { index, group ->
            // 段间留白比行间距高一档：有价段与诊断段之间的"停顿"靠留白认，不画分割线
            if (index > 0) Spacer(Modifier.height(Dims.SpacingM))
            group.rows.forEach { row ->
                QuoteRow(row)
                Spacer(Modifier.height(Dims.SpacingXS))
            }
        }
    }
}

@Composable
private fun QuoteRow(quote: SourceQuote) {
    val scheme = MaterialTheme.colorScheme
    val diagnostic = quote.state != QuoteState.PRICED || quote.priceText == null
    CompactTileRow {
        // ① 源名：预算已由 SourceQuotes 收敛到 6 个汉字，这里的 maxLines 只是兜底
        Text(
            quote.label,
            style = MaterialTheme.typography.bodyLarge,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        // ② 价（等宽数字：同一列上下行的小数位对齐，扫读时才知道差多少）
        Text(
            quote.priceText ?: stringResource(R.string.layout_quote_no_price),
            style = PriceType.PriceRowCompact,
            color = if (diagnostic) scheme.onSurfaceVariant else scheme.primary,
            maxLines = 1
        )
        // ③ 时间戳（没有就退到"出处 / N 条"；两者都没有就让 weight 把徽标顶到行尾）
        val context = quote.stampText ?: quote.note
        if (context == null) {
            Spacer(Modifier.weight(1f))
        } else {
            Text(
                context,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        // ④ 状态徽标：够着没够着、反爬还是失败，一句话说清
        PriceBadge(quoteStateText(quote.state), quoteStateTone(quote.state))
    }
}

/** 状态徽标文案：与概览顶部那排源状态徽标共用同一批字符串，不另起一套说法 */
@Composable
private fun quoteStateText(state: QuoteState): String = stringResource(
    when (state) {
        QuoteState.PRICED -> R.string.src_state_ok
        QuoteState.LOADING -> R.string.src_state_loading
        QuoteState.NO_RESULT -> R.string.src_state_empty
        QuoteState.FAILED -> R.string.src_state_failed
        QuoteState.BLOCKED -> R.string.src_state_blocked
        QuoteState.IDLE -> R.string.src_state_idle
    }
)

@Composable
private fun quoteStateTone(state: QuoteState): BadgeTone = when (state) {
    QuoteState.PRICED -> BadgeTone.POSITIVE
    QuoteState.BLOCKED, QuoteState.FAILED -> BadgeTone.NEGATIVE
    QuoteState.LOADING, QuoteState.NO_RESULT, QuoteState.IDLE -> BadgeTone.NEUTRAL
}

/**
 * 概览走势条：保留曲线，但降到 [Dims.CurveCompact]（详情大曲线在盯价页的 200dp，这里不重复占屏）。
 *
 * 点不足两条时不摆一块空白框冒充图表——那看起来像"又显示错了"，实情只是采样日还没攒够，
 * 于是把已有的那一点如实说出来（与盯价页同一条红线）。
 */
@Composable
fun CurveStripCard(history: ManmanbuyApi.History?, modifier: Modifier = Modifier) {
    if (history == null) return
    PriceCard(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.layout_overview_curve_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Text(
                stringResource(R.string.layout_overview_header_lowest, PriceFormatter.format(history.lowest)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(Dims.SpacingS))
        if (history.points.size < 2) {
            val only = history.points.firstOrNull()
            EmptyText(stringResource(R.string.layout_overview_curve_none, only?.date ?: "—"))
        } else {
            PriceChartCanvas(
                history = history,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Dims.CurveCompact)
            )
        }
    }
}
