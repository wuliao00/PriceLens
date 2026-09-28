package com.pricelens.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面门控（P1-5）与同卡片配对表驱动测试。
 * 旧版只按包名过滤：首页/列表/购物车同样 emit，"点进某商品却读到别的价格"。
 * 新门控要求购买动作 + 商详结构特征，并否决购物车/下单页特征。
 */
class PageGatingTest {

    @Test
    fun `product detail pages pass the gate`() {
        assertTrue(isProductPage(jdDetailPage(), ShopPlatform.JD))
        assertTrue(isProductPage(taobaoDetailPage(), ShopPlatform.TAOBAO))
        assertTrue(isProductPage(pddDetailPage(), ShopPlatform.PDD))
    }

    @Test
    fun `detail page without lazy-loaded section passes via buy-now plus known price id`() {
        // 页面刚进上半屏：无"商品详情"分区文本，但有底栏"立即购买" + 已知主价 ID
        val earlyHalf = container(
            kids = arrayOf(
                leaf(text = "¥5,499", res = "com.jingdong.app.mall:id/jd_price"),
                leaf(text = "HUAWEI Mate 80 12GB+256GB 曜石黑 鸿蒙AI", res = "com.jingdong.app.mall:id/goods_title"),
                leaf(desc = "立即购买", clickable = true)
            )
        )
        assertTrue(isProductPage(earlyHalf, ShopPlatform.JD))
    }

    @Test
    fun `search list page never passes even when cards carry buy icons`() {
        // 列表卡片：带 ¥ + 标题 + "加入购物车"图标，但没有"立即购买"类立购动作 → 拒
        assertFalse(isProductPage(jdSearchListPage(), ShopPlatform.JD))
    }

    @Test
    fun `cart home and checkout pages are gated out`() {
        assertFalse(isProductPage(jdCartPage(), ShopPlatform.JD))
        assertFalse(isProductPage(jdHomePage(), ShopPlatform.JD))
        val orderConfirm = container(
            kids = arrayOf(
                leaf(text = "Apple iPhone 15 5G手机 白色钛金属 128GB", res = "com.jingdong.app.mall:id/goods_title"),
                leaf(text = "¥5,999", res = "com.jingdong.app.mall:id/jd_price"),
                leaf(text = "提交订单", clickable = true)
            )
        )
        assertFalse(isProductPage(orderConfirm, ShopPlatform.JD))
    }

    @Test
    fun `list page pairs title with price from the same card`() {
        val pick = pickSameCardTitleAndPrice(jdSearchListPage(), ShopPlatform.JD)
        assertNotNull(pick)
        // 第一张卡片：小米 13 标题配 1299；旧实现会拿"全页最长名(Redmi K80)"配"第一张卡价 1299"
        assertTrue(pick!!.title.contains("小米 13"))
        assertFalse(pick.title.contains("K80"))
        assertEquals(1299.0, pick.price.value, 0.001)
    }

    @Test
    fun `platform mapping`() {
        assertEquals(ShopPlatform.JD, ShopPlatform.fromPackage("com.jingdong.app.mall"))
        assertEquals(ShopPlatform.TAOBAO, ShopPlatform.fromPackage("com.taobao.taobao"))
        assertEquals(ShopPlatform.PDD, ShopPlatform.fromPackage("com.xunmeng.pinduoduo"))
        assertEquals(ShopPlatform.UNKNOWN, ShopPlatform.fromPackage("com.tencent.mm"))
    }

    @Test
    fun `price basis detection`() {
        assertEquals(PriceBasis.NET, PriceBasis.detect("到手价￥129"))
        assertEquals(PriceBasis.AFTER_COUPON, PriceBasis.detect("券后价 ¥199"))
        assertEquals(PriceBasis.PAGE, PriceBasis.detect("¥5,499"))
        assertEquals(PriceBasis.PAGE, PriceBasis.detect(null))
    }
}
