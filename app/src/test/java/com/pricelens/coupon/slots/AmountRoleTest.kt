package com.pricelens.coupon.slots

import com.pricelens.coupon.model.AmountRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 病根①「金额槽位混淆」的判据测试。
 *
 * 这一层治的是"五种数字混成一锅"：同一个句子里的两个数字必须**各自**判角色
 * （`满4999减300` 里 4999 是门槛、300 是面额），所以每条用例都同时钉住
 * "该判成什么"和"不许判成什么"，只断言一半的量具会把"整句一个标签"的旧错看成药到病除。
 */
class AmountRoleTest {

    @Test
    fun `两个数字在同一句里各自判对，而不是共用整句标签`() {
        val clause = "满4999减300"
        // 4999 之前只有 `满`；300 之前最近的是 `减`（整句判法只能给一个标签，必错一个）
        assertEquals(AmountRole.THRESHOLD, AmountRole.of(clause, 1))
        assertEquals(AmountRole.DISCOUNT, AmountRole.of(clause, 6))
        // 反方向：`满` 不能把 300 也染成门槛，`减` 也不能把 4999 染成面额
        assertNotEquals(AmountRole.DISCOUNT, AmountRole.of(clause, 1))
        assertNotEquals(AmountRole.THRESHOLD, AmountRole.of(clause, 6))
    }

    @Test
    fun `无门槛50元券的 50 判成面额而不是门槛（同下标取长词）`() {
        val clause = "无门槛50元券"
        // `无门槛`(DISCOUNT) 与 `门槛`(THRESHOLD) 的结束下标都是 3 ⇒ 取长词
        assertEquals(AmountRole.DISCOUNT, AmountRole.of(clause, 3))
        // 正例的反面：只写 `门槛` 时它确实该是门槛，证明"取长词"没有把 THRESHOLD 这条路判死
        assertEquals(AmountRole.THRESHOLD, AmountRole.of("门槛50元", 2))
    }

    @Test
    fun `本单可减1500 是券面额而不是降幅`() {
        val clause = "本单可减1500"
        assertEquals(AmountRole.DISCOUNT, AmountRole.of(clause, 4))
        // `减` 与 `降` 只差一个字，判成 DROP 就等于把券展示成"降了 1500"
        assertNotEquals(AmountRole.DROP, AmountRole.of(clause, 4))
    }

    @Test
    fun `到手与原价与降幅各归各槽`() {
        assertEquals(AmountRole.FINAL, AmountRole.of("到手149", 2))
        assertEquals(AmountRole.LIST, AmountRole.of("原价5499", 2))
        assertEquals(AmountRole.DROP, AmountRole.of("降了300", 2))
        // 降幅那条不能被 `比原价` 抢成 LIST：`便宜` 离数字更近（结束下标更大）
        assertEquals(AmountRole.DROP, AmountRole.of("比原价还便宜300", 6))
    }

    @Test
    fun `判不出就是 null，绝不默认成 DISCOUNT`() {
        // 数字前面没有任何角色词（`库存` 还是 excludedNumberWords 里的词）
        assertNull(AmountRole.of("库存5000件", 2))
        // 数字在句首：之前没有"词"可看
        assertNull(AmountRole.of("50元券", 0))
        // 空句
        assertNull(AmountRole.of("", 3))
        // 正例对照：同一把尺子在真有角色词时必须判得出（否则 null 是"永远返回 null"的假绿）
        assertEquals(AmountRole.DISCOUNT, AmountRole.of("立减50", 2))
    }

    @Test
    fun `只往数字前面看，后面的词不算`() {
        // `1000` 之前只有 `到手价` ⇒ FINAL；后面的 `满` 不该把它拽回门槛
        val clause = "到手价1000满500减50"
        assertEquals(AmountRole.FINAL, AmountRole.of(clause, 3))
        assertEquals(AmountRole.THRESHOLD, AmountRole.of(clause, 8))
        assertEquals(AmountRole.DISCOUNT, AmountRole.of(clause, 12))
    }

    /**
     * 「国家补贴500元」：替身说法落在数字**左侧**，所以归这把尺子管
     * （评测集 cm-youhui-06 里那张 500 一直是漏检，就缺这个词）。
     * 右侧的替身说法（`17元外卖餐补`）**不在这里判** —— 尺子只往前看是它的本职，
     * 那一类由模板 `subsidy-tail` 认领，见上面"只往数字前面看"与 CouponTemplatesTest。
     */
    @Test
    fun `补贴在左侧时领走紧跟的数字`() {
        val clause = "plus立减34.99元，国家补贴500元优惠活动"
        assertEquals(AmountRole.DISCOUNT, AmountRole.of(clause, clause.indexOf("500")))
        // 同句的 34.99 归「减」：两个数字各看自己左边的最近词，互不抢
        assertEquals(AmountRole.DISCOUNT, AmountRole.of(clause, clause.indexOf("34.99")))
        // 反例：隔一个逗号就不许领（ROLE_NUMBER_GLUE 的本职，加词表不能把它松动掉）
        assertNull(AmountRole.of("国家补贴，500元", 5))
        // 反例：`补贴15%` 的 15 在这把尺子上确实会判成 DISCOUNT —— 拦住它的是**百分号守卫**
        // （管线与复核层各一道），这里写明分工，免得下次误以为词表能挡费率
        assertEquals(AmountRole.DISCOUNT, AmountRole.of("国家补贴15%", 4))
    }
}
