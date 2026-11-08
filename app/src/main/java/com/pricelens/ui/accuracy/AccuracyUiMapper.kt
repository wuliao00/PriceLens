package com.pricelens.ui.accuracy

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.pricelens.R
import com.pricelens.accuracy.AccuracyIssueKind
import com.pricelens.accuracy.AccuracySnapshot
import com.pricelens.accuracy.ConfidenceLevel
import com.pricelens.accuracy.PriceBasis
import com.pricelens.accuracy.SourceState
import com.pricelens.ui.components.SourceHealthCell
import com.pricelens.ui.components.SourceHealthState
import kotlin.math.roundToInt

/**
 * 准确性引擎 → UI 展示模型的**唯一映射点**（2026-11 UI 大改）。
 *
 * 为什么是一个 @Composable 函数而不是普通 object：
 *  - 证据口径（[PriceBasis]）的文案必须走 res/values（风格约束：代码里不写裸中文），
 *    而 `stringResource` 只能在组合期调用；把它放在映射层，屏幕侧就只需要一行 `accuracyViewOf(snapshot)`。
 *  - 引擎字段 → UI 模型的投影规则（可空价格、毫秒年龄、0..1 相关度）集中在这里，
 *    以后引擎字段再变也只改这一处。
 *
 * 契约以 `com.pricelens.accuracy.AccuracyModels.kt` 源码为准（字段名与本文件一致）：
 *  - level / score / headline / reasons / basisLabel / evidence / conflicts / sourceBoard / generatedAtMs
 *  - PriceEvidence(price: Double?, ageMs: Long?, relevance: Double 0..1)
 *  - SourceCell(state: SourceState)
 */
@Composable
fun accuracyViewOf(snapshot: AccuracySnapshot?): AccuracyView? {
    snapshot ?: return null
    return AccuracyView(
        level = snapshot.level.toView(),
        score = snapshot.score,
        headline = snapshot.headline,
        reasons = snapshot.reasons,
        basisLabel = snapshot.basisLabel,
        evidence = snapshot.evidence.map { item ->
            PriceEvidenceView(
                sourceId = item.sourceId,
                label = item.label,
                price = item.price?.takeIf { it.isFinite() && it > 0.0 },
                basisLabel = stringResource(priceBasisLabelRes(item.basis)),
                ageMs = item.ageMs,
                stale = item.stale,
                // 引擎给 0..1；UI 展示百分比整数（四舍五入到 1%）
                relevance = (item.relevance.coerceIn(0.0, 1.0) * 100).roundToInt()
            )
        },
        conflicts = snapshot.conflicts.map { AccuracyConflictView(it.label, it.detail) },
        sources = snapshot.sourceBoard.map { cell ->
            val state = cell.state.toView()
            SourceHealthCell(
                sourceId = cell.sourceId,
                label = cell.label,
                state = state,
                // 引擎 note 可能是「网络失败: SourceUnreachableException…」这类原文：
                // 展示前归一成人话（反爬拦截/网络不可达/请求超时/HTTP 5xx），未知一律「失败」；
                // 状态本身已表达"不可达"时不再重复写同义泛词（真机首轮出现过「降级 · 网络失败: 类名…」）。
                note = normalizedSourceNote(cell.note, state)
            )
        },
        generatedAtMs = snapshot.generatedAtMs
    )
}

/** 「这条不对」的 UI 口径 → 引擎纠错类型（方向唯一：UI 不反向依赖引擎枚举做判断） */
fun AccuracyIssueView.toEngineKind(): AccuracyIssueKind = when (this) {
    AccuracyIssueView.WRONG_PRICE -> AccuracyIssueKind.WRONG_PRICE
    AccuracyIssueView.WRONG_PRODUCT -> AccuracyIssueKind.WRONG_PRODUCT
    AccuracyIssueView.STALE -> AccuracyIssueKind.STALE
    AccuracyIssueView.WRONG_BASIS -> AccuracyIssueKind.WRONG_BASIS
}

private fun ConfidenceLevel.toView(): ConfidenceLevelView = when (this) {
    ConfidenceLevel.HIGH -> ConfidenceLevelView.HIGH
    ConfidenceLevel.MEDIUM -> ConfidenceLevelView.MEDIUM
    ConfidenceLevel.LOW -> ConfidenceLevelView.LOW
    ConfidenceLevel.UNKNOWN -> ConfidenceLevelView.UNKNOWN
}

private fun SourceState.toView(): SourceHealthState = when (this) {
    SourceState.OK -> SourceHealthState.OK
    SourceState.DEGRADED -> SourceHealthState.DEGRADED
    SourceState.BLOCKED -> SourceHealthState.BLOCKED
    SourceState.STALE -> SourceHealthState.STALE
    SourceState.UNKNOWN -> SourceHealthState.UNKNOWN
}

/** 证据行里的口径名（与主口径 basisLabel 同一批说法，不另起一套） */
private fun priceBasisLabelRes(basis: PriceBasis): Int = when (basis) {
    PriceBasis.PAGE -> R.string.rd_basis_page
    PriceBasis.COUPON_AFTER -> R.string.rd_basis_coupon_after
    PriceBasis.NET -> R.string.rd_basis_net
    PriceBasis.REFERENCE -> R.string.rd_basis_reference
    PriceBasis.UNKNOWN -> R.string.rd_basis_unknown
}

/**
 * 来源健康板 note 归一化：引擎 note 可能带异常类名/英文（真机首轮：`网络失败: SourceUnreacha…`）。
 * 规则：能识别的异常映射成人话；引擎已写好的纯中文诊断原样保留；认不出的一律「失败」；
 * 状态本身已写「不可达」时不再追加同义的「失败」。
 */
@Composable
private fun normalizedSourceNote(rawNote: String?, state: SourceHealthState): String? {
    val raw = rawNote?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val lower = raw.lowercase()
    val mapped = when {
        raw.contains("反爬") || lower.contains("blocked") || raw.contains("403") || raw.contains("412") ->
            R.string.rd_source_note_blocked
        lower.contains("timeout") || raw.contains("超时") -> R.string.rd_source_note_timeout
        lower.contains("sourceunreach") || lower.contains("unreachable") || lower.contains("ioexception") ||
            lower.contains("network") || raw.contains("网络") -> R.string.rd_source_note_network
        raw.contains("HTTP 5") || raw.contains("HTTP5") -> R.string.rd_source_note_http5
        // 引擎已给纯中文诊断（如「本轮未读取页面实时价」「本轮未查询」）：原样展示
        raw.all { it.code > 127 } -> return raw
        else -> R.string.rd_source_note_failed
    }
    if (state == SourceHealthState.BLOCKED && mapped == R.string.rd_source_note_failed) return null
    return stringResource(mapped)
}
