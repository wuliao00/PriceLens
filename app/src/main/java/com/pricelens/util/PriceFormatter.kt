package com.pricelens.util

import java.text.DecimalFormat

/** 价格格式化 + §6.2 价格判断逻辑 */
object PriceFormatter {

    private val grouped = DecimalFormat("#,##0.##")

    /** 7999.0 → "¥7,999" */
    fun format(price: Double): String = "¥" + grouped.format(price)

    fun formatRaw(price: Double): String = grouped.format(price)
}

/** §6.2 价格判断结果 */
sealed class PriceJudgment(val label: String) {
    class LOW : PriceJudgment("≈ 历史低价")
    class SUSPICIOUS : PriceJudgment("⚠ 疑似先涨后降")
    class NORMAL : PriceJudgment("常规价格")
}

fun judgePrice(current: Double, history: List<Double>): PriceJudgment {
    if (history.isEmpty()) return PriceJudgment.NORMAL()
    val lowest = history.min()
    val avg7d = history.takeLast(7).average()
    return when {
        current <= lowest * 1.05 -> PriceJudgment.LOW()
        current >= avg7d * 1.10 -> PriceJudgment.SUSPICIOUS()
        else -> PriceJudgment.NORMAL()
    }
}
