package com.pricelens.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则 JSON 严格解析：坏规则一律拒绝、不崩、不猜（坏内容宁可不要 —— 留在旧规则上）。
 * 这里的每一条拒绝都对应 [RuleJson] 注释里的一个理由码。
 */
class RuleJsonTest {

    private val baseRule = """
        {
          "schemaVersion": 1,
          "id": "jd",
          "version": 3,
          "packages": ["com.jingdong"],
          "pages": [
            {
              "name": "product_detail",
              "activityRegex": ".*ProductDetail.*",
              "extract": {
                "title": [ { "by": "textRegex", "value": "^[^¥￥]{10,80}${'$'}" } ],
                "price": [ { "by": "textRegex", "value": "[¥￥]\\s*\\d[\\d,]*(?:\\.\\d{1,2})?" } ],
                "buyNow": [ { "by": "text", "value": "立即购买" } ]
              },
              "confirm": { "allOf": ["title", "price", "buyNow"] }
            }
          ]
        }
    """.trimIndent()

    private fun parseRule(raw: String): PlatformRuleResult = RuleJson.parsePlatformRule(raw, expectedId = null)

    private fun rejectReason(raw: String): String {
        val parsed = parseRule(raw)
        assertTrue("本应被拒绝但解析通过了：$raw", parsed is PlatformRuleResult.Rejected)
        return (parsed as PlatformRuleResult.Rejected).reason
    }

    @Test
    fun `platform rule parses with selectors and confirm`() {
        val parsed = parseRule(baseRule)
        assertTrue(parsed is PlatformRuleResult.Valid)
        val rule = (parsed as PlatformRuleResult.Valid).rule
        assertEquals("jd", rule.id)
        assertEquals(3, rule.version)
        assertEquals(listOf("com.jingdong"), rule.packages)
        assertEquals(1, rule.pages.size)
        val page = rule.pages[0]
        assertEquals("product_detail", page.name)
        assertEquals(".*ProductDetail.*", page.activityRegex)
        assertEquals(3, page.extract.size)
        assertEquals(SelectorBy.TEXT, page.extract.getValue("buyNow")[0].by)
        assertEquals(ConfirmRule(allOf = listOf("title", "price", "buyNow")), page.confirm)
    }

    @Test
    fun `capture group within range parses and is preserved`() {
        val raw = """
            {
              "schemaVersion": 1, "id": "jd", "version": 1, "packages": ["com.jingdong"],
              "pages": [ {
                "extract": { "title": [ { "by": "descRegex", "value": "商品名称[：:]\\s*(.+)", "group": 1 } ] },
                "confirm": { "allOf": ["title"] }
              } ]
            }
        """.trimIndent()
        val rule = (parseRule(raw) as PlatformRuleResult.Valid).rule
        assertEquals(1, rule.pages[0].extract.getValue("title")[0].group)
    }

    @Test
    fun `manifest parses entries and normalizes sha256 to lowercase`() {
        val sha = "AB".repeat(32)
        val raw = """
            {
              "schemaVersion": 1, "manifestVersion": 2026100201,
              "rules": [ { "id": "jd", "file": "rules/jd.json", "version": 4, "sha256": "$sha" } ]
            }
        """.trimIndent()
        val parsed = RuleJson.parseManifest(raw)
        assertTrue(parsed is ManifestResult.Valid)
        val manifest = (parsed as ManifestResult.Valid).manifest
        assertEquals(2026100201L, manifest.manifestVersion)
        val entry = manifest.entry("jd")
        assertEquals("rules/jd.json", entry?.file)
        assertEquals(4, entry?.version)
        assertEquals(sha.lowercase(), entry?.sha256)
    }

    @Test
    fun `manifest rejects malformed input without crashing`() {
        assertTrue(RuleJson.parseManifest("") is ManifestResult.Rejected)
        assertTrue(RuleJson.parseManifest("not json") is ManifestResult.Rejected)
        assertTrue(rejectManifest(manifest(schema = "2")).contains(RejectReason.UNKNOWN_SCHEMA))
        assertTrue(rejectManifest(manifest(manifestVersion = "0")).contains(RejectReason.MISSING_FIELDS))
        assertTrue(rejectManifest(manifest(rulesJson = "[]")).contains(RejectReason.BAD_RULES))
        assertTrue(rejectManifest(manifest(id = "JD")).contains(RejectReason.BAD_RULES))
        assertTrue(rejectManifest(manifest(file = "../rules/jd.json")).contains(RejectReason.BAD_RULES))
        assertTrue(rejectManifest(manifest(sha = "a".repeat(63))).contains(RejectReason.BAD_SHA256))
        assertTrue(rejectManifest(manifest(sha = "z".repeat(64))).contains(RejectReason.BAD_SHA256))
        val dup = """
            { "schemaVersion": 1, "manifestVersion": 1, "rules": [
              { "id": "jd", "file": "rules/jd.json", "version": 1, "sha256": "${"0".repeat(64)}" },
              { "id": "jd", "file": "rules/jd.json", "version": 1, "sha256": "${"0".repeat(64)}" } ] }
        """.trimIndent()
        assertTrue(rejectManifest(dup).contains(RejectReason.BAD_RULES))
    }

