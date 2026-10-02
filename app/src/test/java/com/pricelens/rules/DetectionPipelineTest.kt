package com.pricelens.rules

import com.pricelens.accessibility.NodeSnapshot
import com.pricelens.accessibility.PriceBasis
import com.pricelens.accessibility.ShopPlatform
import com.pricelens.accessibility.container
import com.pricelens.accessibility.extractItemId
import com.pricelens.accessibility.extractPriceHit
import com.pricelens.accessibility.extractTitle
import com.pricelens.accessibility.isProductPage
import com.pricelens.accessibility.leaf
import com.pricelens.accessibility.loadRealDump
import com.pricelens.rules.DetectionPipeline.DetectionOutcome
import com.pricelens.rules.DetectionPipeline.DetectionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 判定管线（规则优先 + 启发式回落）对**真机实采夹具**的行为钉子。
 *
 * 三组证明：
 *  1. **无回归**：空规则集下，新管线与改造前 `PriceMonitorService` 的三条出口
 *     （非商详收窗 / 商详无价 / 命中）逐值一致 —— 用同一份真机 dump 跑"旧路径镜像函数"
 *     与 [DetectionPipeline]，断言结论相同（不是"看起来一样"，是逐字段相等）；
 *  2. **规则真的在真机上命中**：内置 JD 规则在两棵真机商详页（现货/预约）上都命中，
 *     且价格/标题与启发式逐值一致（规则优先不等于结果变差）；
 *  3. **规则不上首页/搜索页**：内置规则在两页上都不确认，落回启发式后结论与改造前一致
 *     （首页/搜索页不弹窗）—— "规则没命中就什么都不显示"的反面也要钉住。
 */
class DetectionPipelineTest {

    private val home = loadRealDump(HOME_DUMP)
    private val search = loadRealDump(SEARCH_DUMP)
    private val instock = loadRealDump(INSTOCK_DUMP)
    private val presale = loadRealDump(PRESALE_DUMP)

    private val builtinRules: RuleSet get() = RuleSet(listOf(loadedBuiltinJdRule()))

    // ---------- 改造前的服务路径镜像（PriceMonitorService v2.8.0 的三条出口） ----------

    private sealed interface OldOutcome {
        data object NotProductPage : OldOutcome
        data object NoPrice : OldOutcome
        data class Hit(
            val priceValue: Double,
            val rawText: String,
            val basis: PriceBasis,
            val title: String?,
            val itemId: String?
        ) : OldOutcome
    }

    private fun oldPath(root: NodeSnapshot, platform: ShopPlatform): OldOutcome {
        if (!isProductPage(root, platform)) return OldOutcome.NotProductPage
        val priceHit = extractPriceHit(root, platform) ?: return OldOutcome.NoPrice
        val titleHit = extractTitle(root, platform)
        val itemId = extractItemId(root)
        return OldOutcome.Hit(priceHit.value, priceHit.rawText, priceHit.basis, titleHit?.text, itemId)
    }

    private fun mapOutcome(outcome: DetectionOutcome): OldOutcome = when (outcome) {
        is DetectionOutcome.NotProductPage -> OldOutcome.NotProductPage
        is DetectionOutcome.NoPrice -> OldOutcome.NoPrice
        is DetectionOutcome.Hit -> {
            val detection = outcome.detection
            OldOutcome.Hit(
                detection.price.value,
                detection.price.rawText,
                detection.price.basis,
                detection.title,
                detection.itemId
            )
        }
    }

    // ---------- 1. 无回归 ----------

    @Test
    fun `empty rule set reproduces the old heuristic outcome on every real fixture`() {
        for (dump in listOf(home, search, instock, presale)) {
            val old = oldPath(dump.root, ShopPlatform.JD)
            val mapped = mapOutcome(DetectionPipeline.detect(dump.root, ShopPlatform.JD, JD_PKG, RuleSet.EMPTY))
            println("[parity] ${dump.name} old=$old new=$mapped")
            assertEquals("${dump.name}: 空规则集下新管线必须与改造前逐值一致", old, mapped)
        }
    }

    @Test
    fun `builtin rules keep the real home and search pages closed like before`() {
        for (dump in listOf(home, search)) {
            val outcome = DetectionPipeline.detect(dump.root, ShopPlatform.JD, JD_PKG, builtinRules)
            assertEquals(
                "${dump.name}: 规则在非商详页上必须落回启发式，且结论与改造前一致（不弹窗）",
                oldPath(dump.root, ShopPlatform.JD),
                mapOutcome(outcome)
            )
            assertEquals("${dump.name}: 非商详页不可出现命中", DetectionOutcome.NotProductPage, outcome)
        }
    }

