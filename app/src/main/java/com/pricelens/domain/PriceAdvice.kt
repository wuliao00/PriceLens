package com.pricelens.domain

/**
 * 购买建议：把"历史分位"翻译成一句能拍板的话（文档 §10）。
 *
 * 定位：曲线只是展示，**决策**才是这个 App 的差异点。用同一批历史点算"当前价处在什么位置"，
 * 比"当前价 vs 历史最低"更稳定 —— 后者只看极值，一两天的异常低价会把常年正常价判成"贵"。
 *
 * 口径（写死在此，UI 不许自己另算）：
 *  - 样本 < [MIN_SAMPLES] 个日点 → 不判断（数据不足时给结论就是编）；
 *  - 当前价距历史最低 ≤2% → 接近历史低价；
 *  - 否则按分位：≤20% 好价 / ≤50% 居中 / >50% 偏高。
 */
object PriceAdvice {

    /** 少于 8 个日点不判断：样本太少时任何分位都是噪声 */
    const val MIN_SAMPLES = 8

    /** 距历史最低 2% 以内算"接近历史低价"（容忍采样误差与几元波动） */
    private const val NEAR_LOWEST_RATIO = 1.02

    enum class Advice {
        /** 接近历史最低 */
        HIST_LOW,

        /** 分位 ≤ 20%：好价区间 */
        GOOD,

        /** 分位 ≤ 50%：居中 */
        FAIR,

        /** 分位 > 50%：偏高，建议等促销 */
        HIGH,

        /** 数据不足 / 价格非法：不判断 */
        UNKNOWN
    }

    /** 当前价在历史里的分位（0..100，越大越贵）；样本不足或价格非法 → null */
    fun percentile(current: Double, history: List<Double>): Int? {
        val clean = history.filter { it > 0.0 }
        if (clean.size < MIN_SAMPLES || current <= 0.0) return null
        val rank = clean.count { it <= current }.toDouble() / clean.size
        return Math.round(rank * 100).toInt()
    }

    fun advise(current: Double, history: List<Double>): Advice {
        val clean = history.filter { it > 0.0 }
        if (clean.size < MIN_SAMPLES || current <= 0.0) return Advice.UNKNOWN
        val lowest = clean.min()
        if (current <= lowest * NEAR_LOWEST_RATIO) return Advice.HIST_LOW
        return when (percentile(current, clean) ?: return Advice.UNKNOWN) {
            in 0..20 -> Advice.GOOD
            in 21..50 -> Advice.FAIR
            else -> Advice.HIGH
        }
    }
}
