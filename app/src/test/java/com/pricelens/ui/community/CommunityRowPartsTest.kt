package com.pricelens.ui.community

import com.pricelens.ui.layout.TextTruncate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 社区/爆料列表「标题 1 行 + 摘要 2 行 + 元信息 1 行」的截断规则（纯函数）。
 *
 * 单元预算来自 RowBudget 的真实宽度核算（真机内容宽 302.86dp）：
 *  标题 14sp → 21 单元；摘要 12sp 两行 → 25×2 = 50 单元；元信息 12sp → 25 单元。
 * 截断走字簇口径——标题/摘要里混着 emoji 与代理对时**绝不截半**（本仓库的老坑）。
 */
class CommunityRowPartsTest {

    private val longTitle = "华为 Mate 80 Pro 至尊版 官方正品 现货速发 全国联保 12 期免息"
    private val longExcerpt = "值得买爆料正文摘要，慢慢买历史低价参考，识货同款比价，券后可再减二十元，叠加国补到手更低，先涨后降风险自行判断。"

    @Test
    fun `标题一行封顶`() {
        assertEquals(21, CommunityRows.TITLE_UNITS)
        val parts = CommunityRows.of(longTitle, null, listOf("值得买"))
        assertEquals(21, TextTruncate.units(parts.title))
    }

    @Test
    fun `摘要两行封顶`() {
        assertEquals(50, CommunityRows.EXCERPT_UNITS)
        val parts = CommunityRows.of("标题", longExcerpt, listOf("值得买"))
        assertEquals(50, TextTruncate.units(parts.excerpt!!))
    }

    /** 红线：emoji 字簇不能被截半（61 个字簇压到 50，末尾必须还是完整的 👨‍👩‍👧） */
    @Test
    fun `摘要里的家庭表情不被截断`() {
        val family = "👨‍👩‍👧"
        val short = CommunityRows.of("标题", family + family + family + family, listOf("值得买")).excerpt
        assertEquals(family + family + family + family, short)

        val clamped = CommunityRows.of("标题", family.repeat(60) + "x", listOf("值得买")).excerpt!!
        assertEquals(family.repeat(49) + "…", clamped)
        assertFalse(Character.isHighSurrogate(clamped.last()))
        assertEquals(50, TextTruncate.units(clamped))
    }

    /** 稳定三段：没有摘要就整段消失，不摆一行空的省略号冒充内容 */
    @Test
    fun `无摘要时段落整体消失`() {
        assertNull(CommunityRows.of("标题", null, listOf("值得买")).excerpt)
        assertNull(CommunityRows.of("标题", "   ", listOf("值得买")).excerpt)
    }

    @Test
    fun `元信息丢掉空白段`() {
        val parts = CommunityRows.of("标题", "摘要", listOf("识货", "", "  ", "3 小时前"))
        assertEquals("识货 · 3 小时前", parts.meta)
    }

    @Test
    fun `元信息一行封顶`() {
        val parts = CommunityRows.of("标题", "摘要", List(10) { "很长的元信息段" })
        assertEquals(25, TextTruncate.units(parts.meta))
    }

    /** 最坏负载：三段各自都在预算内，卡片高度才谈得上"稳定" */
    @Test
    fun `最坏负载三段都在预算内`() {
        val parts = CommunityRows.of(longTitle.repeat(2), longExcerpt.repeat(2), listOf("值得买", "本机账号", "2026-10-01"))
        assertFalse(TextTruncate.units(parts.title) > CommunityRows.TITLE_UNITS)
        assertFalse(TextTruncate.units(parts.excerpt!!) > CommunityRows.EXCERPT_UNITS)
        assertFalse(TextTruncate.units(parts.meta) > CommunityRows.META_UNITS)
    }
}
