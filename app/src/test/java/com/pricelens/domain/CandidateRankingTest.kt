package com.pricelens.domain

import com.pricelens.util.QueryRelevance
import java.io.InputStreamReader
import kotlin.math.abs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CandidateRanking] 打分基线。
 *
 * 两部分：
 *  1. 权重逐项断言（纯函数，好定位）；
 *  2. 2026-09-28 实况回放：夹具 `fixtures/live_candidate_pools.json` 是 7 个真实关键词在
 *     当当 + 值得买列表页上抓到的条目（未过滤），这里重跑
 *     "[QueryRelevance] 过滤 → 跨源打分" 全链路，断言 argmax 与人工核对结果一致
 *     （旧口径"取第一条带价"选出的候选见各用例注释）。
 * 桌面端 `desktop/_unit_check.js` 用**同一夹具、同一期望值**跑同一套规则。
 */
class CandidateRankingTest {

    private fun fixtureText(name: String): String {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) { "缺少夹具 $name" }
        return InputStreamReader(stream, Charsets.UTF_8).use { it.readText() }
    }

    /** 候选池：把夹具里的 (title, price, source) 变成打分输入 */
    private fun inputsOf(group: JSONObject): List<CandidateRanking.Rankable> {
        val keyword = group.getString("keyword")
        val items = group.getJSONArray("items")
        val pool = ArrayList<CandidateRanking.Rankable>(items.length())
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            val title = o.getString("title")
            val price = o.getDouble("price")
            // 与 App 链路一致：各源先过相关性，再进候选打分
            if (price > 0.0 && QueryRelevance.isRelevant(keyword, title)) {
                pool += CandidateRanking.Rankable(title, price)
            }
        }
        return pool
    }

    private fun bestPrice(keyword: String, pool: List<CandidateRanking.Rankable>): Double {
        val best = CandidateRanking.rank(keyword, pool).firstOrNull()
        checkNotNull(best) { "关键词 [$keyword] 候选池为空" }
        return best.item.price
    }

    // ===== 1. 权重逐项 =====

    @Test
    fun `category word is the dominant positive signal`() {
        val plain = CandidateRanking.score("某某商品 12GB+512GB", 3000.0, 3000.0, "mate 80")
        val withCategory = CandidateRanking.score("华为 Mate 80 手机 12GB+512GB", 3000.0, 3000.0, "mate 80")
        assertEquals(CandidateRanking.WEIGHT_CATEGORY, withCategory - plain, 1e-9)
    }

    @Test
    fun `spec completeness and subsidy add up`() {
        assertEquals(
            "品类 2.0 + 规格 1.0 + 国补 0.5",
            3.5,
            CandidateRanking.score("国家补贴：OPPO Find X8s+ 5G手机 12GB+512GB", 2860.0, 2860.0, "oppo x8s"),
            1e-9
        )
        // "12+256GB"（缺第一个 GB）与 "16GB+1TB" 同样算规格完整
        assertTrue(CandidateRanking.isSpecComplete("oppofindx8s5g手机12+256gb"))
        assertTrue(CandidateRanking.isSpecComplete("华为mate80promax手机16gb+1tb"))
        // 只有屏幕尺寸没有容量 → 规格不完整（实况里这类"6.75英寸直屏旗舰"是渠道混卖标题）
        assertTrue(!CandidateRanking.isSpecComplete("华为mate80学生商务通用手机6.75英寸直屏旗舰"))
    }

    @Test
    fun `secondhand and multi model listings are penalised`() {
        // 实测条目：搜 iPhone 15 拿到的第一条就是这个二手混卖链接
        val secondhand = "Apple iPhone15/14/13/12/苹果 15/14/13/12 全网通激活无使用 黑色"
        val score = CandidateRanking.score(secondhand, 4248.0, 4248.0, "iPhone 15")
        // 无品类词 0 − 二手 2.5 − 多机型 1.5 = −4.0
        assertEquals(-4.0, score, 1e-9)
        assertEquals(
            CandidateRanking.WEIGHT_SECONDHAND,
            CandidateRanking.score("iPhone 15 手机 95新", 4000.0, 4000.0, "iPhone 15") -
                CandidateRanking.score("iPhone 15 手机 12GB+256GB", 4000.0, 4000.0, "iPhone 15") +
                CandidateRanking.WEIGHT_SPEC,
            1e-9
        )
    }

    @Test
    fun `price band pulls outliers away from the median`() {
        val low = CandidateRanking.score("华为 Mate 80 手机 12GB+512GB", 2000.0, 4000.0, "mate 80")
        val onMedian = CandidateRanking.score("华为 Mate 80 手机 12GB+512GB", 4000.0, 4000.0, "mate 80")
        // 偏离一个倍率 → -1.2 * |ln(0.5)| = -1.2 * ln2
        assertEquals(CandidateRanking.WEIGHT_PRICE_BAND * kotlin.math.ln(2.0), low - onMedian, 1e-9)
        // 偏离方向对称：过高/过低同样扣分
        val high = CandidateRanking.score("华为 Mate 80 手机 12GB+512GB", 8000.0, 4000.0, "mate 80")
        assertEquals(low, high, 1e-9)
    }

    @Test
    fun `series suffix only downweights, never filters out of the pool`() {
        val keyword = "mate 80"
        val pool = listOf(
            CandidateRanking.Rankable("华为 Mate 80 手机 12GB+512GB 雪域白", 4079.15),
            CandidateRanking.Rankable("华为 Mate 80 Pro 手机 12GB+512GB 晨曦金", 4079.15)
        )
        val ranked = CandidateRanking.rank(keyword, pool)
        assertEquals("后缀条目必须仍在列表里", 2, ranked.size)
        assertEquals(pool[0].title, ranked[0].item.title)
        assertEquals(CandidateRanking.WEIGHT_SERIES_SUFFIX, ranked[1].score - ranked[0].score, 1e-9)
    }

    @Test
    fun `missing brand in title costs one point`() {
        // query 命中"小米"，标题没写品牌任何写法（第三方混卖）→ 只扣品牌分：
        // 品类词/国补/规格都不在标题里，与桌面端 _unit_check.js 同一条用例同输入同期望。
        assertEquals(
            CandidateRanking.WEIGHT_BRAND_MISSING,
            CandidateRanking.score("全网通 5G 15", 3000.0, 0.0, "小米 15"),
            1e-9
        )
    }

    @Test
    fun `query already mentioning the suffix does not cost anything`() {
        val withSuffixInQuery = CandidateRanking.score("Apple iPhone 15 Pro 5G手机 256GB", 5000.0, 0.0, "iPhone 15 Pro")
        val plainMatch = CandidateRanking.score("Apple iPhone 15 5G手机 256GB", 5000.0, 0.0, "iPhone 15")
        assertEquals(withSuffixInQuery, plainMatch, 1e-9)
    }

    @Test
    fun `rank is stable on ties so dangdang keeps priority`() {
        val pool = listOf(
            CandidateRanking.Rankable("当当同款 手机 12GB+256GB", 3000.0),
            CandidateRanking.Rankable("值得买同款 手机 12GB+256GB", 3000.0)
        )
        val ranked = CandidateRanking.rank("手机 12gb", pool)
        assertEquals(2, ranked.size)
        assertEquals(pool[0].title, ranked[0].item.title) // 同分 → 先入池者（当当）胜出
    }

    // ===== 2. 实况回放（夹具 = 2026-09-28 当当 + 值得买列表页） =====

    @Test
    fun `live pools pick the expected candidate instead of the first priced row`() {
        val root = JSONObject(fixtureText("live_candidate_pools.json"))
        val groups = root.getJSONArray("groups")
        // 键 = 关键词，值 = 打分后应选中的价格。下面几行是旧口径（各源过滤后取第一条带价）
        // 选出的候选，仅作对照，不参与断言：
        //   oppo x8s → 无候选（相关性被误杀）      OPPO Find X8s → ¥3399.00 今日必买
        //   x8s → ¥108.80 纸品（Scrapbook Paper Pad）  mate 80 / Mate80 → ¥8840.95 渠道价
        //   华为 Mate 80 → ¥4249.15               iPhone 15 → ¥4248.00 二手混卖
        val expected = mapOf(
            "oppo x8s" to 2860.00,
            "OPPO Find X8s" to 2860.00,
            "x8s" to 2860.00,
            "mate 80" to 4079.15,
            "Mate80" to 4572.15,
            "华为 Mate 80" to 4279.00,
            "iPhone 15" to 7561.01
        )
        assertEquals("夹具关键词组数", expected.size, groups.length())
        for (i in 0 until groups.length()) {
            val group = groups.getJSONObject(i)
            val keyword = group.getString("keyword")
            val pool = inputsOf(group)
            assertTrue("[$keyword] 过滤后候选池不应为空", pool.isNotEmpty())
            assertEquals("[$keyword] 打分候选价格", expected.getValue(keyword), bestPrice(keyword, pool), 0.01)
        }
    }

    @Test
    fun `live mate 80 pool beats the channel price`() {
        val group = liveGroup("mate 80")
        val pool = inputsOf(group)
        val ranked = CandidateRanking.rank("mate 80", pool)
        assertEquals(4079.15, ranked[0].item.price, 0.01)
        assertEquals(3.0, ranked[0].score, 0.01) // 品类 2.0 + 规格 1.0，价格正好是中位价
        // 旧口径的 ¥8840.95 渠道价（无容量规格、价格离群）仍在列表里，但排在候选之后
        val channel = ranked.firstOrNull { abs(it.item.price - 8840.95) < 0.01 }
        checkNotNull(channel) { "实况池里应有那条 ¥8840.95 渠道价" }
        assertTrue(channel.score < ranked[0].score)
        assertTrue(ranked.indexOf(channel) > 0)
    }

    @Test
    fun `live mate 80 pool keeps the pro max variant in the list`() {
        // Pro/Max 只降权不过滤
        val pool = inputsOf(liveGroup("mate 80"))
        val ranked = CandidateRanking.rank("mate 80", pool)
        assertEquals("13 条带价相关条目应全部留在列表里", pool.size, ranked.size)
        assertTrue(ranked.any { it.item.title.contains("Pro Max", ignoreCase = true) })
        assertTrue(ranked.first { it.item.title.contains("Pro Max", ignoreCase = true) }.score < ranked[0].score)
    }

    @Test
    fun `live iphone 15 pool rejects the secondhand listing`() {
        val group = liveGroup("iPhone 15")
        val pool = inputsOf(group)
        val ranked = CandidateRanking.rank("iPhone 15", pool)
        assertEquals("¥4248 的二手混卖条目应被过滤/压到最后", 7561.01, ranked[0].item.price, 0.01)
        val second = ranked.firstOrNull { abs(it.item.price - 4248.0) < 0.01 }
        if (second != null) assertTrue("二手混卖条目应为负分", second.score < 0.0)
    }

    @Test
    fun `live oppo x8s pool is no longer empty`() {
        // 根因回归：旧规则把 oppo x8s 当成整串 token，当当/值得买 22 条全被剔掉
        val group = liveGroup("oppo x8s")
        val rawItems = group.getJSONArray("items")
        var relevant = 0
        for (i in 0 until rawItems.length()) {
            if (QueryRelevance.isRelevant("oppo x8s", rawItems.getJSONObject(i).getString("title"))) relevant++
        }
        assertTrue("分段规则应至少让 10 条 OPPO Find X8s 整机通过", relevant >= 10)
        assertEquals(2860.00, bestPrice("oppo x8s", inputsOf(group)), 0.01)
    }

    private fun liveGroup(keyword: String): JSONObject {
        val groups = JSONObject(fixtureText("live_candidate_pools.json")).getJSONArray("groups")
        for (i in 0 until groups.length()) {
            val g = groups.getJSONObject(i)
            if (g.getString("keyword") == keyword) return g
        }
        error("夹具缺少关键词 [$keyword]")
    }
}
