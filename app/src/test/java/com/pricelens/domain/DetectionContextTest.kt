package com.pricelens.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面树身份在"再搜一轮"时该不该留着（#73）。
 *
 * 正反例都要钉：只钉"换词要清"会让详情页入口那一次重搜继续把身份抹掉
 * （真机上表现为「页面」芯片永远不出现，2026-10-06 那轮走通整条链才看得见）；
 * 只钉"要留"则可能把 A 页的券挂到 B 商品上。
 */
class DetectionContextTest {

    @Test
    fun `同一句关键词再搜一轮不算换商品，身份留着`() {
        assertTrue(DetectionContext.survivesSearch("MacBook Pro M4", "MacBook Pro M4", null))
        // 两侧空白不算差异（与 search() 的 trim 同口径）
        assertTrue(DetectionContext.survivesSearch("  MacBook Pro M4 ", "MacBook Pro M4\n", null))
    }

    @Test
    fun `从这一轮结果进详情页算同一件商品，哪怕传的是候选标题`() {
        // 概览页「看详情」传的是 product.title 而不是关键词（OverviewScreen 的 onOpenProduct）
        val keyword = "苹果 MacBook Air 13 英寸 M4 16GB 512GB"
        val candidate = "Apple MacBook Air 13 M4 (16GB/512GB)"
        assertTrue(DetectionContext.survivesSearch(keyword, candidate, candidate))
    }

    @Test
    fun `换了关键词或换了候选就是换商品，身份作废`() {
        assertFalse(DetectionContext.survivesSearch("MacBook Pro M4", "MacBook Air M4", "别的商品"))
        // 盯价列表里另一件商品：既不是当前关键词，也不是当前候选 ⇒ 那一页的树不作数
        assertFalse(DetectionContext.survivesSearch("iPhone 17", "小米 16 Ultra", "iPhone 17 Pro Max"))
        // 大小写与全半角不额外归一：这里宁可作废（少一路输入），不冒"把 A 挂到 B"的风险
        assertFalse(DetectionContext.survivesSearch("iPhone 17", "iphone 17", null))
    }

    @Test
    fun `第一次搜索之前没有身份可留`() {
        assertFalse(DetectionContext.survivesSearch("", "MacBook Pro M4", null))
    }
}