    // ---------- 2. 规则在真机上命中 ----------

    @Test
    fun `builtin jd rules hit both real detail fixtures with exact expected values`() {
        val instockOutcome = DetectionPipeline.detect(instock.root, ShopPlatform.JD, JD_PKG, builtinRules)
        assertTrue("现货商详页必须命中：$instockOutcome", instockOutcome is DetectionOutcome.Hit)
        val instockDetection = (instockOutcome as DetectionOutcome.Hit).detection
        assertEquals(DetectionSource.RULE, instockDetection.source)
        assertEquals("¥1838」+「.9」分体小数位必须拼回", 1838.9, instockDetection.price.value, 0.0001)
        assertEquals("¥1838.9", instockDetection.price.rawText)
        assertEquals(PriceBasis.PAGE, instockDetection.price.basis)
        assertTrue(instockDetection.title!!.contains("飞天") && instockDetection.title!!.contains("500ml"))

        val presaleOutcome = DetectionPipeline.detect(presale.root, ShopPlatform.JD, JD_PKG, builtinRules)
        assertTrue("预约商详页必须命中：$presaleOutcome", presaleOutcome is DetectionOutcome.Hit)
        val presaleDetection = (presaleOutcome as DetectionOutcome.Hit).detection
        assertEquals(DetectionSource.RULE, presaleDetection.source)
        assertEquals(1759.0, presaleDetection.price.value, 0.0001)
        assertEquals("¥1759", presaleDetection.price.rawText)
        assertTrue(presaleDetection.title!!.contains("飞天") && presaleDetection.title!!.contains("500ml"))
    }

    @Test
    fun `rule hit values equal the heuristic values on the same fixtures`() {
        for (dump in listOf(instock, presale)) {
            val old = oldPath(dump.root, ShopPlatform.JD) as OldOutcome.Hit
            val outcome = DetectionPipeline.detect(dump.root, ShopPlatform.JD, JD_PKG, builtinRules)
            val detection = (outcome as DetectionOutcome.Hit).detection
            assertEquals(DetectionSource.RULE, detection.source)
            assertEquals("${dump.name}: 规则价必须与启发式逐值一致", old.priceValue, detection.price.value, 0.0001)
            assertEquals("${dump.name}: 规则原文必须与启发式一致", old.rawText, detection.price.rawText)
            assertEquals("${dump.name}: 规则标题必须与启发式一致", old.title, detection.title)
            assertEquals("${dump.name}: itemId 走同一条提取（两路都不依赖它）", old.itemId, detection.itemId)
        }
    }

    @Test
    fun `matchedBy names the rule id version and selector`() {
        val outcome = DetectionPipeline.detect(instock.root, ShopPlatform.JD, JD_PKG, builtinRules)
        val matchedBy = (outcome as DetectionOutcome.Hit).detection.matchedBy
        assertTrue("日志必须写明是规则命中：$matchedBy", matchedBy.startsWith("规则 jd@v1/product_detail"))
        assertTrue("日志必须写明字段命中来源：$matchedBy", matchedBy.contains("price=textRegex:"))
        assertTrue(matchedBy.contains("title=textRegex:"))
    }

    // ---------- 3. 规则优先 / 回落 ----------

    private fun couponFirstTree(): NodeSnapshot = container(
        kids = arrayOf(
            // 启发式 BFS 命中的第一个 ¥ 文本（券文案，不是主价）
            leaf(text = "限时领券 立减 ¥500"),
            leaf(text = "Redmi K80 Pro 旗舰手机 12GB+512GB 晴雪白"),
            // 规则精确命中的主价
            leaf(text = "¥3999"),
            leaf(text = "立即购买", clickable = true)
        )
    )

    private val preciseRule: RuleSet = RuleSet(
        listOf(
            (
                RuleJson.parsePlatformRule(
                    """
                    {
                      "schemaVersion": 1, "id": "jd", "version": 77, "packages": ["com.jingdong"],
                      "pages": [ {
                        "extract": {
                          "title": [ { "by": "textRegex", "value": "^Redmi.{6,}${'$'}" } ],
                          "price": [ { "by": "text", "value": "¥3999" } ],
                          "buyNow": [ { "by": "text", "value": "立即购买" } ]
                        },
                        "confirm": { "allOf": ["title", "price", "buyNow"] }
                      } ]
                    }
                    """.trimIndent()
                ) as PlatformRuleResult.Valid
                ).rule
        )
    )

