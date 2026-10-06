package com.pricelens.domain

import com.pricelens.data.remote.GwdangApi

/**
 * 「到手价」= 商品价格减去**这一单真能用上的那张券**的面额（§五 找券区的净价判据）。
 *
 * 这条从 SearchViewModel 里提出来，是因为原写法有一个会把假话写进界面的错：它先取
 * **面额最大**的那张券，再看**它自己的门槛**过没过；没过就整个返回 null。
 * 于是 `满1000减200` + `无门槛50` 两张券、商品价 ¥300 时，界面上写着
 * 「当前商品价未达券使用门槛，暂无可算的到手价」—— 而那张无门槛 50 明明能用在 300 上。
 * "算不出净价"与"有一张券能用"是两件事，判据必须分开写，才可能在 JVM 里钉住。
 *
 * 取"能用上的券里面额最大的"而不是"最大的券"：这是用户实际会选的那张
 * （凑得到门槛的券里，减得多的那张更划算；凑不到的那张对他不存在）。
 */
object NetPrice {

    /**
     * @param price 当前商品价格
     * @param coupons 远端券列表（`threshold` 为 0 表示无门槛）
     * @return 算不出就 null —— 调用方**不许**把 null 渲染成 ¥0
     */
    fun of(price: Double, coupons: List<GwdangApi.Coupon>): Double? {
        if (price <= 0.0) return null
        val best = coupons
            .filter { it.threshold <= price }
            .maxByOrNull { it.amount }
            ?: return null
        // 面额大于整单价时不给"负到手价"：那是数据错位（券挂错商品/单位错了），
        // 报一个看不懂的数字比不报更糟
        return (price - best.amount).takeIf { it > 0.0 }
    }
}
