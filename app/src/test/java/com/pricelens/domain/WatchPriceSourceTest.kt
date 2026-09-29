package com.pricelens.domain

import com.pricelens.util.PriceJudgment
import com.pricelens.util.judgePrice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F1 回归（2026-09-29）：星罗好货是**历史低价榜**（`jd_historyLowPriceRank`），
 * 它的 `real_money`（券后历史低价）不得冒充「本轮现价」。
 *
 * 三条用例对应作业书的三条，都落在「字段选择 + 来源判定」这一层，
 * 因此可以纯 JVM 跑（不碰 Context/Room/网络）：
 *  1. 榜单同时有在售价与券后历史低价 → 现价取在售价，来源 LINKSTARS_LIST；
 *  2. 榜单只有券后历史低价 → 值可以留着给 UI 当参考，但既不触发降价通知、也不进曲线；
 *  3. 这条参考值单独存在时，曲线与 `judgePrice` 都不该被它拉成「≈ 历史低价 / 可入」。
 *
 * 每条都带**对照组**：同一个数值若来源是真现价，就必须照常触发、照常参与判定——
 * 否则「绿」可能只是因为断言根本没管来源。
 */
class WatchPriceSourceTest {

    private val sku = "100012043978"

    /** 目标价 5000：在售价 5999 不该报警，券后历史低价 4999 也不该报警 */
    private fun target(price: Double = 5000.0) = WatchTargetRef("jd:$sku", "jd", price)

    private fun roundOf(sample: PriceSample) =
        WatchTargetPolicy.classifyRound(listOf(target()), mapOf("jd" to mapOf(sku to sample)), emptySet())

    // ---------- ① 在售价优先，且来源标成 LINKSTARS_LIST ----------

    @Test
    fun `listed price wins over the ranked coupon price when p3cn is down`() {
        val sample = PriceSampling.linkstarsSample(listPrice = 5999.0, couponPrice = 4999.0)
        assertEquals("现价必须取 goods_list_money（在售价）", 5999.0, sample?.price ?: 0.0, 0.001)
        assertEquals("来源必须标成在售价，不能标成历史低价", PriceSource.LINKSTARS_LIST, sample?.source)

        val round = PriceSampling.composeJd(
            p3cn = emptyMap(),
            references = mapOf(sku to requireNotNull(sample)),
            p3cnFailed = true
        )
        assertEquals(mapOf(sku to PriceSample(5999.0, PriceSource.LINKSTARS_LIST)), round)

        // 5999 > 目标 5000：价格并没有降到目标以下，一条通知都不该发。
        // 旧实现在这里用的是 4999（历史低价），于是「价格没降也发降价通知」。
        val report = WatchTargetPolicy.classifyRound(listOf(target()), mapOf("jd" to requireNotNull(round)), emptySet())
        assertEquals(1, report.checked)
        assertTrue(
            "在售价高于目标价时不得触发降价通知，实际 ${report.triggeredProductIds}",
            report.triggeredProductIds.isEmpty()
        )
        assertEquals(0, report.skipped.referenceOnly)
    }

    // ---------- ② 只有券后历史低价时：允许留作参考，但不通知、不进曲线 ----------

    @Test
    fun `ranked coupon price stays a reference and never alerts or joins the curve`() {
        val sample = requireNotNull(PriceSampling.linkstarsSample(listPrice = 0.0, couponPrice = 4999.0))
        assertEquals(4999.0, sample.price, 0.001)
        assertEquals("取的是 real_money，来源要如实标成券后历史低价", PriceSource.LINKSTARS_HISTORY_LOW, sample.source)
        assertNull("券后历史低价没有资格当今日的曲线采样点", PriceSampling.curveWorthy(sample))

        val round = requireNotNull(PriceSampling.composeJd(emptyMap(), mapOf(sku to sample), p3cnFailed = true))
        val report = WatchTargetPolicy.classifyRound(listOf(target()), mapOf("jd" to round), emptySet())
        assertTrue("参考值不得触发降价通知，实际 ${report.triggeredProductIds}", report.triggeredProductIds.isEmpty())
        assertEquals("参考值不算「本轮查到现价」", 0, report.checked)
        assertEquals(1, report.skipped.noPrice)
        assertEquals("脚注要能说出「只有参考值」这个成因", 1, report.skipped.referenceOnly)

        // 对照组：同样 4999，来源换成真·京东在售价 → 必须照常触发
        // （证明上面两条断言管的是来源，不是数值）
        val live = roundOf(PriceSample(4999.0, PriceSource.JD_P3CN))
        assertEquals(1, live.checked)
        assertEquals(listOf("jd:$sku"), live.triggeredProductIds)
        assertEquals(0, live.skipped.referenceOnly)
    }

    // ---------- ③ 参考值不参与 judgePrice 的「可入」结论 ----------

    @Test
    fun `a coupon-only reference does not tilt the buy signal`() {
        val real = listOf(5200.0, 6500.0, 6400.0) // 自建曲线里真实采到的三天
        val reference = requireNotNull(PriceSampling.linkstarsSample(listPrice = 0.0, couponPrice = 4999.0))
        val curve = real + listOfNotNull(PriceSampling.curveWorthy(reference)?.price)
        assertEquals("历史低价参考不该被写成「今天的点」混进曲线", real, curve)

        val current = curve.last() // 6400：本轮真实在售价
        assertTrue(
            "没有参考值掺和时该判「常规价格」，实际是 ${judgmentName(current, curve)}",
            judgePrice(current, curve) is PriceJudgment.NORMAL
        )

        // 对照组：旧行为把 4999 写成「今天」的点 → current 与 lowest 同时变成 4999 → 恒判「≈ 历史低价」
        val polluted = real + reference.price
        assertTrue(
            "对照：曲线一旦被参考低价污染就必须判成 LOW，说明这条用例真能区分新旧行为",
            judgePrice(polluted.last(), polluted) is PriceJudgment.LOW
        )
    }

    private fun judgmentName(current: Double, curve: List<Double>): String = when (judgePrice(current, curve)) {
        is PriceJudgment.LOW -> "≈ 历史低价"
        is PriceJudgment.SUSPICIOUS -> "疑似先涨后降"
        is PriceJudgment.NORMAL -> "常规价格"
    }
}
