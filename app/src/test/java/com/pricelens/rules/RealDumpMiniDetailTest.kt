package com.pricelens.rules

import com.pricelens.accessibility.ShopPlatform
import com.pricelens.accessibility.loadRealDump
import com.pricelens.rules.DetectionPipeline.DetectionOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 京东**迷你详情页**（`productdetailmini.PdMiniImmerseActivity`）在判据层面必须命中。
 *
 * 夹具：`jd_detail_mini_pdminiimmerse_20261006.xml` —— 真机（vivo V2156A / 京东 App）原样 dump。
 * 这一页是 2026-10-06 才发现的入口变化：**首页推荐流的卡片现在一律落迷你页**，
 * 而我们的浮窗在那一页一条都不出（同一轮服务侧 `A11Y-EVT` 396 条、`root=false` 零条）。
 *
 * 我据此给出的假设是：「出厂规则的页门是 `activityRegex = ".*ProductDetail.*"`，
 * 大小写敏感地跨不过 `productdetailmini.PdMiniImmerseActivity`，所以这一页被当成非商详」。
 * **这个假设是错的，被本测试当场推翻**：把这棵真的迷你页树喂进 `DetectionPipeline`，
 * 带 Activity 名与不带 Activity 名两条入口都命中，价格 `¥334` 与商品名都对。
 * 也就是说判据认这一页，迷你页的树也确实把内容给了无障碍（111 节点、含底栏「立即购买」）。
 *
 * 那真机上为什么没弹？剩下的解释只有一个方向：**判据拿到的不是那棵落定后的树**——
 * 迷你页的价格是异步渲染的，渲染完成时如果没有再来一次 `TYPE_WINDOW_CONTENT_CHANGED`，
 * 管线就只见过"还没有价"的那一帧。这一条留给下一轮用探针查（打印事件时刻的节点数与
 * 是否存在带 ¥ 的文本），不在这里改判据。
 *
 * 这个测试因此是**回归钉**而不是修复证明：它钉住"迷你页在判据层面必须命中"，
 * 并且把"页门大小写"这条错诊断留在这里，免得下次又照着它去改正则。
 */
class RealDumpMiniDetailTest {

    private val mini get() = loadRealDump(MINI_DUMP)
    private val rules get() = RuleSet(listOf(loadedBuiltinJdRule()))

    @Test
    fun `窗口切换事件落在迷你页时也要命中`() {
        val outcome = DetectionPipeline.detect(mini.root, ShopPlatform.JD, JD_PKG, rules, MINI_ACTIVITY)
        assertTrue("迷你商详页必须命中，实际=$outcome", outcome is DetectionOutcome.Hit)
        val d = (outcome as DetectionOutcome.Hit).detection
        assertEquals("底栏主价 ¥334", 334.0, d.price.value, 0.0001)
        val title = d.title ?: ""
        assertTrue("标题必须是商品名而不是导航词，实际=$title", title.startsWith("LAN兰时光"))
    }

    @Test
    fun `内容变化事件（activityName 为空）在迷你页同样命中`() {
        val outcome = DetectionPipeline.detect(mini.root, ShopPlatform.JD, JD_PKG, rules, null)
        assertTrue(
            "activityName 为 null 时页门放行，这一路必须命中，实际=$outcome",
            outcome is DetectionOutcome.Hit
        )
        assertEquals(334.0, (outcome as DetectionOutcome.Hit).detection.price.value, 0.0001)
    }

    private companion object {
        const val MINI_DUMP = "jd_detail_mini_pdminiimmerse_20261006.xml"
        const val MINI_ACTIVITY = "com.jd.lib.productdetailmini.PdMiniImmerseActivity"
        const val JD_PKG = "com.jingdong.app.mall"
    }
}
