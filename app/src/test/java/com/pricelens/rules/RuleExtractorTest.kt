package com.pricelens.rules

import com.pricelens.accessibility.container
import com.pricelens.accessibility.leaf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提取引擎：五种选择器各至少一例 + 捕获组 + 树序/链序 + confirm 组合 + 坏输入不崩。
 * 全部是构造树（结构参照真机 dump 的脱敏改写），纯 JVM 跑。
 */
class RuleExtractorTest {

    private fun rule(pagesJson: String): PlatformRule {
        val raw = """
            {
              "schemaVersion": 1, "id": "jd", "version": 7, "packages": ["com.jingdong"],
              "pages": $pagesJson
            }
        """.trimIndent()
        val parsed = RuleJson.parsePlatformRule(raw)
        assertTrue("测试规则必须可解析：$parsed", parsed is PlatformRuleResult.Valid)
        return (parsed as PlatformRuleResult.Valid).rule
    }

    private fun singlePageRule(extractJson: String, confirmJson: String, activityRegex: String? = null): PlatformRule {
        val activity = if (activityRegex == null) "" else """"activityRegex": "$activityRegex","""
        return rule(
            """
            [ {
              $activity
              "extract": $extractJson,
              "confirm": $confirmJson
            } ]
            """.trimIndent()
        )
    }

    // ---------- 五种选择器 ----------

    @Test
    fun `text selector matches cleaned exact text (zero width padding ignored)`() {
        // 真机京东用 U+200B 填充文案防爬：不清洗会"规则明明写了还是不命中"（既有教训）
        val root = container(kids = arrayOf(leaf(text = "立即\u200B购买"), leaf(text = "立即购买x")))
        val rule = singlePageRule(
            """{ "hint": [ { "by": "text", "value": "立即购买" } ] }""",
            """{ "allOf": ["hint"] }"""
        )
        val result = RuleExtractor.extract(root, rule)
        assertNotNull(result)
        assertEquals("立即购买", result!!.fields.getValue("hint").value)
    }

    @Test
    fun `textRegex supports capture groups and rejects empty group match`() {
        // 捕获组：从"券后1299.5元"里取数字
        val root = container(kids = arrayOf(leaf(text = "券后1299.50元 限时")))
        val rule = singlePageRule(
            """{ "price": [ { "by": "textRegex", "value": "(?:券后)(\\d+(?:\\.\\d+)?)", "group": 1 } ] }""",
            """{ "allOf": ["price"] }"""
        )
        val hit = RuleExtractor.extract(root, rule)?.fields?.getValue("price")
        assertEquals("1299.50", hit?.value)
        assertEquals("券后1299.50元 限时", hit?.nodeText)

        // 可选组未匹配（空）→ 该节点不算命中，继续扫下一个节点，而不是拿空串冒充字段
        val twoNodes = container(
            kids = arrayOf(leaf(text = "¥100"), leaf(text = "¥100.50"))
        )
        val optional = singlePageRule(
            """{ "price": [ { "by": "textRegex", "value": "(?:¥)(\\d+)(?:\\.(\\d{1,2}))?", "group": 2 } ] }""",
            """{ "allOf": ["price"] }"""
        )
        assertEquals("50", RuleExtractor.extract(twoNodes, optional)?.fields?.getValue("price")?.value)
    }

    @Test
    fun `textRegex without group returns the whole match`() {
        val root = container(kids = arrayOf(leaf(text = "秒杀价 ¥1838.9 起")))
        val rule = singlePageRule(
            """{ "price": [ { "by": "textRegex", "value": "[¥￥]\\s*\\d[\\d,]*(?:\\.\\d{1,2})?" } ] }""",
            """{ "allOf": ["price"] }"""
        )
        assertEquals("¥1838.9", RuleExtractor.extract(root, rule)?.fields?.getValue("price")?.value)
    }

    @Test
    fun `desc selector and descRegex read contentDescription`() {
        val root = container(
            kids = arrayOf(
                leaf(text = "搜一搜", desc = "搜索栏"),
                leaf(desc = "商品名称：小米 15 Ultra 16GB+512GB 白色")
            )
        )
        val desc = singlePageRule(
            """{ "entry": [ { "by": "desc", "value": "搜索栏" } ] }""",
            """{ "allOf": ["entry"] }"""
        )
        assertEquals("搜索栏", RuleExtractor.extract(root, desc)?.fields?.getValue("entry")?.value)

        val descRegex = singlePageRule(
            """{ "title": [ { "by": "descRegex", "value": "商品名称[：:]\\s*(.+)", "group": 1 } ] }""",
            """{ "allOf": ["title"] }"""
        )
        assertEquals(
            "小米 15 Ultra 16GB+512GB 白色",
            RuleExtractor.extract(root, descRegex)?.fields?.getValue("title")?.value
        )
    }