    private fun manifest(
        schema: String = "1",
        manifestVersion: String = "1",
        id: String = "jd",
        file: String = "rules/jd.json",
        sha: String = "0".repeat(64),
        rulesJson: String = ""
    ): String {
        val rules = rulesJson.ifEmpty {
            """[ { "id": "$id", "file": "$file", "version": 1, "sha256": "$sha" } ]"""
        }
        return """{ "schemaVersion": $schema, "manifestVersion": $manifestVersion, "rules": $rules }"""
    }

    private fun rejectManifest(raw: String): String {
        val parsed = RuleJson.parseManifest(raw)
        assertTrue("本应被拒绝：$raw", parsed is ManifestResult.Rejected)
        return (parsed as ManifestResult.Rejected).reason
    }

    @Test
    fun `platform rule rejects bad selectors`() {
        assertTrue(selectorReason("""{ "by": "xpath", "value": "//x" }""").contains("unknownBy"))
        assertTrue(selectorReason("""{ "by": "text" }""").contains("value.missing"))
        assertTrue(selectorReason("""{ "by": "textRegex", "value": "((" }""").contains("regex.invalid"))
        // 捕获组号超过 pattern 的 groupCount：解析期就拒绝，不让它在运行时静默不命中
        assertTrue(selectorReason("""{ "by": "textRegex", "value": "(a)", "group": 2 }""").contains("group.outOfRange"))
        // 非正则选择器不允许 group（写错就红，不猜意图）
        assertTrue(selectorReason("""{ "by": "text", "value": "x", "group": 1 }""").contains("group.onlyForRegex"))
    }

    private fun selectorReason(selectorJson: String): String = rejectReason(
        """
        {
          "schemaVersion": 1, "id": "jd", "version": 1, "packages": ["com.jingdong"],
          "pages": [ {
            "extract": { "title": [ $selectorJson ] },
            "confirm": { "allOf": ["title"] }
          } ]
        }
        """.trimIndent()
    )

    @Test
    fun `platform rule rejects confirm problems`() {
        assertTrue(confirmReason("""{ "allOf": ["missing"] }""").contains("confirm.unknownField"))
        assertTrue(confirmReason("""{ "noneOf": ["title"] }""").contains("confirm.noPositiveCondition"))
        assertTrue(confirmReason("""{ "allOf": ["title"], "noneOf": ["title"] }""").contains("confirm.noneOfConflicts"))
    }

    private fun confirmReason(confirmJson: String): String = rejectReason(
        """
        {
          "schemaVersion": 1, "id": "jd", "version": 1, "packages": ["com.jingdong"],
          "pages": [ {
            "extract": { "title": [ { "by": "text", "value": "x" } ] },
            "confirm": $confirmJson
          } ]
        }
        """.trimIndent()
    )

    @Test
    fun `platform rule rejects dangerous packages and bad structure`() {
        assertTrue(rejectReason(ruleWith(packagesJson = "[]")).contains(RejectReason.BAD_PACKAGES))
        assertTrue(rejectReason(ruleWith(packagesJson = "[\"\"]")).contains(RejectReason.BAD_PACKAGES))
        assertTrue("空前缀 startsWith 会覆盖所有宿主，必须拒绝", rejectReason(ruleWith(packagesJson = "[\"c\"]")).contains(RejectReason.BAD_PACKAGES))
        assertTrue(rejectReason(ruleWith(pagesJson = "[]")).contains(RejectReason.BAD_PAGES))
        assertTrue(rejectReason(ruleWith(schema = "9")).contains(RejectReason.UNKNOWN_SCHEMA))
        assertTrue(rejectReason(ruleWith(id = "JD")).contains(RejectReason.MISSING_FIELDS))
        assertTrue(rejectReason("garbage").contains(RejectReason.JSON_UNPARSEABLE))
    }

    private fun ruleWith(
        schema: String = "1",
        id: String = "jd",
        packagesJson: String = "[\"com.jingdong\"]",
        pagesJson: String = """[ {
            "extract": { "title": [ { "by": "text", "value": "x" } ] },
            "confirm": { "allOf": ["title"] }
        } ]"""
    ): String = """
        { "schemaVersion": $schema, "id": "$id", "version": 1, "packages": $packagesJson, "pages": $pagesJson }
    """.trimIndent()

    @Test
    fun `expected id mismatch is rejected`() {
        val parsed = RuleJson.parsePlatformRule(baseRule, expectedId = "taobao")
        assertTrue(parsed is PlatformRuleResult.Rejected)
        assertTrue((parsed as PlatformRuleResult.Rejected).reason.contains(RejectReason.ID_MISMATCH))
    }
}
