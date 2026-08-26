package com.pricelens.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ContentRiskRules] 商单/夸大词命中规则基线（阶段0 测试安全网）。
 *
 * v2.4.4 源码实际行为：
 *  - 命中词库按 SPONSOR_WORDS / HYPE_WORDS 列表顺序返回第一个命中词
 *  - B 站联合投稿标记（isUnionVideo）单独即构成商单痕迹
 *  - 只标记不删除：flagged = sponsored || hype
 */
class ContentRiskTest {

    @Test
    fun `clean text is not flagged`() {
        val risk = ContentRiskRules.assess("真实测评：这盏台灯连续使用 30 天的体验")
        assertEquals(ContentRisk.NONE, risk)
        assertFalse(risk.flagged)
    }

    @Test
    fun `sponsor word triggers sponsored with matched word`() {
        val risk = ContentRiskRules.assess("本视频是商单，大家理性观看")
        assertTrue(risk.sponsored)
        assertEquals("商单", risk.sponsorWord)
        assertTrue(risk.flagged)
        assertFalse(risk.hype)
    }

    @Test
    fun `hype word triggers hype with first hit in word list order`() {
        val risk = ContentRiskRules.assess("这款键盘简直天花板，yyds")
        assertTrue(risk.hype)
        // 词库顺序："天花板" 排在 "yyds" 之前 → 返回先命中者
        assertEquals("天花板", risk.hypeWord)
        assertFalse(risk.sponsored)
    }

    @Test
    fun `matching is case insensitive`() {
        val risk = ContentRiskRules.assess("YYDS 永远的神")
        assertEquals("yyds", risk.hypeWord)
        assertTrue(risk.hype)
    }

    @Test
    fun `union video alone counts as sponsored`() {
        val risk = ContentRiskRules.assess("聊聊最近买的手机", isUnionVideo = true)
        assertTrue(risk.sponsored)
        assertEquals("联合投稿", risk.sponsorWord)
        assertTrue(risk.flagged)
        assertFalse(risk.hype)
    }

    @Test
    fun `explicit sponsor word wins over union video label`() {
        val risk = ContentRiskRules.assess("感谢金主爸爸的支持", isUnionVideo = true)
        assertTrue(risk.sponsored)
        assertEquals("金主", risk.sponsorWord)
    }

    @Test
    fun `sponsor and hype can be flagged together`() {
        val risk = ContentRiskRules.assess("恰饭产品，全网最低价，赶紧入手")
        assertTrue(risk.sponsored)
        assertTrue(risk.hype)
        assertTrue(risk.flagged)
        assertEquals("恰饭", risk.sponsorWord)
        assertEquals("全网最低", risk.hypeWord)
    }
}
