package com.pricelens.ui.price

import androidx.annotation.StringRes
import com.pricelens.R
import com.pricelens.domain.PriceAdvice

/**
 * 购买建议 → 文案资源（纯映射，不碰 Compose，可在 JVM 单测里直接断言）。
 *
 * 与「检测 Cookie」的结论映射同一个套路：判定归 [PriceAdvice]，显示归这里，
 * 保证"每个结论都有话说"且互不串词（UNKNOWN 也不许显示成"好价"）。
 */
@StringRes
internal fun adviceStringRes(advice: PriceAdvice.Advice): Int = when (advice) {
    PriceAdvice.Advice.HIST_LOW -> R.string.advice_hist_low
    PriceAdvice.Advice.GOOD -> R.string.advice_good
    PriceAdvice.Advice.FAIR -> R.string.advice_fair
    PriceAdvice.Advice.HIGH -> R.string.advice_high
    PriceAdvice.Advice.UNKNOWN -> R.string.advice_unknown
}
