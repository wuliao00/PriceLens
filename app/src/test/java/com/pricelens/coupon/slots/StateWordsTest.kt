package com.pricelens.coupon.slots

import com.pricelens.accessibility.dumpTexts
import com.pricelens.accessibility.loadRealDump
import com.pricelens.coupon.model.CouponState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 病根②「状态词失明」的判据测试。
 *
 * 两条纪律各有正反例：**弱信号不许盖强信号**（优先级），**整句判不出才看祖先**（顺序），
 * 两处都判不出必须是 UNKNOWN 而不是"假设能领"（默认值才是最贵的那种错）。
 */
class StateWordsTest {

    @Test
    fun `已领完 压在 立即领 前面，弱信号不许盖强信号`() {
        val clause = "已领完，立即领下一个"
        assertEquals(CouponState.SOLD_OUT, StateWords.of(clause, emptyList()))
        // 只断言"SOLD_OUT"看不出优先级是否真在跑：这两条把"展示成能领"的错钉死
        assertNotEquals(CouponState.CLAIMABLE, StateWords.of(clause, emptyList()))
        assertNotEquals(CouponState.CLAIMED, StateWords.of(clause, emptyList()))
    }

    @Test
    fun `真机原文的当前地区判成限地区而不是可领`() {
        // 夹具 jd_detail_guobu_popup_plb110_20261003.xml 里的券弹层按钮那一行
        val real = dumpTexts(loadRealDump("jd_detail_guobu_popup_plb110_20261003.xml").root)
        val line = real.first { it.contains("可减1500") }
        assertEquals("当前地区可领，本单可减1500元", line)
        assertEquals(CouponState.REGION_LIMITED, StateWords.of(line, emptyList()))
        assertNotEquals(CouponState.CLAIMABLE, StateWords.of(line, emptyList()))
        // 同一棵真机树上的按钮原文确实是弱信号，它自己那行判得出 CLAIMABLE（正例对照）
        val button = real.first { it.contains("立即领取") }
        assertEquals(CouponState.CLAIMABLE, StateWords.of(button, emptyList()))
    }

    @Test
    fun `整句判不出时用祖先与同层文案，券自己那行仍然赢`() {
        // 券那一行没有状态词，外层容器写"会员专享" ⇒ 用祖先
        assertEquals(CouponState.MEMBER_ONLY, StateWords.of("满199减50", listOf("会员专享")))
        // 同层"领取"按钮是券卡片常见的兄弟节点（不是祖先）⇒ 也算上下文
        assertEquals(CouponState.CLAIMABLE, StateWords.of("满199减50", listOf("立即领取")))
        // 券自己那行有强信号时，不许被外层的弱信号盖掉（顺序：先整句，再上下文）
        assertEquals(CouponState.SOLD_OUT, StateWords.of("已领完", listOf("立即领取")))
    }

    @Test
    fun `两处都判不出是 UNKNOWN，绝不默认 CLAIMABLE`() {
        assertEquals(CouponState.UNKNOWN, StateWords.of("满199减50", emptyList()))
        // 上下文里只有商品名（真机祖先链上最常见的噪声）也不许造出状态
        assertEquals(CouponState.UNKNOWN, StateWords.of("满199减50", listOf("小米 13 12GB+256GB 黑色")))
        // 正例对照：同一份上下文换成真状态词就必须判得出（否则 UNKNOWN 是"永远 UNKNOWN"）
        assertEquals(CouponState.EXPIRED, StateWords.of("满199减50", listOf("该券已过期")))
    }

    @Test
    fun `过期与售罄都压在可领前面`() {
        assertEquals(CouponState.EXPIRED, StateWords.of("今天过期，立即领", emptyList()))
        assertEquals(CouponState.SOLD_OUT, StateWords.of("抢光了就点击领取", emptyList()))
    }
}
