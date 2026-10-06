package com.pricelens.domain

import com.pricelens.data.remote.GwdangApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 到手价判据（**门槛没过的那张不许霸占整条判据**）。
 *
 * 与 domain 里其它纯判据同一口径：正反例都钉，只断言一半会把"整句一个标签"那类错看成药到病除。
 */
class NetPriceTest {

    private fun coupon(amount: Double, threshold: Double) = GwdangApi.Coupon(amount = amount, threshold = threshold, title = "t", url = "u")

    @Test
    fun `能用上的券里面额最大的那张赢，而不是面额最大的那张`() {
        val coupons = listOf(coupon(200.0, 1000.0), coupon(50.0, 0.0))
        // ¥300 用不到满1000减200，但无门槛 50 能用 —— 旧写法在这里返回 null，
        // 界面于是写着"未达门槛，暂无可算的到手价"，那是句假话
        assertEquals(250.0, NetPrice.of(300.0, coupons)!!, 0.0)
        // 同一个列表，价格涨到凑得到 200 那张的门槛时，减得多的那张才是用户会选的
        assertEquals(1000.0, NetPrice.of(1200.0, coupons)!!, 0.0)
    }

    @Test
    fun `一张都用不上时如实算不出，但不许算出零或负数`() {
        assertNull(NetPrice.of(50.0, listOf(coupon(200.0, 1000.0))))
        assertNull(NetPrice.of(100.0, emptyList()))
        // 面额比整单价还大：数据错位，宁可算不出也不给一个负的"到手价"
        assertNull(NetPrice.of(30.0, listOf(coupon(50.0, 0.0))))
        assertNull(NetPrice.of(0.0, listOf(coupon(50.0, 0.0))))
    }

    @Test
    fun `门槛等于商品价算用得上（边界是闭区间）`() {
        assertEquals(50.0, NetPrice.of(100.0, listOf(coupon(50.0, 100.0)))!!, 0.0)
        assertNull(NetPrice.of(99.9, listOf(coupon(50.0, 100.0))))
    }
}
