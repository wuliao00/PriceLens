package com.pricelens.ui.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 版式截断的字节层红线（2026-10-02 排版大改）。
 *
 * 存在的理由只有一句话：**截断不能把 emoji 或代理对截半**。
 * 本仓库在 MUTF-8 / 代理对上摔过，`String.take(n)` 按 UTF-16 code unit 计数，
 * 一个 😀 占两个 unit —— naive 实现下面每条 emoji 用例都会红。
 */
class TextTruncateTest {

    @Test
    fun `汉字按字计单元`() {
        assertEquals(4, TextTruncate.units("京东现价"))
        assertEquals(9, TextTruncate.units("本机京东账号实时价"))
    }

    @Test
    fun `省略号计入预算`() {
        // 预算 6 → 5 个字 + 1 个省略号，总宽仍是 6 个单元（不是 7）
        assertEquals("本机京东账…", TextTruncate.clamp("本机京东账号实时价", 6))
        assertEquals(6, TextTruncate.units(TextTruncate.clamp("本机京东账号实时价", 6)))
    }

    /** 能显示原文就别显示省略号：返回同一个实例，渲染层据此判断"没被截断" */
    @Test
    fun `未超长原样返回`() {
        val text = "京东现价"
        assertSame(text, TextTruncate.clamp(text, 6))
        assertEquals("京东现价", TextTruncate.clamp("京东现价", 4))
    }

    @Test
    fun `预算非正时不给内容`() {
        assertEquals("", TextTruncate.clamp("京东现价", 0))
        assertEquals("", TextTruncate.clamp("京东现价", -1))
        assertEquals("", TextTruncate.head("京东现价", 0))
    }

    /** 关键红线：😀 = D83D DE00，naive take(2) 会留下半个代理对 */
    @Test
    fun `emoji 代理对不被截半`() {
        assertEquals(1, TextTruncate.units("😀"))
        assertEquals(4, TextTruncate.units("😀😀😀😀"))
        val clamped = TextTruncate.clamp("😀😀😀😀", 3)
        assertEquals("😀😀…", clamped)
        assertFalse(Character.isHighSurrogate(clamped.last()))
        assertEquals(3, clamped.codePointCount(0, clamped.length))
    }

    /** 👨‍👩‍👧 是 5 个码点（含两枚 ZWJ）但只有 1 个字簇 */
    @Test
    fun `zwj 家庭表情是一整簇`() {
        val family = "👨‍👩‍👧"
        assertEquals(1, TextTruncate.units(family))
        assertEquals(family, TextTruncate.head(family + "x", 1))
        assertEquals("$family…", TextTruncate.clamp("${family}${family}y", 2))
    }

    /** 🇨🇳 = 两枚 Regional Indicator，单切一枚是孤儿 */
    @Test
    fun `国旗成对`() {
        assertEquals(1, TextTruncate.units("🇨🇳"))
        assertEquals("🇨🇳…", TextTruncate.clamp("🇨🇳🇺🇸A", 2))
    }

    @Test
    fun `变体选择符与肤色修饰符跟着基字符`() {
        assertEquals(1, TextTruncate.units("❤️"))
        assertEquals(1, TextTruncate.units("👍🏽"))
        assertEquals(1, TextTruncate.units("e\u0301"))
        assertEquals("❤️…", TextTruncate.clamp("❤️❤️❤️", 2))
    }

    /** 最坏负载性质：截断结果的单元数永远不超预算 */
    @Test
    fun `截断结果不超预算`() {
        val samples = listOf("京东现价", "本机京东账号实时价", "👨‍👩‍👧家庭款", "🇨🇳🇺🇸", "e\u0301clair", "¥12,999.00", "")
        for (s in samples) {
            for (budget in 1..8) {
                assertFalse("超出预算：$s / $budget", TextTruncate.units(TextTruncate.clamp(s, budget)) > budget)
            }
        }
    }
}
