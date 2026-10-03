package com.pricelens.coupon.slots

import com.pricelens.coupon.model.CouponScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 券范围判定（病根②的另一半，也是"多券混并"的第二道闸：范围不同的券不该并进一张）。
 *
 * 判不出必须是 UNKNOWN —— 默认成 SHOP 等于替用户决定"这张券本店可用"。
 */
class ScopeWordsTest {

    @Test
    fun `会员专享压在店铺券前面（门槛比哪家店更能决定能不能用）`() {
        val clause = "会员专享店铺券满199减20"
        assertEquals(CouponScope.MEMBER, ScopeWords.of(clause, emptyList()))
        assertNotEquals(CouponScope.SHOP, ScopeWords.of(clause, emptyList()))
        // 正例对照：只有店铺词时 SHOP 这条路是活的，不是被 MEMBER 判死的
        assertEquals(CouponScope.SHOP, ScopeWords.of("店铺券满199减20", emptyList()))
    }

    @Test
    fun `判不出是 UNKNOWN，不默认 SHOP`() {
        assertEquals(CouponScope.UNKNOWN, ScopeWords.of("满199减20", emptyList()))
        assertEquals(CouponScope.UNKNOWN, ScopeWords.of("满199减20", listOf("小米 13 12GB+256GB 黑色")))
        // 真机券弹层那一行说的是地区不是范围 ⇒ 整句与祖先都没有范围词时保持 UNKNOWN
        assertEquals(CouponScope.UNKNOWN, ScopeWords.of("当前地区可领，本单可减1500元", emptyList()))
    }

    @Test
    fun `整句没有范围词时才用祖先与同层文案`() {
        assertEquals(CouponScope.PLATFORM, ScopeWords.of("满500减100", listOf("平台券")))
        assertEquals(CouponScope.CATEGORY, ScopeWords.of("满300减50", listOf("品类券", "会场券")))
        // 祖先链里同时有会员词时按优先级取 MEMBER，不取更"靠近页面主体"的店铺
        assertEquals(CouponScope.MEMBER, ScopeWords.of("满300减50", listOf("店铺券", "会员专享")))
    }

    @Test
    fun `PLUS 大小写敏感：商品型号里的 Plus 不许被判成会员券`() {
        // 词表里是大写 `PLUS`（京东/淘宝把品牌名写成大写），型号名是小写混排
        assertEquals(CouponScope.UNKNOWN, ScopeWords.of("iPhone 17 Plus 128G 手机壳", emptyList()))
        assertEquals(CouponScope.MEMBER, ScopeWords.of("PLUS会员专享券", emptyList()))
        // 88vip 是带数字锚点的记法 ⇒ anyCase，大小写两种写法都收（正例对照）
        assertEquals(CouponScope.MEMBER, ScopeWords.of("88VIP满1500减120", emptyList()))
        assertEquals(CouponScope.MEMBER, ScopeWords.of("88vip满1500减120", emptyList()))
    }
}
