package com.pricelens.ui.product

import com.pricelens.R
import com.pricelens.coupon.Tier
import com.pricelens.coupon.Tiers
import com.pricelens.coupon.model.CouponScope
import com.pricelens.coupon.model.CouponSlot
import com.pricelens.coupon.model.CouponState
import com.pricelens.coupon.model.ExtractSource
import com.pricelens.coupon.model.Extraction
import com.pricelens.coupon.model.ExtractorKind
import com.pricelens.coupon.model.PriceSlots
import com.pricelens.ui.theme.BadgeTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B2 交付一的纯展示断言（Compose 版面本身 JVM 测不了，这里钉的全是展示模型的选择逻辑）：
 *  - 三档边界，含**恰好落在阈值上**的归属（0.85 归 HIGH、0.6 归 MEDIUM，闭区间下界是理解层刻意的）；
 *  - 逐槽展示：读不出的槽位必须"未识别"且**不携带任何数字**（不许拿面额凑门槛）；
 *  - 状态不是 UNKNOWN 就必须被显示；门槛 0 与 null 分开措辞。
 * 排版好不好看只能等人眼在真机上看，这里一概不声称。
 */
class CouponLocalLogicTest {

    private fun slot(
        discount: Double? = null,
        threshold: Double? = null,
        scope: CouponScope = CouponScope.UNKNOWN,
        state: CouponState = CouponState.UNKNOWN,
        expiry: String? = null,
        code: String? = null,
        url: String? = null,
        sourceText: String = "领券满4999减300"
    ) = CouponSlot(discount, threshold, scope, state, expiry, code, url, sourceText, emptyList())

    private fun extractionOf(vararg slots: CouponSlot, confidence: Double = 0.9) = Extraction(
        source = ExtractSource.CLIPBOARD,
        extractor = ExtractorKind.RULE,
        platform = "jd",
        itemRef = null,
        coupons = slots.toList(),
        price = PriceSlots(null, null, null),
        stackNote = null,
        confidence = confidence
    )

    // ---------- 三档边界（阈值含恰好落点） ----------

    @Test
    fun `恰好落在高阈值的下界归高档`() {
        val rows = localCouponRows(extractionOf(slot(), confidence = 0.85))
        assertEquals(Tier.HIGH, rows.single().tier)
        assertEquals(R.string.coupon_local_tier_high, tierLabelRes(Tier.HIGH))
        assertEquals(1.0, localCouponRows(extractionOf(slot(), confidence = 1.0)).single().confidence, 0.0)
        assertEquals(Tier.HIGH, localCouponRows(extractionOf(slot(), confidence = 0.8501)).single().tier)
    }

    @Test
    fun `差一点到高阈值归中档而恰好到零六也归中档`() {
        assertEquals(Tier.MEDIUM, localCouponRows(extractionOf(slot(), confidence = 0.8499)).single().tier)
        assertEquals(Tier.MEDIUM, localCouponRows(extractionOf(slot(), confidence = 0.6)).single().tier)
        assertEquals(R.string.coupon_local_tier_medium, tierLabelRes(Tier.MEDIUM))
    }

    @Test
    fun `差一点到零六归低档`() {
        assertEquals(Tier.LOW, localCouponRows(extractionOf(slot(), confidence = 0.5999)).single().tier)
        assertEquals(Tier.LOW, localCouponRows(extractionOf(slot(), confidence = 0.0)).single().tier)
        assertEquals(R.string.coupon_local_tier_low, tierLabelRes(Tier.LOW))
    }

    @Test
    fun `三档措辞与色调两两不同`() {
        val labels = listOf(Tier.HIGH, Tier.MEDIUM, Tier.LOW).map { tierLabelRes(it) }
        assertEquals(3, labels.distinct().size)
        val tones = listOf(Tier.HIGH, Tier.MEDIUM, Tier.LOW).map { tierTone(it) }
        assertEquals(3, tones.distinct().size)
        assertEquals(BadgeTone.POSITIVE, tierTone(Tier.HIGH))
        assertEquals(BadgeTone.NEGATIVE, tierTone(Tier.LOW))
    }

    // ---------- 逐槽展示 ----------

