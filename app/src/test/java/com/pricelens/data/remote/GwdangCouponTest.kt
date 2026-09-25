package com.pricelens.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 找券提取基线（夹具：2026-09-25 值得买优惠券频道实况页面，已裁剪 7 条）。
 * 回归点：旧实现找不到「满X减Y」时用 原价−到手价 反推券面额，会把国补/PLUS 价
 * 算成"无门槛券"（编造）。现只认显式券文案，拿不到就不返回。
 */
class GwdangCouponTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) { "缺少夹具 $name" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test
    fun `explicit man jian coupon is extracted`() {
        assertEquals(8.0 to 15.0, GwdangApi.extractCoupon("此款目前活动售价19.87元，下单领取满15减8元优惠券，实付低至7.35元"))
        assertEquals(500.0 to 5000.0, GwdangApi.extractCoupon("活动售价6999元，下单领取满5000减500元优惠券"))
        assertEquals(20.0 to 28.0, GwdangApi.extractCoupon("天猫精选此款目前活动售价36.2元，下单领取满28减20元优惠券"))
    }

    @Test
    fun `flat coupon is extracted as no-threshold`() {
        assertEquals(5.0 to 0.0, GwdangApi.extractCoupon("下单领取5元优惠券"))
    }

    @Test
    fun `price gap without coupon wording yields nothing`() {
        // 旧实现会把这两句算成"券后直降 500 / 3"——都是不存在的券
        assertNull(GwdangApi.extractCoupon("目前活动售价5599元，实付低至5099元"))
        assertNull(GwdangApi.extractCoupon("原价 3.9 元，实付 0.9 元"))
        assertNull(GwdangApi.extractCoupon("叠加其他15%折扣，实付低至7.35元"))
    }

    @Test
    fun `parse keeps only rows with explicit coupon and skips others`() {
        val coupons = GwdangApi.parseSearchPage(fixture("smzdm_youhui.html"))
        assertTrue("应至少抽到若干显式券", coupons.size >= 4)
        // 每条都得来自显式券文案（金额>0；满减门槛要么 0 要么大于面额）
        coupons.forEach { c ->
            assertTrue(c.amount > 0)
            assertTrue(c.threshold == 0.0 || c.threshold > c.amount)
        }
        // 实况里 512GB 那条的"满5000减500"应被抽到
        val big = coupons.firstOrNull { it.amount == 500.0 }
        assertNotNull("应抽到满5000减500的券", big)
        assertEquals(5000.0, big!!.threshold, 0.001)
    }
}
