package com.pricelens.coupon.normalize

import com.pricelens.coupon.adapters.TextAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 病根③「多券混并」的第一道闸：每句独立进抽取。
 *
 * 分句符只有 换行 / `；` / `;` / `。` 四种，**不收 `，`**（京东真机券弹层「当前地区可领，本单可减1500元」
 * 是一个语义单元，按逗号切开就把"可领"和"可减1500"分家 ⇒ 反而造出一张"能领的1500券"）。
 * 每条都同时断言"该拆的拆开了"和"不该拆的没被拆坏"。
 */
class ClausesTest {

    @Test
    fun `三张券写在三个分句里就拆成三条`() {
        val clauses = Clauses.split("店铺券满199减20；品类券满300减50。平台券满500减100;x")
        assertEquals(4, clauses.size)
        assertEquals(
            listOf("店铺券满199减20", "品类券满300减50", "平台券满500减100", "x"),
            clauses.map { it.text }
        )
        // 反例：同一句里的两张券不许在这里被"猜"着拆开（那是抽取层按数字配对的事）
        assertEquals(1, Clauses.split("满199减50 满299减80").size)
    }

    @Test
    fun `逗号与顿号都不是分句符`() {
        val one = Clauses.split("当前地区可领，本单可减1500元")
        assertEquals(1, one.size)
        assertEquals("当前地区可领,本单可减1500元", one[0].text)
        assertEquals(1, Clauses.split("店铺券、品类券各一张").size)
        // 正例对照：把逗号换成分号就必须切开（证明上一条不是"永远只出一条"）
        assertEquals(2, Clauses.split("当前地区可领；本单可减1500元").size)
    }

    @Test
    fun `换行分句并保留 ①②③ 序号`() {
        val clauses = Clauses.split("①满199减50\n②满299减80\n③无门槛")
        assertEquals(3, clauses.size)
        assertEquals(listOf("①", "②", "③"), clauses.map { it.marker })
        // 序号必须**同时**留在正文里：展示层要说"第②步领这张"
        assertTrue(clauses[1].text.startsWith("②"))
        assertEquals("②满299减80", clauses[1].text)
    }

    @Test
    fun `单独成行的步骤标记跟下一句合并而不是自己成句`() {
        val clauses = Clauses.split("步骤1\n满199减50\n第 2 步\n领50元券")
        assertEquals(2, clauses.size)
        assertEquals(listOf("步骤1", "第 2 步"), clauses.map { it.marker })
        assertEquals(listOf("满199减50", "领50元券"), clauses.map { it.text })
        // 反例：正文本身含"步骤"两个字时不该被当成标记吃掉
        val plain = Clauses.split("满199减50 领取步骤见详情")
        assertEquals(1, plain.size)
        assertEquals(null, plain[0].marker)
    }

    @Test
    fun `数字序号 1、与 1) 都能当标记`() {
        assertEquals("1、", Clauses.split("1、满199减50")[0].marker)
        assertEquals("3)", Clauses.split("3)满199减50")[0].marker)
        // 反例：日期不是序号（`2026-10-08` 曾被叠券正则当成 `09-01` 那一类）
        assertEquals(null, Clauses.split("2026-10-08 满199减50")[0].marker)
    }

    @Test
    fun `只有标记没有正文时保留标记不出空句`() {
        val clauses = Clauses.split("满199减50\n步骤3")
        assertEquals(2, clauses.size)
        assertEquals("满199减50", clauses[0].text)
        assertEquals("步骤3", clauses[1].text)
        assertEquals("步骤3", clauses[1].marker)
        // 反例：整段是空白 / 只有分隔符 ⇒ 一条都不出
        assertTrue(Clauses.split("   。；;  \n").isEmpty())
        assertTrue(Clauses.split("").isEmpty())
    }

    @Test
    fun `文本入口先分句再逐句规整所以换行不会被压掉`() {
        // 整段先 Normalize.text 会把 `\s+`（含换行）压成空格，三行步骤就并成一句了
        val clauses = TextAdapter.normalize("①满199减50\n②满299减80\n③无门槛")
        assertEquals(3, clauses.size)
        assertEquals(listOf("①", "②", "③"), clauses.map { it.marker })
        // 正例对照：全角与零宽仍然逐句被治好（顺序换了也不能漏）
        assertEquals("满199减50", TextAdapter.normalize("满１９９减５０")[0].text)
        assertEquals("立即领", TextAdapter.normalize("立\u200B即\u200B领")[0].text)
        // 反例：零宽插在"步骤"中间时序号还是要认得出来
        assertEquals("步骤1", TextAdapter.normalize("步\u200B骤1\n满199减50")[0].marker)
    }
}