    @Test
    fun `rule wins when both paths hit with different prices`() {
        val tree = couponFirstTree()
        val heuristic = mapOutcome(DetectionPipeline.detect(tree, ShopPlatform.JD, JD_PKG, RuleSet.EMPTY))
        assertEquals(
            "改造前这条树读出的是券文案里的 ¥500（启发式取 BFS 第一个带 ¥ 的文本）",
            OldOutcome.Hit(500.0, "限时领券 立减 ¥500", PriceBasis.PAGE, REDMI_TITLE, null),
            heuristic
        )

        val outcome = DetectionPipeline.detect(tree, ShopPlatform.JD, JD_PKG, preciseRule)
        val detection = (outcome as DetectionOutcome.Hit).detection
        assertEquals("两条路都命中时以规则为准", DetectionSource.RULE, detection.source)
        assertEquals(3999.0, detection.price.value, 0.0001)
        assertEquals(REDMI_TITLE, detection.title)
    }

    private fun freeGiftTree(): NodeSnapshot = container(
        kids = arrayOf(
            leaf(text = REDMI_TITLE),
            leaf(text = "免费"),
            leaf(text = "¥1234"),
            leaf(text = "立即购买", clickable = true)
        )
    )

    @Test
    fun `rule confirmed but unparseable price falls back instead of going silent`() {
        val ruleWithBadPrice = (
            RuleJson.parsePlatformRule(
                """
                {
                  "schemaVersion": 1, "id": "jd", "version": 5, "packages": ["com.jingdong"],
                  "pages": [ {
                    "extract": {
                      "title": [ { "by": "textRegex", "value": "^Redmi.{6,}${'$'}" } ],
                      "price": [ { "by": "text", "value": "免费" } ],
                      "buyNow": [ { "by": "text", "value": "立即购买" } ]
                    },
                    "confirm": { "allOf": ["title", "price", "buyNow"] }
                  } ]
                }
                """.trimIndent()
            ) as PlatformRuleResult.Valid
            ).rule
        val outcome = DetectionPipeline.detect(freeGiftTree(), ShopPlatform.JD, JD_PKG, RuleSet(listOf(ruleWithBadPrice)))
        val detection = (outcome as DetectionOutcome.Hit).detection
        assertEquals("规则确认了但价格解析不出来 → 回落启发式，不是什么都不显示", DetectionSource.HEURISTIC, detection.source)
        assertEquals(1234.0, detection.price.value, 0.0001)
    }

    @Test
    fun `fallback log reason distinguishes no-rules and rules-missed`() {
        val withRules = DetectionPipeline.detect(instock.root, ShopPlatform.JD, JD_PKG, builtinRules, activityName = MAIN_FRAME)
        val fallback = (withRules as DetectionOutcome.Hit).detection
        assertEquals("Activity 不匹配 → 规则页被跳过 → 回落启发式（结果与改造前一致）", DetectionSource.HEURISTIC, fallback.source)
        assertTrue("必须说清是规则未命中：${fallback.matchedBy}", fallback.matchedBy.contains("规则未命中"))

        val withoutRules = DetectionPipeline.detect(instock.root, ShopPlatform.JD, JD_PKG, RuleSet.EMPTY)
        val noRules = (withoutRules as DetectionOutcome.Hit).detection
        assertTrue("必须说清是无规则：${noRules.matchedBy}", noRules.matchedBy.contains("无规则"))
    }

    @Test
    fun `activity name selects the rule page on window switch events`() {
        val outcome = DetectionPipeline.detect(
            instock.root,
            ShopPlatform.JD,
            JD_PKG,
            builtinRules,
            activityName = "com.jd.lib.productdetail.ProductDetailActivity"
        )
        assertEquals(DetectionSource.RULE, (outcome as DetectionOutcome.Hit).detection.source)
    }

    private companion object {
        const val JD_PKG = "com.jingdong.app.mall"
        const val MAIN_FRAME = "com.jingdong.app.mall.MainFrameActivity"
        const val REDMI_TITLE = "Redmi K80 Pro 旗舰手机 12GB+512GB 晴雪白"
        const val HOME_DUMP = "jd_home_20260929.xml"
        const val SEARCH_DUMP = "jd_search_20260929.xml"
        const val INSTOCK_DUMP = "jd_detail_instock_20260929.xml"
        const val PRESALE_DUMP = "jd_detail_presale_20260929.xml"
    }
}
