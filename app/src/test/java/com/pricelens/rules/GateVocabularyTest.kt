package com.pricelens.rules

import com.pricelens.accessibility.PageVocabulary
import com.pricelens.accessibility.ShopPlatform
import com.pricelens.accessibility.container
import com.pricelens.accessibility.isProductPage
import com.pricelens.accessibility.leaf
import com.pricelens.rules.DetectionPipeline.DetectionOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `gate`（商详门控词表的远端可改副本）的语义与校验闸门。
 *
 * 这批用例存在的理由是一条真机事故：2026-10-03 京东国补商品把底栏「立即购买」换成
 * 「领取补贴购买」，而词表当时写死在 [com.pricelens.accessibility.PriceNodeMatcher] 里 ——
 * 唯一的修法是发一次 APK（就是 2.8.0.1）。把词表做成**数据**之后，同一件事的修法应该是
 * "推一条规则"，所以下面第三条用例（远程一条词救回一页）是这个能力的**唯一判别证据**：
 * 它必须在没有这条通道时红、有这条通道时绿。
 *
 * 与 [BuiltinRuleTest] 的分工：那边管"出厂规则文件本身没被改坏"，这边管
 * "gate 字段的解析口径与覆盖语义"。
 */
class GateVocabularyTest {

    private fun parse(raw: String): PlatformRuleResult = RuleJson.parsePlatformRule(raw, expectedId = null)

    private fun validRule(raw: String): PlatformRule {
        val result = parse(raw)
        assertTrue("规则必须能解析，实际被拒绝：$result", result is PlatformRuleResult.Valid)
        return (result as PlatformRuleResult.Valid).rule
    }

    private fun rejected(raw: String): String {
        val result = parse(raw)
        assertTrue("规则本应被拒绝，实际通过了：$result", result is PlatformRuleResult.Rejected)
        return (result as PlatformRuleResult.Rejected).reason
    }

    /** 只带一条 gate 字段的规则（其余字段应沿用出厂值） */
    private fun ruleWithGate(gate: String, buyNowSelector: String = "立即购买"): String = """
        {
          "schemaVersion": 1,
          "id": "jd",
          "version": 7,
          "packages": ["com.jingdong"],
          "pages": [{
            "name": "product_detail",
            "extract": {
              "title": [{ "by": "textRegex", "value": "^[^¥￥]{10,80}$" }],
              "price": [{ "by": "textRegex", "value": "[¥￥]\\s*\\d[\\d,]*(?:\\.\\d{1,2})?" }],
              "buyNow": [{ "by": "textRegex", "value": "$buyNowSelector" }]
            },
            "confirm": { "allOf": ["title", "price", "buyNow"] }
          }],
          "gate": $gate
        }
    """.trimIndent()

    /**
     * 一页"底栏文案换了别的说法"的京东商详（结构参照 [com.pricelens.accessibility.jdDetailPage]
     * 的形态，但**去掉**「商品详情」分区标记、把底栏按钮换成出厂词表里不存在的新说法）：
     * 于是门控只能靠 `buyNow` 这一关，而这一关完全由词表决定。
     */
    private fun detailPageWithRenamedBar(button: String) = container(
        kids = arrayOf(
            leaf(text = "¥5,499", res = "com.jingdong.app.mall:id/jd_price"),
            container(
                "com.jingdong.app.mall:id/goods_title",
                clickable = true,
                kids = arrayOf(leaf(text = "HUAWEI Mate 80 12GB+256GB 曜石黑 鸿蒙AI 第二代红枫影像"))
            ),
            leaf(
                desc = button,
                res = "com.jingdong.app.mall:id/buy_now",
                clickable = true,
                cls = "android.widget.Button"
            )
        )
    )

    @Test
    fun `shipped jd rule gate is byte-for-byte the factory vocabulary`() {
        val rule = loadedBuiltinJdRule()
        assertEquals(
            "rules/jd.json 的 gate 与出厂 PageVocabulary.DEFAULT 漂移了 —— 两份真相可以并存，" +
                "但不许悄悄不一致：改任何一边都要同时改另一边（改 gate 是为了让远端能救，" +
                "改 DEFAULT 是为了让没有规则包的宿主也能救）",
            PageVocabulary.DEFAULT,
            rule.vocabulary
        )
    }

    @Test
    fun `rule without gate keeps the factory vocabulary`() {
        val raw = """
            {
              "schemaVersion": 1,
              "id": "jd",
              "version": 1,
              "packages": ["com.jingdong"],
              "pages": [{
                "name": "product_detail",
                "extract": { "price": [{ "by": "textRegex", "value": "[¥￥]\\d+" }] },
                "confirm": { "allOf": ["price"] }
              }]
            }
        """.trimIndent()
        assertEquals(PageVocabulary.DEFAULT, validRule(raw).vocabulary)
    }

