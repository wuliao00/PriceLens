package com.pricelens.coupon

import com.pricelens.coupon.model.CouponScope
import com.pricelens.coupon.model.CouponSlot
import com.pricelens.coupon.model.CouponState
import com.pricelens.coupon.model.ExtractSource
import com.pricelens.coupon.model.Extraction
import com.pricelens.coupon.model.ExtractorKind
import com.pricelens.coupon.model.PriceSlots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * B2 交付二的判据：这份导出**必须**回灌得进评测集，格式不一致就白做。
 *
 * 固定表照抄 `tools/eval_coupons.py` 的解析判据（不跑 Python，测试表即口径）：
 *  - 条目：id / source / coupons 三键必需，source 只认三值；raw / price / user_mark /
 *    origin / note 允许出现（`load_jsonl` 不拒绝未知键，但 eval 只消费这些）；
 *  - 券条目：**齐且仅齐** discount / threshold / scope / state / expiry / url 六键，
 *    多一键（比如把 code 塞进去）或少一键 eval 都直接判数据错误；
 *  - 面额/门槛只收数字或 null（字符串数字会被拒），0.0 与 null 是两件事。
 * 逐字段解析回来断言，而不是比字符串——字符串相等不代表 eval 读得懂。
 */
class MisreadExportTest {

    /** 照 `tools/eval_coupons.py` 的 SOURCES / COUPON_KEYS / PRICE_KEYS 与 parse_entry 的必需键 */
    private val evalSources = setOf("page_node", "clipboard", "community")
    private val evalCouponKeys = setOf("discount", "threshold", "scope", "state", "expiry", "url")
    private val evalPriceKeys = setOf("final", "list", "drop")
    private val evalEntryRequired = setOf("id", "source", "coupons")
    private val evalEntryAllowed = setOf("id", "source", "coupons", "raw", "price", "user_mark", "origin", "note")

    @JvmField
    @Rule
    val tmp = TemporaryFolder()

    private fun slot(
        discount: Double? = null,
        threshold: Double? = null,
        scope: CouponScope = CouponScope.UNKNOWN,
        state: CouponState = CouponState.UNKNOWN,
        expiry: String? = null,
        code: String? = null,
        url: String? = null,
        sourceText: String = "领券满4999减300",
        nodePath: List<Int> = emptyList()
    ) = CouponSlot(discount, threshold, scope, state, expiry, code, url, sourceText, nodePath)

    private fun extraction(
        coupons: List<CouponSlot>,
        source: ExtractSource = ExtractSource.PAGE_NODE,
        confidence: Double = 0.9
    ): Extraction {
        return Extraction(
            source = source,
            extractor = ExtractorKind.RULE,
            platform = "jd",
            itemRef = "100001",
            coupons = coupons,
            price = PriceSlots(5499.0, 5999.0, null),
            stackNote = null,
            confidence = confidence
        )
    }

    private fun linesOf(text: String): List<org.json.JSONObject> {
        return text.trim().lines().filter { it.isNotBlank() }.map { org.json.JSONObject(it) }
    }

