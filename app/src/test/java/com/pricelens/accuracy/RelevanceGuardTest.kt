package com.pricelens.accuracy

import com.pricelens.domain.AutoTrackPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RelevanceGuard] 行为基线（>=12 条，覆盖真机 round1 抓到的全部垃圾样本类型）。
 *
 * 纯 JVM：只依赖 QueryRelevance / AutoTrackPolicy 两个纯函数模块，无 Android、无网络、无时钟。
 * 用例与真机样本对照（2026-11 round1，识货社区列表 + 什么值得买爆料）：
 *  - 「稳健医疗 Apple 苹果 iPhone … ¥13.4」→ 医疗用品；
 *  - 「资源机 Apple/苹果 iPhone … ¥4,737」→ 资源机；
 *  - 什么值得买爆料价 ¥2,898 vs 候选 ¥5,999 → 比值 0.48 越界。
 */
class RelevanceGuardTest {

    // ---------- 正例：同款应放行 ----------

    @Test
    fun `normal same product passes with in-band price`() {
        assertTrue(
            RelevanceGuard.isLikelySameProduct(
                keyword = "iphone 15",
                title = "Apple iPhone 15 128GB 蓝色 5G手机",
                price = 5999.0,
                referencePrice = 5999.0
            )
        )
    }

    @Test
    fun `same product with slightly different price still passes`() {
        assertTrue(
            RelevanceGuard.isLikelySameProduct(
                keyword = "iphone 15",
                title = "Apple iPhone 15 128GB 蓝色 5G手机",
                price = 5699.0,
                referencePrice = 5999.0
            )
        )
    }

    @Test
    fun `price ratio boundaries are inclusive`() {
        assertTrue(
            RelevanceGuard.isLikelySameProduct(
                keyword = "iphone 15",
                title = "Apple iPhone 15 128GB 蓝色 5G手机",
                price = 3000.0,
                referencePrice = 6000.0
            )
        )
        assertTrue(
            RelevanceGuard.isLikelySameProduct(
                keyword = "iphone 15",
                title = "Apple iPhone 15 128GB 蓝色 5G手机",
                price = 12000.0,
                referencePrice = 6000.0
            )
        )
    }

    @Test
    fun `missing reference price only does text check`() {
        // 参照价缺失：不做比值断言（调用方语义见 RelevanceGuard KDoc：不要当"第二来源现价"用）
        assertTrue(
            RelevanceGuard.isLikelySameProduct(
                keyword = "iphone 15",
                title = "Apple iPhone 15 128GB 蓝色 5G手机",
                price = 2898.0,
                referencePrice = null
            )
        )
    }

    @Test
    fun `missing price only does text check`() {
        assertTrue(
            RelevanceGuard.isLikelySameProduct(
                keyword = "iphone 15",
                title = "Apple iPhone 15 128GB 蓝色 5G手机",
                price = null,
                referencePrice = 5999.0
            )
        )
    }

    // ---------- 反例：真机垃圾样本 ----------

    @Test
    fun `resource machine is rejected`() {
        // 真机样本：「资源机 Apple/苹果 iPhone … ¥4,737」
        assertFalse(
            RelevanceGuard.isLikelySameProduct(
                keyword = "苹果 iPhone 15",
                title = "资源机 Apple/苹果 iPhone 15 128GB 全新未激活",
                price = 4737.0,
                referencePrice = 5999.0
            )
        )
    }

    @Test
    fun `medical product is rejected`() {
        // 真机样本：「稳健医疗 Apple 苹果 iPhone … ¥13.4」——同品牌词，但卖的是医疗用品
        assertFalse(
            RelevanceGuard.isLikelySameProduct(
                keyword = "苹果 iPhone 15",
                title = "稳健医疗 Apple 苹果 iPhone 15 护理用品",
                price = 13.4,
                referencePrice = 5999.0
            )
        )
    }

    @Test
    fun `second hand and renewed are rejected`() {
        assertFalse(
            RelevanceGuard.isLikelySameProduct(
                keyword = "iphone 15",
                title = "99新 二手 Apple iPhone 15 128GB 无锁",
                price = 4500.0,
                referencePrice = 5999.0
            )
        )
        assertFalse(
            RelevanceGuard.isLikelySameProduct(
                keyword = "iphone 15",
                title = "官翻 Apple iPhone 15 128GB 官方翻新机",
                price = 4999.0,
                referencePrice = 5999.0
            )
        )
    }

    @Test
    fun `accessories are rejected`() {
        assertFalse(
            RelevanceGuard.isLikelySameProduct(
                keyword = "iphone 15",
                title = "iPhone 15 钢化膜 高清防爆保护贴",
                price = 19.9,
                referencePrice = 5999.0
            )
        )
        assertFalse(
            RelevanceGuard.isLikelySameProduct(
                keyword = "iphone 15",
                title = "Apple iPhone 15 原装充电器 20W 快充头",
                price = 149.0,
                referencePrice = 5999.0
            )
        )
    }

