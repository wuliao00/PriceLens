package com.pricelens.util

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test

/**
 * [PriceFormatter] 价格格式化行为基线（阶段0 测试安全网）。
 * 断言全部来自 v2.4.4 源码实际行为：DecimalFormat("#,##0.##") + "¥" 前缀。
 */
class PriceFormatterTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun pinLocale() {
            // DecimalFormat 的分组符跟随 JVM 默认 Locale，固定为 US 保证断言确定性
            Locale.setDefault(Locale.US)
        }
    }

    @Test
    fun `integer price gets grouping separator and yuan sign`() {
        assertEquals("¥7,999", PriceFormatter.format(7999.0))
        assertEquals("¥1,000,000", PriceFormatter.format(1_000_000.0))
    }

    @Test
    fun `fractional part kept up to two decimals`() {
        assertEquals("¥1,234.5", PriceFormatter.format(1234.5))
        assertEquals("¥99.99", PriceFormatter.format(99.99))
    }

    @Test
    fun `sub-cent fractions are rounded away`() {
        // 1.999 的 double 表示 > 1.995，两位小数后进位为整数
        assertEquals("¥2", PriceFormatter.format(1.999))
    }

    @Test
    fun `zero price formats as plain zero`() {
        assertEquals("¥0", PriceFormatter.format(0.0))
    }

    @Test
    fun `formatRaw omits the yuan sign`() {
        assertEquals("7,999", PriceFormatter.formatRaw(7999.0))
        assertEquals("0", PriceFormatter.formatRaw(0.0))
    }
}
