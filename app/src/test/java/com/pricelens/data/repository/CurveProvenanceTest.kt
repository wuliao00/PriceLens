package com.pricelens.data.repository

import com.pricelens.data.local.dao.SourceDayCount
import com.pricelens.domain.PriceSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 盯价页脚注的出处统计（2026-09-30）。
 *
 * 用户要能看出这条线是**本机自采**还是**慢慢买**给的，所以：
 *  - 天数按"日"算，不按行算；
 *  - 认不出的出处（迁移前的老行 = [PriceSource.UNRECORDED_NAME]）必须诚实标"来源未记录"，
 *    既不能算进"本机自采"，也不能算进"慢慢买"；
 *  - `isSelfCollected` 只在**没有任何外部点**时才为真 —— 有慢慢买数据时还宣称"本机自采"是假话。
 */
class CurveProvenanceTest {

    private fun of(vararg pairs: Pair<String, Int>) = CurveProvenance.of(pairs.map { SourceDayCount(it.first, it.second) })

    @Test
    fun `self collected curve reports its own day count`() {
        val p = of(PriceSource.SELF_WATCH.name to 4)
        assertEquals(4, p.totalDays)
        assertEquals(0, p.externalDays)
        assertTrue("没有 Cookie 时曲线确实是自己长出来的", p.isSelfCollected)
        assertEquals(listOf("本机盯价自采" to 4), p.dayPairs())
        assertEquals("本机盯价自采 4 天", p.dayPairsText())
    }

    @Test
    fun `manmanbuy days are never reported as self collected`() {
        val p = of(PriceSource.MANMANBUY.name to 120)
        assertEquals(120, p.totalDays)
        assertEquals(120, p.externalDays)
        assertFalse(p.isSelfCollected)
        assertEquals("慢慢买 120 天", p.dayPairsText())
    }

    @Test
    fun `mixed curve lists every source with its own day count`() {
        val p = of(
            PriceSource.SELF_WATCH.name to 3,
            PriceSource.MANMANBUY.name to 20,
            PriceSource.LINKSTARS_LIST.name to 2
        )
        assertEquals(25, p.totalDays)
        assertEquals(22, p.externalDays)
        assertFalse("混着长的线不能自称纯自采", p.isSelfCollected)
        assertEquals(
            "按天数降序，外部与自采都单列",
            listOf("慢慢买" to 20, "本机盯价自采" to 3, "星罗好货在售价" to 2),
            p.dayPairs()
        )
        assertEquals("慢慢买 20 天 · 本机盯价自采 3 天 · 星罗好货在售价 2 天", p.dayPairsText())
    }

    @Test
    fun `unrecorded legacy days stay unrecorded`() {
        val p = of(PriceSource.UNRECORDED_NAME to 7, PriceSource.SELF_WATCH.name to 1)
        assertEquals(8, p.totalDays)
        assertEquals(1, p.selfWatchDays)
        assertEquals(7, p.unrecordedDays)
        assertEquals("来源未记录不能算成慢慢买或星罗给的天数", 0, p.externalDays)
        assertFalse("还有一半天数出处不明，不许宣称纯自采", p.isSelfCollected)
        assertEquals("来源未记录 7 天 · 本机盯价自采 1 天", p.dayPairsText())
    }

    @Test
    fun `an unknown source label is reported honestly instead of guessed`() {
        val p = of("SOME_FUTURE_SOURCE" to 2)
        assertEquals(2, p.totalDays)
        assertEquals(2, p.unrecordedDays)
        assertEquals("来源未记录 2 天", p.dayPairsText())
    }

    @Test
    fun `an empty curve says nothing`() {
        val p = CurveProvenance()
        assertEquals(0, p.totalDays)
        assertFalse(p.isSelfCollected)
        assertEquals("", p.dayPairsText())
        assertTrue(p.dayPairs().isEmpty())
    }
}