    @Test
    fun `compatible third party product is rejected`() {
        assertFalse(
            RelevanceGuard.isLikelySameProduct(
                keyword = "iphone 15",
                title = "兼容 Apple iPhone 15 全系列 通用型",
                price = 59.0,
                referencePrice = 5999.0
            )
        )
    }

    @Test
    fun `book or manual is rejected`() {
        assertFalse(
            RelevanceGuard.isLikelySameProduct(
                keyword = "iphone 15",
                title = "iPhone 15 使用指南 图书 正版",
                price = 39.0,
                referencePrice = 5999.0
            )
        )
    }

    @Test
    fun `rental and repair service are rejected`() {
        assertFalse(
            RelevanceGuard.isLikelySameProduct(
                keyword = "iphone 15",
                title = "Apple iPhone 15 租赁 免押金",
                price = 300.0,
                referencePrice = 5999.0
            )
        )
        assertFalse(
            RelevanceGuard.isLikelySameProduct(
                keyword = "iphone 15",
                title = "Apple iPhone 15 维修 换屏服务",
                price = 800.0,
                referencePrice = 5999.0
            )
        )
    }

    // ---------- 反例：价格带 / 文本 ----------

    @Test
    fun `price far below band is rejected`() {
        // 真机样本：什么值得买爆料价 ¥2,898 vs 候选 ¥5,999（比值 0.48 < 0.5）
        assertFalse(
            RelevanceGuard.isLikelySameProduct(
                keyword = "iphone 15",
                title = "Apple iPhone 15 128GB 蓝色 5G手机",
                price = 2898.0,
                referencePrice = 5999.0
            )
        )
    }

    @Test
    fun `price far above band is rejected`() {
        assertFalse(
            RelevanceGuard.isLikelySameProduct(
                keyword = "iphone 15",
                title = "Apple iPhone 15 128GB 蓝色 5G手机",
                price = 11999.0,
                referencePrice = 5999.0
            )
        )
    }

    @Test
    fun `invalid price is rejected`() {
        assertFalse(
            RelevanceGuard.isLikelySameProduct(
                keyword = "iphone 15",
                title = "Apple iPhone 15 128GB 蓝色 5G手机",
                price = 0.0,
                referencePrice = 5999.0
            )
        )
        assertFalse(
            RelevanceGuard.isLikelySameProduct(
                keyword = "iphone 15",
                title = "Apple iPhone 15 128GB 蓝色 5G手机",
                price = Double.NaN,
                referencePrice = 5999.0
            )
        )
    }

    @Test
    fun `blank keyword or title is rejected`() {
        assertFalse(
            RelevanceGuard.isLikelySameProduct("", "Apple iPhone 15 128GB 蓝色", 5999.0, 5999.0)
        )
        assertFalse(
            RelevanceGuard.isLikelySameProduct("   ", "Apple iPhone 15 128GB 蓝色", 5999.0, 5999.0)
        )
        assertFalse(
            RelevanceGuard.isLikelySameProduct("iphone 15", "", 5999.0, 5999.0)
        )
    }

    @Test
    fun `unrelated product is rejected by query relevance`() {
        assertFalse(
            RelevanceGuard.isLikelySameProduct(
                keyword = "iphone 15",
                title = "小米 14 Ultra 16GB+512GB 手机",
                price = 6499.0,
                referencePrice = 5999.0
            )
        )
    }

    // ---------- 清单 / 常量守卫 ----------

    @Test
    fun `negative tokens cover real device garbage categories`() {
        val tokens = RelevanceGuard.negativeTokens()
        assertTrue(tokens.isNotEmpty())
        assertTrue(
            "否定词缺少真机实测类别",
            tokens.containsAll(
                listOf(
                    "资源机", "官换机", "翻新", "二手", "租赁", "维修",
                    "配件", "保护壳", "贴膜", "数据线", "充电器",
                    "适用", "兼容", "适配", "医疗", "图书", "纸书"
                )
            )
        )
    }

    @Test
    fun `contains negative token helper works`() {
        assertTrue(RelevanceGuard.containsNegativeToken("资源机 Apple/苹果 iPhone 15"))
        assertFalse(RelevanceGuard.containsNegativeToken("Apple iPhone 15 128GB 蓝色 5G手机"))
    }

    @Test
    fun `ratio constants stay aligned with auto track policy`() {
        assertEquals(AutoTrackPolicy.MIN_PRICE_RATIO, RelevanceGuard.MIN_PRICE_RATIO, 0.0)
        assertEquals(AutoTrackPolicy.MAX_PRICE_RATIO, RelevanceGuard.MAX_PRICE_RATIO, 0.0)
    }
}