    @Test
    fun `导出行逐字段解析回来与golden键集合对齐`() {
        val coupon = slot(
            discount = 300.0,
            threshold = 4999.0,
            scope = CouponScope.SHOP,
            state = CouponState.CLAIMED,
            expiry = "2026-10-08",
            code = "A1b2C3d4E5",
            url = "https://u.jd.com/test",
            sourceText = "领券满4999减300",
            nodePath = listOf(0, 2)
        )
        val ledger = MisreadLedger()
        assertTrue(ledger.mark(extraction(listOf(coupon)), 0, 1760000000000L))
        val lines = linesOf(CouponMisreadJsonl.export(ledger.snapshot()))
        assertEquals(1, lines.size)
        val entry = lines.single()

        val entryKeys = entry.keys().asSequence().toSet()
        assertTrue("条目必须含 eval 必需键 $evalEntryRequired", evalEntryRequired.all { entryKeys.contains(it) })
        assertEquals("条目键必须都在 eval 认识的范围里", emptySet<String>(), entryKeys - evalEntryAllowed)
        assertEquals("usermark-1760000000000-1", entry.getString("id"))
        assertEquals("page_node", entry.getString("source"))
        assertEquals(coupon.sourceText, entry.getString("raw"))

        val coupons = entry.getJSONArray("coupons")
        assertEquals("只导被标记的那一张", 1, coupons.length())
        val parsed = coupons.getJSONObject(0)
        assertEquals(evalCouponKeys, parsed.keys().asSequence().toSet())
        assertEquals(300.0, parsed.getDouble("discount"), 1e-9)
        assertEquals(4999.0, parsed.getDouble("threshold"), 1e-9)
        assertEquals("shop", parsed.getString("scope"))
        assertEquals("claimed", parsed.getString("state"))
        assertEquals("2026-10-08", parsed.getString("expiry"))
        assertEquals("https://u.jd.com/test", parsed.getString("url"))
        assertFalse("金额槽不许是字符串（eval 的 amount 会拒）", parsed.get("discount") is String)

        val price = entry.getJSONObject("price")
        assertEquals(evalPriceKeys, price.keys().asSequence().toSet())
        assertEquals(5499.0, price.getDouble("final"), 1e-9)
        assertEquals(5999.0, price.getDouble("list"), 1e-9)
        assertTrue("降幅没读到就是 null", price.isNull("drop"))

        val userMark = entry.getJSONObject("user_mark")
        assertEquals("user_says_wrong", userMark.getString("verdict"))
        assertEquals("A1b2C3d4E5", userMark.getString("code"))
        assertEquals("HIGH", userMark.getString("tier"))
        assertEquals(0.9, userMark.getDouble("confidence"), 1e-9)
        assertEquals(listOf(0, 2), listOf(userMark.getJSONArray("node_path").getInt(0), userMark.getJSONArray("node_path").getInt(1)))
        assertEquals(1760000000000L, userMark.getLong("marked_at_ms"))

        assertTrue("每行都要写明人工回灌口径", entry.getString("note").contains("tools/golden"))
    }

    @Test
    fun `零门槛是数字未给门槛是null六键都不许省略`() {
        val zero = slot(discount = 70.0, threshold = 0.0, sourceText = "【点击领取】70元无门槛立减券")
        val none = slot(discount = null, threshold = null, sourceText = "可能是券的一句话")
        val ledger = MisreadLedger()
        val ex = extraction(listOf(zero, none))
        assertTrue(ledger.mark(ex, 0, 1760000001000L))
        assertTrue(ledger.mark(ex, 1, 1760000002000L))
        val lines = linesOf(CouponMisreadJsonl.export(ledger.snapshot()))
        assertEquals(2, lines.size)

        val first = lines[0].getJSONArray("coupons").getJSONObject(0)
        assertTrue("键必须在，值才是 null", first.has("threshold") && first.has("discount"))
        assertEquals(0.0, first.getDouble("threshold"), 1e-9)
        assertEquals("无门槛(0.0) 与 没写(null) 判据不同，导出必须先保住类型", false, first.isNull("threshold"))

        val second = lines[1].getJSONArray("coupons").getJSONObject(0)
        assertTrue(second.isNull("threshold"))
        assertTrue(second.isNull("discount"))
        assertTrue("UNKNOWN 的范围/状态导成 null 而不是 unknown 字样", second.isNull("scope") && second.isNull("state"))
    }

    @Test
    fun `没被标记的券不出现在导出里`() {
        val kept = slot(discount = 5.0, threshold = 20.0, sourceText = "第一张 满20减5")
        val marked = slot(discount = 10.0, threshold = 40.0, sourceText = "第二张 满40减10")
        val ledger = MisreadLedger()
        val ex = extraction(listOf(kept, marked))
        assertFalse("没点过的不占清单", ledger.isMarked(ex, 0))
        assertTrue(ledger.mark(ex, 1, 1760000003000L))
        val text = CouponMisreadJsonl.export(ledger.snapshot())
        val lines = linesOf(text)
        assertEquals(1, lines.size)
        assertEquals("第二张 满40减10", lines.single().getString("raw"))
        assertFalse("未标记券的原文一个字节都不许出现在导出里", text.contains("第一张 满20减5"))
        assertEquals(1, lines.single().getJSONArray("coupons").length())
    }

