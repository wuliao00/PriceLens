package com.pricelens.rules

import com.pricelens.accessibility.ShopPlatform
import com.pricelens.accessibility.loadRealDump
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 出厂规则（assets/rules/jd.json）与仓库侧规则源（rules/jd.json + manifest.json）的一致性闸门。
 *
 * 这些文件是"离线可用"和"远端同步"两条链路的共同源头，任何一侧被手改坏，
 * 都必须在这里先红 —— 而不是等真机上浮窗不弹或内容不对才发现。
 * 依赖约定：单测工作目录是 app/ 模块目录（同 DatabaseMigrationWiringTest）。
 */
class BuiltinRuleTest {

    private val assetBytes: ByteArray get() = readRequiredFile(assetJdRuleFile)
    private val repoBytes: ByteArray get() = readRequiredFile(repoJdRuleFile)

    @Test
    fun `assets copy and repo rules copy are byte identical`() {
        assertTrue(
            "assets 快照与 rules/ 源文件不一致 —— 运行 tools/gen_rules_manifest.py 重新同步（单一事实源在 rules/）",
            assetBytes.contentEquals(repoBytes)
        )
    }

    @Test
    fun `manifest sha256 matches the shipped rule file`() {
        val manifestRaw = readRequiredFile(repoManifestFile).toString(Charsets.UTF_8)
        val parsed = RuleJson.parseManifest(manifestRaw)
        assertTrue("rules/manifest.json 必须可解析：$parsed", parsed is ManifestResult.Valid)
        val manifest = (parsed as ManifestResult.Valid).manifest
        val entry = manifest.entry("jd")
        assertNotNull("清单里必须有 jd 条目", entry)
        assertEquals("rules/jd.json", entry!!.file)
        assertEquals(
            "清单里的 sha256 必须等于规则文件实际摘要 —— 运行 tools/gen_rules_manifest.py 重算",
            RuleIntegrity.sha256Hex(assetBytes),
            entry.sha256
        )
        assertTrue(entry.version >= 1)
        assertTrue(manifest.manifestVersion >= 2026010100L)
    }

    @Test
    fun `builtin rule parses and declares jd package prefix`() {
        val rule = loadedBuiltinJdRule()
        assertEquals("jd", rule.id)
        // 2 = 2026-10-03 把国补底栏文案「领取补贴购买」收进 buyNow 正则时由
        // tools/gen_rules_manifest.py 自动递增的（改内容不改版本号 = 这条会红，
        // 逼着一起重算 sha256，见下面两条一致性闸门）
        assertEquals(2, rule.version)
        assertEquals(listOf("com.jingdong"), rule.packages)
        assertTrue(rule.covers("com.jingdong.app.mall"))
        assertEquals(1, rule.pages.size)
        assertEquals("product_detail", rule.pages[0].name)
    }

    @Test
    fun `builtin rule hits both real detail fixtures at engine level`() {
        val rule = loadedBuiltinJdRule()
        val instock = loadRealDump("jd_detail_instock_20260929.xml")
        val presale = loadRealDump("jd_detail_presale_20260929.xml")

        val instockHit = RuleExtractor.extract(instock.root, rule)
        assertNotNull("现货真机树必须命中", instockHit)
        assertTrue(instockHit!!.title!!.contains("飞天"))
        assertEquals("¥1838", instockHit.priceText)

        val presaleHit = RuleExtractor.extract(presale.root, rule)
        assertNotNull("预约真机树必须命中", presaleHit)
        assertTrue(presaleHit!!.title!!.contains("飞天"))
        assertEquals("¥1759", presaleHit.priceText)
    }

    @Test
    fun `builtin rule never confirms on the real home or search pages`() {
        val rule = loadedBuiltinJdRule()
        assertNull(
            "首页信息流满屏 ¥ 与长文本，规则必须靠 buyNow/checkout 条件把它挡住",
            RuleExtractor.extract(loadRealDump("jd_home_20260929.xml").root, rule)
        )
        assertNull(
            "搜索页不得命中",
            RuleExtractor.extract(loadRealDump("jd_search_20260929.xml").root, rule)
        )
    }

    @Test
    fun `builtin rule packages only cover jd`() {
        val rule = loadedBuiltinJdRule()
        assertTrue(rule.covers("com.jingdong.app.mall"))
        assertFalse("规则只覆盖京东，不能误配到淘宝", rule.covers("com.taobao.taobao"))
        assertFalse("规则只覆盖京东，不能误配到拼多多", rule.covers("com.xunmeng.pinduoduo"))
        assertEquals(ShopPlatform.JD, ShopPlatform.fromPackage("com.jingdong.app.mall"))
    }
}
