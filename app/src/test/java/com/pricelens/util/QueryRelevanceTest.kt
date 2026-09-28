package com.pricelens.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QueryRelevance] 相关性规则基线。
 *
 * 两轮修复的规则说明：
 *  - 2026-09-25：上游（什么值得买/当当/识货）按热度排序会混入配件、图书、其它品牌、热榜商品，
 *    于是加了配件/图书/品牌反向排除 + 拉丁段全覆盖（旧规则 2）。
 *  - 2026-09-28：旧规则 2 先 [QueryRelevance.normalize] 删空白再切段，`oppo x8s` 退化成
 *    一个必须整体连续出现的巨型 token `oppox8s`，而标题写的是 `OPPO Find X8s` → 判不相关，
 *    导致"搜 oppo x8s / mate 80 显示不准确、跟踪错误"。现改为**分段 + 顺序 + 间隔上限 G=12**。
 *
 * 所有"应拒绝"的标题都来自 2026-09 上游实况（什么值得买/当当/识货），
 * 见 `app/src/test/resources/fixtures/` 内夹具与 docs/API.md 的源状态说明。
 * 桌面端 `desktop/src/main/utils/relevance.js` 与本文件逐条对齐，
 * 同名同用例见 `desktop/_unit_check.js`（node 可直接跑）。
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

    // ===== 2026-09-28 分段/间隔/顺序规则（根因修复） =====

    @Test
    fun `spaced model query matches title with words between segments`() {
        // 实况根因：oppo x8s 归一化成 oppox8s 后要求连续出现，标题是 OPPO Find X8s → 全被过滤
        val title = "OPPO Find X8s 12GB+256GB 月光白"
        assertTrue(QueryRelevance.isRelevant("oppo x8s", title))
        assertTrue(QueryRelevance.isRelevant("OPPO  X8S", title))
        assertTrue(QueryRelevance.isRelevant("ＯＰＰＯ ｘ８ｓ", title))
        assertEquals(listOf("oppo", "x8s"), QueryRelevance.querySegments("oppo x8s"))
        // 间隔 "find" = 4 ≤ G(12)
        assertTrue(QueryRelevance.matchesSegments("x8s plus", "OPPO Find X8s Plus 5G手机"))
    }

    @Test
    fun `model aliases written with or without space are interchangeable`() {
        // 不需要机型别名词表：Mate80 / Mate 80 / MATE 80 互认
        assertTrue(QueryRelevance.isRelevant("mate 80", "华为 Mate80 麒麟9020 鸿蒙智能手机"))
        assertTrue(QueryRelevance.isRelevant("Mate80", "华为 Mate 80 手机 12GB+512GB 雪域白"))
        assertTrue(QueryRelevance.isRelevant("MATE 80", "华为 Mate 80 手机 12GB+512GB 云杉绿"))
        assertTrue(QueryRelevance.isRelevant("iphonese2", "Apple iPhone SE2 64GB"))
    }

    @Test
    fun `segment order must match title order`() {
        // 实测样例：搜 mate 80 命中「EG80MATE33S」（含 mate + 80 但顺序颠倒）
        assertFalse(QueryRelevance.isRelevant("mate 80", "EG80MATE33S 空调室内机主板"))
        assertFalse(QueryRelevance.isRelevant("mate 80", "80 元券 MATE 30 手机"))
        assertTrue(QueryRelevance.isRelevant("mate 80", "MATE 系列 80 周年纪念版"))
    }

    @Test
    fun `gap beyond the limit is rejected`() {
        val title = "OPPO  Find  X8s 12GB"
        assertTrue(QueryRelevance.matchesSegments("oppo x8s", title))
        assertFalse(QueryRelevance.matchesSegments("oppo x8s", "OPPO Find N6 折叠屏 官方旗舰正品店 X8s"))
        // 紧邻（G=0 语义）仍要求连续子串
        assertTrue(QueryRelevance.matchesSegments("oppo x8s", "OPPOX8S 5G手机", maxGap = 0))
        assertFalse(QueryRelevance.matchesSegments("oppo x8s", "OPPO Find X8S 5G手机", maxGap = 0))
    }

    @Test
    fun `segment rule still blocks other model of same brand`() {
        // 分段放宽后，机型数字仍是硬条件
        assertFalse(QueryRelevance.isRelevant("小米 15", "小米 14 手机 12GB+256GB"))
        assertFalse(QueryRelevance.isRelevant("iphone 15", "Apple iPhone 15 Pro Max 保护套"))
        assertFalse(QueryRelevance.isRelevant("oppo x8s", "OPPO Find X8 Pro 5G手机 16GB+512GB"))
    }

    // ===== 2026-09-28 配件词表扩充（放宽分段后唯一新增漏网是骨传导耳机） =====

    @Test
    fun `extended accessory words are rejected`() {
        // 实况漏网条目（值得买 youhui 页，关键词 华为 Mate 80）
        assertFalse(
            QueryRelevance.isRelevant(
                "华为 Mate 80",
                "怎么挑适用华为mate50荣耀80Pro GT无线蓝牙骨传导耳机不入耳运动"
            )
        )
        assertFalse(QueryRelevance.isRelevant("mate 80", "华为 Mate 80 手机 充电宝 10000mAh 移动电源"))
        assertFalse(QueryRelevance.isRelevant("mate 80", "华为 Mate 80 手机 电池 3000mAh 原装"))
        assertFalse(QueryRelevance.isRelevant("mate 80", "华为 Mate 80 手机 保护贴 3片装"))
        assertFalse(QueryRelevance.isRelevant("mate 80", "华为 Mate 80 手机膜 高清防窥"))
    }

    @Test
    fun `accessory query itself keeps those accessories`() {
        assertTrue(QueryRelevance.isRelevant("耳机", "索尼 WF-1000XM5 无线蓝牙降噪耳机"))
        assertTrue(QueryRelevance.isRelevant("充电宝", "安克 充电宝 移动电源 12000mAh"))
        assertTrue(QueryRelevance.isRelevant("移动电源", "小米 移动电源 20000mAh"))
        assertTrue(QueryRelevance.isRelevant("电池", "耐杰 电池 适用华为 P30"))
        assertTrue(QueryRelevance.isRelevant("保护贴", "闪魔 保护贴 3片装 适用 iPad"))
        assertTrue(QueryRelevance.isRelevant("手机膜", "闪魔 手机膜 高清防窥 适用苹果"))
    }

    @Test
    fun `series suffix is never filtered out`() {
        // Pro/Max 只降权不过滤：列表里仍要出现（打分见 CandidateRankingTest）
        assertTrue(QueryRelevance.isRelevant("mate 80", "华为 Mate 80 Pro Max 手机 16GB+1TB 极昼金"))
        assertTrue(QueryRelevance.isRelevant("x8s", "OPPO Find X8s+ 5G手机 12GB+512GB"))
        assertTrue(QueryRelevance.isRelevant("iphone 15", "Apple iPhone 15 Pro 5G手机 256GB"))
    }
}