    @Test
    fun `one remote gate word rescues a page the factory wording rejects`() {
        val page = detailPageWithRenamedBar("限时直购")

        // 红锚：出厂词表下这一页判不成商详（= 真机上"浮窗不弹"的那个症状）
        assertFalse(
            "出厂词表居然认得「限时直购」= 词表被改宽了，这条用例的判别力归零",
            isProductPage(page, ShopPlatform.JD)
        )

        // 同一棵树、同一条规则（规则的 buyNow 选择器**故意仍然只认「立即购买」**，
        // 好让规则路径确认失败、把决定权交给启发式路径的词表 —— 这样测的就是 gate 本身）
        val rule = validRule(ruleWithGate("""{ "buyAction": ["限时直购"], "buyNow": ["限时直购"] }"""))
        val outcome = DetectionPipeline.detect(page, ShopPlatform.JD, "com.jingdong.app.mall", RuleSet(listOf(rule)))
        assertTrue("推了 gate 之后这一页仍判非商详 = 远程改词通道没接通：$outcome", outcome is DetectionOutcome.Hit)
        val detection = (outcome as DetectionOutcome.Hit).detection
        assertEquals(
            "这一页是靠 gate 救回来的，命中来源必须是启发式（规则路径的 buyNow 选择器没命中）",
            DetectionPipeline.DetectionSource.HEURISTIC,
            detection.source
        )
        assertTrue(detection.matchedBy.contains("词表=规则 gate"))
    }

    @Test
    fun `gate overrides only the fields it spells out`() {
        val rule = validRule(ruleWithGate("""{ "buyNow": ["限时直购"] }"""))
        assertEquals(listOf("限时直购"), rule.vocabulary.buyNow)
        assertEquals(PageVocabulary.DEFAULT.buyAction, rule.vocabulary.buyAction)
        assertEquals(PageVocabulary.DEFAULT.checkout, rule.vocabulary.checkout)
        assertEquals(PageVocabulary.DEFAULT.detailSection, rule.vocabulary.detailSection)
        assertEquals(PageVocabulary.DEFAULT.pddSingleBuy, rule.vocabulary.pddSingleBuy)
        assertEquals(PageVocabulary.DEFAULT.pddGroupBuy, rule.vocabulary.pddGroupBuy)
    }

    @Test
    fun `a gate turning off every positive action signal is rejected`() {
        val reason = rejected(
            ruleWithGate(
                """{ "buyAction": [], "buyNow": [], "pddSingleBuy": [], "pddGroupBuy": [] }"""
            )
        )
        assertTrue(
            "全关正向信号 = 任何页面都判非商详 = 静默什么都不显示，必须当场拒绝：$reason",
            reason.contains("noPositiveActionSignal")
        )
        // 反过来：只关 buyNow、留着 buyAction，是合法收紧（只会少弹窗，不会误弹）
        validRule(ruleWithGate("""{ "buyNow": [] }"""))
    }

    @Test
    fun `gate words are matched as substrings so regex bodies are rejected`() {
        listOf(
            """["立即购买|马上抢"]""" to "regexMeta",
            """["^立即购买$"]""" to "regexMeta",
            """["\\d{4}"]""" to "regexMeta",
            """["${"超长".padEnd(31, '词')}"]""" to "length",
            """["短"]""" to "length",
            """["立即购买", "立即购买"]""" to "duplicate",
            """["含\t制表符"]""" to "nonPrintable"
        ).forEach { (body, expected) ->
            val reason = rejected(ruleWithGate("""{ "buyNow": $body }"""))
            assertTrue("gate=$body 应被拒且原因含 $expected，实际：$reason", reason.contains(expected))
        }
    }

    @Test
    fun `gate word count is capped`() {
        val words = (1..33).joinToString(",") { "\"购买${it}号按钮\"" }
        val reason = rejected(ruleWithGate("""{ "buyNow": [$words] }"""))
        assertTrue("超条数应被拒：$reason", reason.contains("count=33"))
    }

    @Test
    fun `gate does not bump schema version so shipped builds still accept new rules`() {
        // 抬 schemaVersion 会让已出厂的 2.8.0.1 整包拒绝新规则 —— 那是把兼容性改进
        // 变成强制升级。gate 是"可选新增 + 未知字段忽略"，老客户端读不到也不会坏。
        assertEquals(1, RuleJson.SCHEMA_VERSION)
    }
}