    @Test
    fun `viewId matches full resource name and bare name suffix`() {
        val full = container(kids = arrayOf(leaf(text = "¥123", res = "com.jingdong.app.mall:id/jd_price")))
        val byFull = singlePageRule(
            """{ "price": [ { "by": "viewId", "value": "com.jingdong.app.mall:id/jd_price" } ] }""",
            """{ "allOf": ["price"] }"""
        )
        assertEquals("¥123", RuleExtractor.extract(full, byFull)?.fields?.getValue("price")?.value)

        // 只写 `:id/` 后的名字段：与 PriceNodeMatcher 的"名后缀"策略同源（跨包壳改版存活）
        val byBare = singlePageRule(
            """{ "price": [ { "by": "viewId", "value": "jd_price" } ] }""",
            """{ "allOf": ["price"] }"""
        )
        assertEquals("¥123", RuleExtractor.extract(full, byBare)?.fields?.getValue("price")?.value)
    }

    // ---------- 遍历顺序与 fallback 链 ----------

    @Test
    fun `selector chain order wins over tree order`() {
        val root = container(kids = arrayOf(leaf(text = "甲商品"), leaf(text = "乙商品")))
        // 第一个选择器先在整个树里找：乙命中，就取乙（不会因为甲在树序更前而改用甲）
        val preferSecond = singlePageRule(
            """{ "title": [ { "by": "text", "value": "乙商品" }, { "by": "text", "value": "甲商品" } ] }""",
            """{ "allOf": ["title"] }"""
        )
        assertEquals("乙商品", RuleExtractor.extract(root, preferSecond)?.fields?.getValue("title")?.value)
        // 链序反过来则取甲 —— 证明"按链序 fallback"而不是"取任意命中"
        val preferFirst = singlePageRule(
            """{ "title": [ { "by": "text", "value": "甲商品" }, { "by": "text", "value": "乙商品" } ] }""",
            """{ "allOf": ["title"] }"""
        )
        assertEquals("甲商品", RuleExtractor.extract(root, preferFirst)?.fields?.getValue("title")?.value)
    }

    @Test
    fun `within one selector the first node in bfs order wins`() {
        val root = container(kids = arrayOf(leaf(text = "¥100"), leaf(text = "¥200")))
        val rule = singlePageRule(
            """{ "price": [ { "by": "textRegex", "value": "(?:¥)(\\d+)" } ] }""",
            """{ "allOf": ["price"] }"""
        )
        assertEquals("¥100", RuleExtractor.extract(root, rule)?.fields?.getValue("price")?.value)
    }

    // ---------- confirm ----------

    @Test
    fun `confirm allOf anyOf noneOf combine`() {
        val root = container(kids = arrayOf(leaf(text = "标题商品名"), leaf(text = "¥99"), leaf(text = "去结算")))
        val anyOf = singlePageRule(
            """{ "title": [ { "by": "text", "value": "标题商品名" } ],
                "price": [ { "by": "text", "value": "¥99" } ],
                "checkout": [ { "by": "text", "value": "去结算" } ] }""",
            """{ "allOf": ["title"], "anyOf": ["price", "checkout"] }"""
        )
        assertNotNull("anyOf 命中其一即可", RuleExtractor.extract(root, anyOf))

        val missing = singlePageRule(
            """{ "title": [ { "by": "text", "value": "标题商品名" } ],
                "price": [ { "by": "text", "value": "¥199" } ] }""",
            """{ "allOf": ["title", "price"] }"""
        )
        assertNull("allOf 缺 price 必须整体不确认", RuleExtractor.extract(root, missing))

        val veto = singlePageRule(
            """{ "title": [ { "by": "text", "value": "标题商品名" } ],
                "checkout": [ { "by": "text", "value": "去结算" } ] }""",
            """{ "allOf": ["title"], "noneOf": ["checkout"] }"""
        )
        assertNull("noneOf 命中一票否决（购物车/确认订单页）", RuleExtractor.extract(root, veto))
    }

    @Test
    fun `activity regex filters pages and null activity name passes`() {
        val root = container(kids = arrayOf(leaf(text = "标题商品名")))
        val rulesJson = """
            [ {
              "activityRegex": ".*ProductDetail.*",
              "extract": { "title": [ { "by": "text", "value": "标题商品名" } ] },
              "confirm": { "allOf": ["title"] }
            } ]
        """.trimIndent()
        val ruleSet = rule(rulesJson)
        assertNotNull(RuleExtractor.extract(root, ruleSet, "com.jd.lib.productdetail.ProductDetailActivity"))
        assertNull(RuleExtractor.extract(root, ruleSet, "com.jingdong.app.mall.MainFrameActivity"))
        val noActivity = RuleExtractor.extract(root, ruleSet, null)
        assertNotNull("拿不到 Activity 名（内容变化事件）必须放行，否则规则会被静默跳过", noActivity)
    }

    // ---------- 坏输入不崩 ----------

    @Test
    fun `empty tree and null texts never crash and never hit`() {
        val empty = container()
        val rule = singlePageRule(
            """{ "title": [ { "by": "textRegex", "value": ".*" } ] }""",
            """{ "allOf": ["title"] }"""
        )
        assertNull(RuleExtractor.extract(empty, rule))
        val nulls = container(kids = arrayOf(leaf(), leaf(desc = " "), leaf(text = "")))
        assertNull(RuleExtractor.extract(nulls, rule))
        val blankOnly = singlePageRule(
            """{ "title": [ { "by": "textRegex", "value": "^\\s*${'$'}" } ] }""",
            """{ "allOf": ["title"] }"""
        )
        assertNull("清洗后为空文本不参与匹配", RuleExtractor.extract(nulls, blankOnly))
    }
}
