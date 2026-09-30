package com.pricelens.data.repository

import com.pricelens.data.local.DayCurve
import com.pricelens.data.remote.ManmanbuyApi

/**
 * 历史曲线的绘图口径：外源（慢慢买 / 星罗）给的点为骨架，本机自采点补齐外源没覆盖的日期。
 *
 * 为什么要专门把这条规则抽成纯函数：盯价页脚注说的是**库里存的天数**（按 source 分组统计），
 * 而曲线画的是这里返回的点。两边口径不一致时，脚注会宣称"本机盯价自采 4 天"，
 * 图上却一个自采点都没有 —— 用户看到的就是一句对不上号的说明。
 *
 * 同一天外源优先：慢慢买给的是平台侧的日线历史，比本机偶发采样更适合当那条线的骨架。
 * 覆盖写回时本机的「当日至低」不会被抬掉（见
 * [PriceRepository.persistHistory]），丢掉的只是当日的收盘值。
 */
object CurveMerge {

    /**
     * @param external 外源给的历史点（可以为空 = 全靠本机自采）
     * @param self [DayCurve.collapse] 之后的本机自采日点（已按日去重、升序）
     * @return 要画的那条线：按日期升序、一天一个点
     */
    fun fillMissingDays(external: List<ManmanbuyApi.PricePoint>, self: List<DayCurve.Point>): List<ManmanbuyApi.PricePoint> {
        if (self.isEmpty()) return external.sortedBy { it.date }
        val covered = external.mapTo(HashSet()) { it.date }
        val extras = self.filter { it.date !in covered && it.close > 0.0 }
            .map { ManmanbuyApi.PricePoint(it.date, it.close) }
        return (external + extras).sortedBy { it.date }
    }
}
