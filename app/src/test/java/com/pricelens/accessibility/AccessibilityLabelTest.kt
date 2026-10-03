package com.pricelens.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 读屏标签清洗与"导航项不是商品标题"的判定。
 *
 * 红证据不是这里给的，是真机给的：2026-10-03 上午用户在淘宝里逛了一圈，
 * `.dev` 库里落下两条脏身份（标题 `购物车，按钮` / `购物车20，按钮`，各自绑了一个价格）
 * 与两条脏搜索历史——那是 2.8.0 就有的行为。本文件把当时实际出现的字符串逐条钉住。
 */
class AccessibilityLabelTest {

    @Test
    fun `剥掉尾部读屏角色后缀`() {
        assertEquals("购物车20", AccessibilityLabel.stripRoles("购物车20，按钮"))
        assertEquals("购物车", AccessibilityLabel.stripRoles("购物车，按钮"))
        assertEquals("购物车", AccessibilityLabel.stripRoles("购物车，按钮，已选中"))
        assertEquals("查看详情", AccessibilityLabel.stripRoles("查看详情, 链接"))
        assertEquals("第 2 张图", AccessibilityLabel.stripRoles("第 2 张图"))
    }

    /** 标题中间出现"按钮"两字是真实商品名，不能整串删 */
    @Test
    fun `只吃尾部不吃中间`() {
        val title = "红色按钮衬衫 长袖 男装"
        assertEquals(title, AccessibilityLabel.stripRoles(title))
    }

    @Test
    fun `导航词与带角标的导航词都判为界面外壳`() {
        assertTrue(AccessibilityLabel.looksLikeNavLabel("购物车20"))
        assertTrue(AccessibilityLabel.looksLikeNavLabel("购物车20，按钮"))
        assertTrue(AccessibilityLabel.looksLikeNavLabel("首页"))
        assertTrue(AccessibilityLabel.looksLikeNavLabel("消息3"))
        assertTrue(AccessibilityLabel.looksLikeNavLabel("拍照搜索，按钮"))
        assertTrue(AccessibilityLabel.looksLikeNavLabel("购物车 20 件"))
    }

    /** 判别例：真实商品名不许被误杀（"我的/购物车/收藏"作为子串出现也不行，判定是整串相等） */
    @Test
    fun `不误杀含导航字的真实商品名`() {
        assertFalse(AccessibilityLabel.looksLikeNavLabel("我的健身计划 训练手册"))
        assertFalse(AccessibilityLabel.looksLikeNavLabel("购物车收纳袋 挂袋置物架"))
        assertFalse(AccessibilityLabel.looksLikeNavLabel("小米 14 Ultra 16GB+512GB 白色 影像旗舰"))
    }

    @Test
    fun `cleanTitle 顺带剥角色后缀并清零宽`() {
        assertEquals("购物车20", PriceNodeMatcher.cleanTitle("购物车20，按钮"))
        assertEquals("小米手机", PriceNodeMatcher.cleanTitle("小米\u200B手机"))
        assertEquals(null, PriceNodeMatcher.cleanTitle("，按钮"))
    }

    /** 主回归：当年过掉的那两级门槛（ID 背书的 strict=false，门槛只有 6 字）现在必须挡住 */
    @Test
    fun `脏标题过不了一二级门槛`() {
        val dirty = PriceNodeMatcher.cleanTitle("购物车20，按钮") ?: ""
        assertEquals("购物车20", dirty)
        assertFalse(PriceNodeMatcher.isPlausibleTitle(dirty, strict = false))
        assertFalse(PriceNodeMatcher.isPlausibleTitle("首页", strict = false))
    }

    @Test
    fun `真商品标题照常通过`() {
        val real = PriceNodeMatcher.cleanTitle("小米 REDMI Book 14 2026 Core Ultra5 32GB+1TB 星光银") ?: ""
        assertTrue(PriceNodeMatcher.isPlausibleTitle(real, strict = false))
        assertTrue(PriceNodeMatcher.isPlausibleTitle(real, strict = true))
    }
}
