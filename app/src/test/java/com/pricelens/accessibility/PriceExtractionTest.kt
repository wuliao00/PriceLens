package com.pricelens.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 价格取法（P0-2）表驱动测试：已知主价 ID 优先；分期数/券面额/存储容量不得被当价格；
 * 输出价格口径（页面价/券后价/到手价）。夹具与 NodeFixtures.kt 同源（uiautomator dump 手工构造）。
 */
class PriceExtractionTest {

    @Test
    fun `known jd price id wins over installment and coupon texts`() {
        val hit = extractPriceHit(jdDetailPage(), ShopPlatform.JD)
        assertNotNull(hit)
        assertEquals(5499.0, hit!!.value, 0.001)
        assertTrue(hit.viaKnownId)
        assertEquals(PriceBasis.PAGE, hit.basis)
    }

    @Test
    fun `currency symbol beats bare numbers in bfs order`() {
        val page = container(
            kids = arrayOf(
                leaf(text = "12", res = "com.jingdong.app.mall:id/period_count"),
                leaf(text = "300", res = "com.jingdong.app.mall:id/coupon_amount"),
                leaf(text = "256"),
                leaf(text = "¥5,499", res = "com.jingdong.app.mall:id/jd_price")
            )
        )
        val hit = extractPriceHit(page, ShopPlatform.JD)
        assertNotNull(hit)
        assertEquals(5499.0, hit!!.value, 0.001)
    }

    @Test
    fun `bare numbers alone are never a price`() {
        // 全页没有 ¥ 语境：256/300/12 之类的裸数字（存储/券额/分期）一律不判价
        val page = container(
            kids = arrayOf(
                leaf(text = "256"),
                leaf(text = "12", res = "com.jingdong.app.mall:id/period_count"),
                leaf(text = "300", res = "com.jingdong.app.mall:id/coupon_amount")
            )
        )
        assertNull(extractPriceHit(page, ShopPlatform.JD))
    }

    @Test
    fun `bare number inside a card with currency sibling is accepted as page price`() {
        val card = container(
            kids = arrayOf(
                leaf(text = "¥"), // 货币符号独立节点（电商价格区常见拆分渲染）
                leaf(text = "1299")
            )
        )
        val hit = extractPriceHit(card, ShopPlatform.UNKNOWN)
        assertNotNull(hit)
        assertEquals(1299.0, hit!!.value, 0.001)
        assertEquals(PriceBasis.PAGE, hit.basis)
    }

    @Test
    fun `split decimal tail is glued onto the integer part`() {
        // 真机商详主价渲染成「¥1838」+「.9」两个紧邻兄弟节点（小数分体，见
        // fixtures/jd_detail_instock_20260929.xml）：只读「¥1838」会把 1838.9 报成 1838。
        val page = container(
            kids = arrayOf(
                container(kids = arrayOf(leaf(text = "¥1838"), leaf(text = ".9"), leaf(text = "12期免息"))),
                container(kids = arrayOf(leaf(text = "¥1099")))
            )
        )
        val hit = extractPriceHit(page, ShopPlatform.JD)
        assertNotNull(hit)
        assertEquals(1838.9, hit!!.value, 0.001)
        assertEquals("¥1838.9", hit.rawText)
        assertEquals(PriceBasis.PAGE, hit.basis)
    }

    @Test
    fun `currency symbol integer and decimal split across three sibling nodes`() {
        // 「¥」「1838」「.9」三段式渲染（走三级裸数字兜底）同样要读出 1838.9
        val page = container(
            kids = arrayOf(leaf(text = "¥"), leaf(text = "1838"), leaf(text = ".9"))
        )
        val hit = extractPriceHit(page, ShopPlatform.UNKNOWN)
        assertNotNull(hit)
        assertEquals(1838.9, hit!!.value, 0.001)
    }

    @Test
    fun `a decimal tail in a different container is not glued on`() {
        // 只有**同一父节点的紧邻兄弟**才是同一段价格的小数位；隔了容器的 .99 不能拼
        val page = container(
            kids = arrayOf(
                container(kids = arrayOf(leaf(text = "¥1299"))),
                container(kids = arrayOf(leaf(text = ".99"), leaf(text = "¥1299")))
            )
        )
        val hit = extractPriceHit(page, ShopPlatform.JD)
        assertNotNull(hit)
        assertEquals(1299.0, hit!!.value, 0.001)
    }

    @Test
    fun `price basis labels parsed from context text`() {
        val net = extractPriceHit(container(kids = arrayOf(leaf(text = "到手价￥129"))), ShopPlatform.UNKNOWN)
        assertEquals(129.0, net!!.value, 0.001)
        assertEquals(PriceBasis.NET, net.basis)

        val coupon = extractPriceHit(container(kids = arrayOf(leaf(text = "券后价 ¥199"))), ShopPlatform.UNKNOWN)
        assertEquals(199.0, coupon!!.value, 0.001)
        assertEquals(PriceBasis.AFTER_COUPON, coupon.basis)

        val page = extractPriceHit(container(kids = arrayOf(leaf(text = "¥7,999.00"))), ShopPlatform.UNKNOWN)
        assertEquals(7999.0, page!!.value, 0.001)
        assertEquals(PriceBasis.PAGE, page.basis)
    }

    @Test
    fun `installment context text excluded even with currency symbol`() {
        val page = container(
            kids = arrayOf(
                leaf(text = "¥2,000 支持分期"),
                leaf(text = "¥5,499", res = "com.jingdong.app.mall:id/jd_price")
            )
        )
        val hit = extractPriceHit(page, ShopPlatform.JD)
        assertNotNull(hit)
        assertEquals(5499.0, hit!!.value, 0.001)
    }

    @Test
    fun `implausible values rejected`() {
        // 超过 30 万的"浏览量/总销量"类数字不当价
        val huge = container(kids = arrayOf(leaf(text = "¥3,800,000")))
        assertNull(extractPriceHit(huge, ShopPlatform.UNKNOWN))
        // 0 元
        val zero = container(kids = arrayOf(leaf(text = "¥0")))
        assertNull(extractPriceHit(zero, ShopPlatform.UNKNOWN))
    }

    @Test
    fun `extractPrice keeps legacy formats`() {
        assertEquals(7999.0, PriceNodeMatcher.extractPrice("¥7,999")!!, 0.001)
        assertEquals(7999.0, PriceNodeMatcher.extractPrice("7999.00")!!, 0.001)
        assertEquals(129.0, PriceNodeMatcher.extractPrice("到手价￥129")!!, 0.001)
        assertNull(PriceNodeMatcher.extractPrice("no numbers"))
    }

    @Test
    fun `deterministic item id recognized including m-site and sku param`() {
        val mSite = container(kids = arrayOf(leaf(text = "https://item.m.jd.com/product/100012043976.html")))
        assertEquals("100012043976", extractItemId(mSite))

        val skuParam = container(kids = arrayOf(leaf(desc = "https://detail.m.jd.com/product/x.html?sku=100082819140&hybr=FY")))
        assertEquals("100082819140", extractItemId(skuParam))

        val plain = container(kids = arrayOf(leaf(text = "HUAWEI Mate 80")))
        assertNull(extractItemId(plain))
    }
}
