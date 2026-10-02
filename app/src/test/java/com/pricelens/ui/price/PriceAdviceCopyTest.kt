package com.pricelens.ui.price

import com.pricelens.R
import com.pricelens.domain.PriceAdvice.Advice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 购买建议 → 文案：五种结论各归各的，谁也不许冒充谁。
 *
 * 重点钉两条：
 *  - "数据不足"（UNKNOWN）不能被显示成任何带判断的词（尤其不能是好价）；
 *  - "偏高"与"好价"必须是两句不同的话（同一个徽章位，串了就是把贵说成便宜）。
 */
class PriceAdviceCopyTest {

    @Test
    fun `every verdict maps to its own string`() {
        assertEquals(R.string.advice_hist_low, adviceStringRes(Advice.HIST_LOW))
        assertEquals(R.string.advice_good, adviceStringRes(Advice.GOOD))
        assertEquals(R.string.advice_fair, adviceStringRes(Advice.FAIR))
        assertEquals(R.string.advice_high, adviceStringRes(Advice.HIGH))
        assertEquals(R.string.advice_unknown, adviceStringRes(Advice.UNKNOWN))
    }

    @Test
    fun `verdicts never share copy with each other`() {
        val all = Advice.entries.map { adviceStringRes(it) }
        assertEquals("五个结论必须五句不同的话", all.size, all.toSet().size)
        assertNotEquals(adviceStringRes(Advice.HIGH), adviceStringRes(Advice.GOOD))
        assertNotEquals(adviceStringRes(Advice.UNKNOWN), adviceStringRes(Advice.GOOD))
    }
}