    @Test
    fun `门槛读不出时标未识别且绝不拿面额凑`() {
        val coupon = slot(discount = 50.0, threshold = null, state = CouponState.SOLD_OUT, sourceText = "满50减50券，已领完")
        val rows = localCouponRows(extractionOf(coupon))
        val cells = rows.single().cells
        assertEquals("七格固定顺序", 7, cells.size)
        assertEquals(R.string.coupon_local_slot_discount, cells[0].labelRes)
        assertEquals(R.string.coupon_local_slot_threshold, cells[1].labelRes)
        assertEquals(R.string.coupon_local_slot_scope, cells[2].labelRes)
        assertEquals(R.string.coupon_local_slot_state, cells[3].labelRes)
        assertEquals(R.string.coupon_local_slot_expiry, cells[4].labelRes)
        assertEquals(R.string.coupon_local_slot_code, cells[5].labelRes)
        assertEquals(R.string.coupon_local_slot_link, cells[6].labelRes)

        val threshold = cells[1]
        assertTrue("门槛读不出必须 unknown", threshold.unknown)
        assertEquals(R.string.coupon_local_unrecognized, threshold.valueRes)
        assertEquals("凑数即判红：未识别格不许携带任何值", 0, threshold.args.size)

        val discount = cells[0]
        assertEquals(R.string.coupon_local_value_amount, discount.valueRes)
        assertEquals(listOf("50"), discount.args)

        assertEquals("状态不是 UNKNOWN 就必须显示，且显示的是状态词本身", R.string.coupon_local_state_sold_out, cells[3].valueRes)
        assertEquals(false, cells[3].unknown)

        assertEquals("UNKNOWN 的范围格同样标未识别", true, cells[2].unknown)
        assertEquals("券码/链接/有效期读不出都是未识别", listOf(true, true, true), listOf(cells[4].unknown, cells[5].unknown, cells[6].unknown))

        assertEquals("满50减50券，已领完", rows.single().sourceText)
    }

    /**
     * 「有券但没金额」这张**全 null 券**在展示层的形状（2026-10-05 抽取层新增的这种槽位：
     * 整句没数字却有券名，例如首页角标「试用专享券」）。
     * 要求是"看得见、但不装懂"：两格金额都标未识别、证据句还在、档位由调用方给的置信决定。
     */
    @Test
    fun `全空券显示成读不出而不是空白行`() {
        val rows = localCouponRows(extractionOf(slot(discount = null, threshold = null, sourceText = "试用专享券"), confidence = 0.4))
        assertEquals("一行都不能少：这张券的存在本身是信息", 1, rows.size)
        val cells = rows.single().cells
        assertEquals(7, cells.size)
        assertTrue("面额没读出来就必须标未识别", cells[0].unknown)
        assertTrue("门槛没读出来就必须标未识别", cells[1].unknown)
        assertEquals(R.string.coupon_local_unrecognized, cells[0].valueRes)
        assertEquals(listOf<String>(), cells[0].args + cells[1].args)
        assertEquals("证据句要让用户能看到是从哪句读出来的", "试用专享券", rows.single().sourceText)
    }

    @Test
    fun `门槛零是文案显式写了无门槛与未识别是两件事`() {
        val rows = localCouponRows(extractionOf(slot(discount = 70.0, threshold = 0.0)))
        val threshold = rows.single().cells[1]
        assertEquals(false, threshold.unknown)
        assertEquals(R.string.coupon_no_threshold, threshold.valueRes)
        assertEquals(0, threshold.args.size)
    }

    @Test
    fun `券码有效期链接有值就原样给显示层`() {
        val filled = slot(
            expiry = "2026-10-08",
            code = "A1b2C3",
            url = "https://u.jd.com/x",
            scope = CouponScope.PLATFORM,
            state = CouponState.REGION_LIMITED
        )
        val rows = localCouponRows(extractionOf(filled))
        val cells = rows.single().cells
        assertEquals(listOf("2026-10-08"), cells[4].args)
        assertEquals(listOf("A1b2C3"), cells[5].args)
        assertEquals(listOf("https://u.jd.com/x"), cells[6].args)
        assertEquals(R.string.coupon_local_scope_platform, cells[2].valueRes)
        assertEquals(R.string.coupon_local_state_region_limited, cells[3].valueRes)
    }

    @Test
    fun `空白的字符串槽位也算读不出`() {
        val rows = localCouponRows(extractionOf(slot(expiry = "   ")))
        assertTrue(rows.single().cells[4].unknown)
    }

    @Test
    fun `每张券一行且 index 对齐抽取下标`() {
        val rows = localCouponRows(extractionOf(slot(sourceText = "第一张 满20减5"), slot(sourceText = "第二张 满40减10"), confidence = 0.95))
        assertEquals(2, rows.size)
        assertEquals(listOf(0, 1), rows.map { it.index })
        assertEquals(listOf("第一张 满20减5", "第二张 满40减10"), rows.map { it.sourceText })
        assertEquals(Tier.HIGH, rows.first().tier)
    }

    @Test
    fun `没抽出券就没有本机行`() {
        assertTrue(localCouponRows(extractionOf()).isEmpty())
    }

    @Test
    fun `展示层不许改档`() {
        // 同一置信在理解层与展示层的判定必须一致（展示层自己调阈值 = 说谎）
        val conf = 0.85
        assertEquals(Tiers.of(conf), localCouponRows(extractionOf(slot(), confidence = conf)).single().tier)
        assertNotEquals(Tier.LOW, localCouponRows(extractionOf(slot(), confidence = conf)).single().tier)
    }
}
