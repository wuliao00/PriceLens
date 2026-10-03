package com.pricelens.coupon.normalize

import com.pricelens.coupon.rules.CouponTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 确定性文本规整（三入口共用第一步）。
 *
 * 每条都配"不该被动的东西还在"的反例：全角要转、结构标记不许跟着 emoji 一起被扫掉、
 * 分句符要留着 —— 规整做过头比分母做少了更难查，因为症状是"券词都不见了"。
 */
class NormalizeTest {

    @Test
    fun `全角数字字母标点空格都转成半角`() {
        assertEquals("满199减50", Normalize.text("满１９９减５０"))
        assertEquals("FUNN", Normalize.text("ＦＵＮＮ"))
        assertEquals("满199+50", Normalize.text("满１９９＋５０"))
        // 全角空格 U+3000 先变半角空格，再被压缩规则收成一段
        assertEquals("满 199", Normalize.text("满　199"))
        // 正例对照：本来就是半角的输入不被改坏
        assertEquals("满199减50", Normalize.text("满199减50"))
    }

    @Test
    fun `全角规整之后规则才命中（不规整就是词表收了却不命中）`() {
        val hits = CouponTemplates.builtin().match(Normalize.text("满１９９减５０"))
        assertEquals(1, hits.size)
        assertEquals("man-jian-pair", hits[0].templateId)
        assertEquals(listOf(199.0, 50.0), hits[0].amounts.map { it.value })
        // 反例：不做规整直接喂全角，模板一条都命中不了 —— 这正是本用例要治的症状
        assertTrue(CouponTemplates.builtin().match("满１９９减５０").isEmpty())
    }

    @Test
    fun `零宽字符必须删掉，但圈号一步标记不许跟着删`() {
        // 京东用 U+200B 填充文案防爬：不删的话 contains("立即领") 静默失配
        assertEquals("立即领", Normalize.text("立\u200B即\u200B领"))
        assertEquals("立即领", Normalize.text("\u200B立即领\u200B"))
        // ①(U+2460) 与零宽同为"其他符号"类，所以这里用码点区间而不是 \p{So}
        assertEquals("①满199减50", Normalize.text("①\u200B满199减50"))
        assertEquals("①②③", Normalize.text("①②③"))
        assertFalse(Normalize.text("①满199减50").contains("\u200B"))
    }

    @Test
    fun `emoji 删除而结构标点保留`() {
        assertEquals("满199减50", Normalize.text("\uD83C\uDF89满199减50"))
        assertEquals("领卷", Normalize.text("领卷\u2705"))
        // 逗号/句号/顿号/圈号是 Clauses 与步骤拆分的依据，规整不许吃掉它们
        assertEquals("当前地区可领,本单可减1500元", Normalize.text("当前地区可领，本单可减1500元"))
        assertEquals("满199减50。品类券", Normalize.text("满199减50。品类券"))
        assertEquals("小米、华为", Normalize.text("小米、华为"))
        assertTrue(Normalize.text("a，b").contains(","))
    }

    @Test
    fun `连续空白压成一段并去掉首尾`() {
        assertEquals("满199 减50", Normalize.text("  满199 \t\n 减50 "))
        assertEquals("满199 减50", Normalize.text("满199\u00A0减50"))
        assertEquals("", Normalize.text("   \u200B  "))
    }
}
