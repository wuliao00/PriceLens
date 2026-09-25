package com.pricelens.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QueryRelevance] 相关性规则基线。
 * 所有"应拒绝"的标题都来自 2026-09-25 上游实况（什么值得买/当当/识货），
 * 见 `app/src/test/resources/fixtures/` 内夹具与 docs/API.md 的源状态说明。
 */
class QueryRelevanceTest {

    @Test
    fun `device query rejects phone case from dangdang`() {
        assertFalse(
            QueryRelevance.isRelevant(
                "iPhone 15",
                "【 iPhone15 Pro 百变磁吸背盖手机壳 】 新品哈利波特正版苹果手机壳百变磁吸背盖可替换背盖iPhone保护套"
            )
        )
    }

    @Test
    fun `device query rejects lens film from smzdm`() {
        assertFalse(
            QueryRelevance.isRelevant(
                "iPhone 15",
                "闪魔苹果16镜头膜17镜头膜适用iphone15promax镜头膜14镜头膜13圈plus手机膜"
            )
        )
    }

    @Test
    fun `device query rejects english guide books from dangdang`() {
        assertFalse(QueryRelevance.isRelevant("iPhone 15", "iPhone 15 Pro Max User Guide: Unlocking the Power and Ease"))
        assertFalse(QueryRelevance.isRelevant("iPhone 15", "预售 iPhone 15 Manual: Unlock the Ultimate iPhone 15 Pro Max"))
        assertFalse(QueryRelevance.isRelevant("iPhone 15", "iPhone 15 Pro Mastery: A Comprehensive Beginner's Guide"))
    }

    @Test
    fun `device query rejects another brand with same model number`() {
        // 真实案例：搜 iPhone 15 命中「PLUS会员…小米 15 5G 手机」（数字 15 相同）
        assertFalse(QueryRelevance.isRelevant("iPhone 15", "PLUS会员 国家补贴 小米 15 5G 手机 黑色 16GB+512GB"))
    }

    @Test
    fun `device query rejects another model of same brand`() {
        assertFalse(QueryRelevance.isRelevant("iPhone 15", "Apple iPhone 17 5G手机 8GB+256GB 黑色"))
        assertFalse(QueryRelevance.isRelevant("iPhone 15", "国家补贴 Apple iPhone Air 5G手机"))
    }

    @Test
    fun `device query rejects hot-list items unrelated to keyword`() {
        // 真实案例：识货首页热榜被当成搜索结果（adidas 板鞋 / 洗发水）
        assertFalse(
            QueryRelevance.isRelevant(
                "iPhone 15",
                "adidas Originals Superstar 板鞋 轻便贝壳头薄底舒适百搭耐磨复古 白色/金色/纯黑色"
            )
        )
        assertFalse(QueryRelevance.isRelevant("iPhone 15", "海飞丝 洗发水 控油蓬松肌底瓶洗发水 头皮护理去屑补水 670g"))
    }

    @Test
    fun `matching phone title passes`() {
        assertTrue(QueryRelevance.isRelevant("iPhone 15", "Apple iPhone 15 5G手机 512GB 黑色"))
        assertTrue(QueryRelevance.isRelevant("iPhone 15", "苹果 iPhone15 全网通 双卡双待 128GB"))
    }

    @Test
    fun `accessory query keeps accessories`() {
        assertTrue(QueryRelevance.isRelevant("iPhone 15 手机壳", "适用 iPhone 15 手机壳 磁吸透明保护套 防摔"))
        assertTrue(QueryRelevance.isRelevant("钢化膜", "闪魔 钢化膜 2片装 适用苹果"))
    }

    @Test
    fun `accessory prefix rule only rejects when query has none`() {
        assertFalse(QueryRelevance.isRelevant("iPhone 15", "适用 iPhone 15 磁吸透明保护套"))
        // 关键词自带"适用"时不启用前缀规则
        assertTrue(QueryRelevance.isRelevant("适用 iPhone 15 保护套", "适用 iPhone 15 保护套 磁吸"))
    }

    @Test
    fun `full width and letter case are normalized`() {
        assertTrue(QueryRelevance.isRelevant("ＩＰＨＯＮＥ １５", "Apple iPhone15 5G 手机"))
        assertTrue(QueryRelevance.isRelevant("iphone 15", "APPLE IPHONE 15 手机"))
    }

    @Test
    fun `pure cjk query requires word presence`() {
        assertTrue(QueryRelevance.isRelevant("充电宝", "小米 充电宝 10000mAh 移动电源"))
        assertFalse(QueryRelevance.isRelevant("充电宝", "绿联 数据线 快充线 2米"))
    }

    @Test
    fun `cjk brand query rejects other brand`() {
        assertFalse(QueryRelevance.isRelevant("苹果 15", "小米 15 手机 全网通"))
        assertTrue(QueryRelevance.isRelevant("苹果 15", "苹果 iPhone 15 手机"))
    }

    @Test
    fun `book query itself keeps books`() {
        assertTrue(QueryRelevance.isRelevant("iPhone 15 使用指南", "iPhone 15 使用指南 图解教程"))
    }

    @Test
    fun `empty inputs are irrelevant`() {
        assertFalse(QueryRelevance.isRelevant("", "iPhone 15"))
        assertFalse(QueryRelevance.isRelevant("iPhone 15", ""))
    }
}
