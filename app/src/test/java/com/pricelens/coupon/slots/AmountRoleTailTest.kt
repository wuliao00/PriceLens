package com.pricelens.coupon.slots

import com.pricelens.coupon.model.AmountRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 尾判（**价词写在数字右边**）的判据测试。
 *
 * 左邻尺子 `AmountRole.of` 只看数字之前，所以 `¥92.9，到手价` 这一族形状判不出角色 ——
 * 评测里那 5 条 `final … → n/a` 全是它（ROADMAP §9.22 的 `[价格槽对照]`：final 一致 53 / 比对 58）。
 * 与左邻那份同一条纪律：每条用例同时钉"该判成什么"和"绝不该判成什么"。
 * 尾判比左邻多开了一个方向，就等于多开一条"认领远处数字"的路，**反例比正例重要**。
 */
class AmountRoleTailTest {

    /** 尾判取的是数字的右端点（不含），这里按原文里的字面量算，免得手写下标数错 */
    private fun tail(clause: String, number: String): AmountRole? = AmountRole.tailOf(clause, clause.indexOf(number) + number.length)

    @Test
    fun `价词在后的四条真机与社区原文各判成到手价`() {
        assertEquals(AmountRole.FINAL, tail("¥92.9，到手价", "92.9"))
        // 京东国补页（评测集 jd-guobu-06）：同句里 12999 由左邻尺子认成 LIST，11499 只有尾判能认
        assertEquals(AmountRole.FINAL, tail("¥11499，国补领后价划线价¥12999", "11499"))
        // 京东首页卡片（jd-home-04）
        assertEquals(AmountRole.FINAL, tail("人民币1579.90 入会到手价", "1579.90"))
        // 社区价格标签（cm-youhui-07 / cm-faxian-06）：只说需用券、不给面额 ⇒ 是价不是券
        assertEquals(AmountRole.FINAL, tail("0.99元（需用券）", "0.99"))
        assertNull(tail("0.99元（需要券）", "0.99"))
        // 少一个字/多一个字都不进表：词表写错不会报错，只会让那两条 golden 一直空着
        // 词形只认夹具里那三个字：`需券` 这种没采到过的写法不进表（进了就是照想象补词表）
        assertNull(tail("0.99元（需券）", "0.99"))
        // 正例的反面：这四句左邻尺子一条都判不出（否则这条测试量的就不是尾判了）
        assertNull(AmountRole.of("¥92.9，到手价", 2))
        assertNull(AmountRole.of("¥0.99元（需用券）", 1))
    }

    @Test
    fun `尾判的取值域只有价格，券的形状一律判不出`() {
        // `500元无门槛券`：「无门槛」也在数字右边，但它说的是这张券，不是价格
        assertNull(tail("500元无门槛券", "500"))
        // `17元外卖餐补`：替身说法确实跟在数字后面，但那条路归 subsidy-tail **模板**管，尾判不收
        assertNull(tail("17元外卖餐补", "17"))
        // 门槛词也不许从右边被认领
        assertNull(tail("300元满1000可用", "300"))
    }

    @Test
    fun `间隙只认列得出的字，别的一出现就判不出`() {
        // 「天」：`近995天新低` 角标 —— 「低」不是词，「新低」也不是，整条本就判不出
        assertNull(tail("近995天新低", "995"))
        // 拉丁字母：尾巴上就算跟着价词也不许越过 `GB`
        assertNull(tail("存储容量：256GB到手价", "256"))
        // 「件」：`下单1件，实付低至4999元` 的 1 右边隔着「件」就碰到「实付」，必须认不出
        assertNull(tail("下单1件，实付低至4999元", "1"))
        // 「本次」：`降价前售价为5999.00元，本次降幅8%` 的 5999 右边隔着「元，本次」就碰到「降」
        assertNull(tail("降价前售价为5999.00元，本次降幅8%", "5999.00"))
    }

    @Test
    fun `两个价词都在右边时取紧贴的那一个，修饰词只认表里那一枚`() {
        // 11499 右边先出现「领后」(FINAL)，再往后才是「划线」(LIST) —— 取近的，不是取强的
        assertEquals(AmountRole.FINAL, tail("¥11499，国补领后价划线价¥12999", "11499"))
        // 修饰词必须有真样本出处：把「国补」换成同族但没采到过的「国补叠加」，间隙就超纲了
        assertNull(tail("¥11499，国补叠加领后价", "11499"))
    }
}