    @Test
    fun `重复标记幂等且行序保证id唯一`() {
        val same = slot(discount = 5.0, sourceText = "同一句话抽出的两张")
        val ledger = MisreadLedger()
        val ex = extraction(listOf(same, same.copy()))
        assertTrue(ledger.mark(ex, 0, 1760000004000L))
        assertFalse("同键重复标记返回 false 且不重复记账", ledger.mark(ex, 0, 1760000005000L))
        assertEquals(1, ledger.markedCount())
        assertTrue("同文案不同下标是两张券，各自记", ledger.mark(ex, 1, 1760000005000L))
        assertEquals(2, ledger.markedCount())
        val ids = linesOf(CouponMisreadJsonl.export(ledger.snapshot())).map { it.getString("id") }
        assertEquals("同一毫秒也不撞 id", 2, ids.distinct().size)
    }

    @Test
    fun `凭证cookie一律不落盘`() {
        val coupon = slot(
            discount = 5.0,
            threshold = 20.0,
            sourceText = "领券页提示 cookie=abc123secret 满20减5",
            url = "https://u.jd.com/x?pt_key=TOKENVALUE999&y=1"
        )
        val ledger = MisreadLedger()
        val ex = extraction(listOf(coupon), source = ExtractSource.CLIPBOARD)
        assertTrue(ledger.mark(ex, 0, 1760000006000L))
        val text = CouponMisreadJsonl.export(ledger.snapshot())
        assertFalse(text.contains("abc123secret"))
        assertFalse(text.contains("TOKENVALUE999"))
        assertTrue("含凭证键的值后段被打成 ***", text.contains("cookie=***"))
        val lines = linesOf(text)
        assertEquals("脱敏只毁值不毁行：整行仍是合法 JSON", 1, lines.size)
        assertEquals("clipboard", lines.single().getString("source"))
    }

    @Test
    fun `三入口映射到golden的source三值`() {
        assertEquals("page_node", CouponMisreadJsonl.goldenSource(ExtractSource.PAGE_NODE))
        assertEquals("clipboard", CouponMisreadJsonl.goldenSource(ExtractSource.CLIPBOARD))
        assertEquals("community", CouponMisreadJsonl.goldenSource(ExtractSource.COMMUNITY))
        for (source in ExtractSource.entries) {
            assertTrue(CouponMisreadJsonl.goldenSource(source) in evalSources)
        }
    }

    @Test
    fun `空清单导出空文本`() {
        assertEquals("", CouponMisreadJsonl.export(emptyList()))
    }

    @Test
    fun `落盘沿用崩溃日志链路命名裁剪且只认自家文件`() {
        val dir = tmp.newFolder("couponmisreads")
        val store = CouponMisreadStore(dir)
        val marks = MisreadLedger()
        marks.mark(extraction(listOf(slot(discount = 5.0, sourceText = "满20减5"))), 0, 1760000007000L)
        val jsonl = CouponMisreadJsonl.export(marks.snapshot())

        val written = store.write(jsonl, 1760000007000L)
        assertEquals("coupon-misread-1760000007000.jsonl", written.name)
        assertEquals(jsonl, written.readText(Charsets.UTF_8))

        // 别人的文件（崩溃日志同款命名）混在同目录也不能被裁剪误删
        dir.resolve("crash-123.log").writeText("x")
        dir.resolve("notes.txt").writeText("y")
        for (i in 1..5) {
            store.write(jsonl, 1760000007000L + i)
        }
        store.prune(keep = 3)
        val ours = dir.listFiles()?.map { it.name }?.filter { it.startsWith("coupon-misread-") } ?: emptyList()
        assertEquals(3, ours.size)
        assertTrue(dir.resolve("crash-123.log").exists())
        assertTrue(dir.resolve("notes.txt").exists())
        assertEquals(3, store.listNewestFirst().size)
        assertEquals(1760000007005L, CouponMisreadFiles.parseEpoch(store.listNewestFirst().first().name))
        assertEquals(null, CouponMisreadFiles.parseEpoch("crash-123.log"))
    }
}
