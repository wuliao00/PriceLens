package com.pricelens.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SearchQueryCleaner] 表驱动测试（A2 P0-3：取代 `title.take(30)` 盲截断）。
 * 夹具为 2026-09 京东/淘宝/PDD 商详标题实况（脱敏改写）：截半型号会让搜索命中
 * X8/X8s/X8 Ultra 的第一条并拿到旧款价，是本组用例的核心回归点。
 */
class SearchQueryCleanerTest {

    @Test
    fun `bracket promo prefix and capacity combo and trailing color are stripped`() {
        assertEquals(
            "HUAWEI Mate 80",
            SearchQueryCleaner.clean("【国家补贴】HUAWEI Mate 80 12GB+256GB 曜石黑")
        )
        assertEquals(
            "OPPO Find X8",
            SearchQueryCleaner.clean("OPPO Find X8 12GB+256GB 霜月白")
        )
    }

    @Test
    fun `model token is never halved by truncation`() {
        // 旧 take(30) 会把"…12GB+256GB 曜石黑"截到"曜"，或用截半型号搜到旧款
        val cleaned = SearchQueryCleaner.clean("【国家补贴】HUAWEI Mate 80 12GB+256GB 曜石黑")!!
        assertFalse(cleaned.contains("曜"))
        assertFalse(cleaned.contains("GB"))
    }

    @Test
    fun `unit spec tokens removed`() {
        assertEquals(
            "小米平板7 WiFi",
            SearchQueryCleaner.clean("小米平板7 11.2英寸 12GB+256GB WiFi")
        )
        val buds = SearchQueryCleaner.clean("REDMI Buds8 Pro 5000mAh超长续航 深灰")!!
        assertFalse(buds.contains("5000"))
        assertFalse(buds.contains("深灰"))
        assertTrue(buds.contains("Buds8"))
    }

    @Test
    fun `short titles pass through untouched`() {
        assertEquals("小米13 Ultra", SearchQueryCleaner.clean("小米13 Ultra"))
        // 无空格粘连颜色词不拆（避免误伤"福鼎老白茶"类商品名），保留原样
        assertEquals("小米13曜石黑", SearchQueryCleaner.clean("小米13曜石黑"))
    }

    @Test
    fun `truncation happens at token boundary with spaces`() {
        val long = "小米 Redmi Note 13 Pro 5G 游戏性能旗舰 AI 大内存手机 官方标配"
        val cleaned = SearchQueryCleaner.clean(long)!!
        assertEquals("小米 Redmi Note 13 Pro 5G 游戏性能旗舰 AI 大内存手机", cleaned)
        assertTrue(cleaned.length <= 40)
    }

    @Test
    fun `truncation never splits a latin model token`() {
        val long = "三星 Galaxy S24Ultra 秘色银 官方标配版 5G双卡双待手机防水防尘"
        val cleaned = SearchQueryCleaner.clean(long)!!
        assertTrue(cleaned.length <= 40)
        // "5G双卡…" 整词被丢弃；保留下来的 S24Ultra 完整
        assertTrue(cleaned.contains("S24Ultra"))
        assertFalse(cleaned.contains("5G"))
    }

    @Test
    fun `pure cjk oversize cuts at max length`() {
        val long = "索尼头戴式降噪耳机旗舰无线蓝牙长续航舒适佩戴耳罩正品行货国行补贴专享特惠版型号赠品袋优惠必入"
        val cleaned = SearchQueryCleaner.clean(long)!!
        assertEquals(40, cleaned.length)
    }

    @Test
    fun `blank and fully-bracketed inputs return null`() {
        assertNull(SearchQueryCleaner.clean(null))
        assertNull(SearchQueryCleaner.clean("   "))
        assertNull(SearchQueryCleaner.clean("【包邮】"))
    }

    @Test
    fun `overlap scoring separates brand-model from lookalikes`() {
        // 同品牌不同表述（括号英文名夹在中间）：重叠分保留展示下限之上
        assertTrue(SearchQueryCleaner.titleOverlap("戴森吹风机", "戴森(DYSON)吹风机HD16 彩盒装") >= 0.6)
        // 搜 iPhone 15、候选是"小米 15 Pro 手机"：完全不相干 → 0 分（判为上一次商品数据）
        assertEquals(0.0, SearchQueryCleaner.titleOverlap("iPhone 15", "小米 15 Pro 手机"), 0.001)
        // 同款：满分方向
        assertTrue(SearchQueryCleaner.titleOverlap("小米 15 手机", "小米 15 5G 手机 16GB+512GB") > 0.9)
    }
}
