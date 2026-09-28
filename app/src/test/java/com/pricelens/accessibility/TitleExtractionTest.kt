package com.pricelens.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标题三级取法（P0-1）表驱动测试。夹具见 NodeFixtures.kt（手工按 uiautomator dump 结构构造）。
 * 核心回归点：主标题挂在**可点击容器**上也要取到；推荐位/促销长句/评价类文本不得顶替标题
 * （旧实现"最长不可点击文本"在京东商详几乎必取错）。
 */
class TitleExtractionTest {

    @Test
    fun `jd detail title comes from known id even when clickable`() {
        val hit = extractTitle(jdDetailPage(), ShopPlatform.JD)
        assertNotNull(hit)
        assertTrue(hit!!.text.startsWith("HUAWEI Mate 80"))
        assertTrue(hit.viaKnownId)
        // 推荐位里"别的商品名"绝不能顶替标题
        assertFalse(hit.text.contains("iPhone 17"))
        // 促销长句（含黑名单词：补贴/晒单/红包）也不能
        assertFalse(hit.text.contains("补贴"))
    }

    @Test
    fun `taobao detail falls back to contentDescription when text empty`() {
        val hit = extractTitle(taobaoDetailPage(), ShopPlatform.TAOBAO)
        assertNotNull(hit)
        assertTrue(hit!!.text.contains("优衣库"))
        assertTrue(hit.viaContentDescription)
        // "7天无理由退换"（退货=黑名单词）不得成为标题
        assertFalse(hit.text.contains("退货"))
    }

    @Test
    fun `pdd detail title via known goods_title id`() {
        val hit = extractTitle(pddDetailPage(), ShopPlatform.PDD)
        assertNotNull(hit)
        assertTrue(hit!!.text.startsWith("小米13"))
        assertTrue(hit.viaKnownId)
        assertFalse(hit.text.contains("秒杀"))
    }

    @Test
    fun `heuristic fallback skips blacklist promo and spec lines`() {
        val hit = extractTitle(pageWithOnlyRawTexts(), ShopPlatform.PDD)
        assertNotNull(hit)
        assertTrue(hit!!.text.contains("Redmi Note 13 Pro"))
        assertFalse(hit.viaKnownId)
        // 三级启发式结果不得是促销句/售后句/规格句/价格
        assertFalse(hit.text.contains("晒单"))
        assertFalse(hit.text.contains("售后"))
        assertFalse(hit.text.contains("："))
        assertFalse(hit.text.contains("¥"))
    }

    @Test
    fun `empty and garbage pages return null`() {
        assertEquals(null, extractTitle(leaf(text = "搜索"), ShopPlatform.JD))
        assertEquals(null, extractTitle(leaf(text = "¥1,299"), ShopPlatform.JD))
        assertEquals(null, extractTitle(leaf(text = "2026-09-26"), ShopPlatform.JD))
    }

    @Test
    fun `spec line detection`() {
        assertTrue(PriceNodeMatcher.looksLikeSpecLine("屏幕尺寸：6.8英寸"))
        assertTrue(PriceNodeMatcher.looksLikeSpecLine("存储容量: 256GB"))
        assertFalse(PriceNodeMatcher.looksLikeSpecLine("HUAWEI Mate 80 曜石黑"))
    }
}
