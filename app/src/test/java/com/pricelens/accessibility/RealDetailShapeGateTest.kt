package com.pricelens.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真机**结构形态**的商详页必须过门控（2026-09-29 真机复现的"浮窗不弹"回归）。
 *
 * 用户头号抱怨"点击购物平台，比价浮窗显示错误不准确"的另一半：真机京东商详页上浮窗**压根不弹**。
 * 已证事实（见 [RealDumpGatingTest] 与本轮 dumpsys activity top 取证）：
 *  - 新版京东商详页 76 个 distinct resource-id 全是混淆短名，主价 ID 白名单一个都不命中
 *    → `priceHit.viaKnownId` 恒 false；
 *  - 首屏顶部 tab 是「商品/大家评/详情/推荐」，没有 `DETAIL_SECTION_WORDS` 里的任何字样
 *    → `hasDetailSection` 恒 false；
 *  两条同时为 false 时，旧门控的兜底 `hasBuyNow && priceHit.viaKnownId` 在真机上恒 false
 *  → **首屏必被拒**；滚到详情区后标题/主价节点已被 RecyclerView 回收出树，仍然拒。
 *
 * 本类用两棵**按上述实测事实手工构造**的树（id 为混淆短名、无"商品详情"字样、价格"¥1838"+".9"分体）
 * 断言：现货页与预约页各自都能过门控，且读出的价/标题属于**同一件商品**。
 * 与 [RealDumpGatingTest] 是一对反向钉子：那边保证真机首页/搜索页**不过**，这边保证真机商详页**过**。
 */
class RealDetailShapeGateTest {

    private val instock = loadRealDump(INSTOCK_DUMP)
    private val presale = loadRealDump(PRESALE_DUMP)

    @Test
    fun `real-shaped in-stock detail page passes the gate`() {
        println("[budget] ${instock.describeBudget()}")
        println("[criteria] ${instock.name} -> ${gateReport(instock.root)}")
        println("[extract] ${instock.name} -> ${extractionReport(instock.root)}")

        // 真机首屏：既没有"商品详情"分区字样，也没有任何已知主价 ID —— 这两条是缺陷的根因，先把它们钉住
        val texts = dumpTexts(instock.root)
        assertFalse("夹具里混进了『商品详情』类字样 = 没复刻真机首屏", texts.any { PriceNodeMatcher.isDetailSection(it) })
        assertNull("夹具里混进了语义化主价 ID = 没复刻真机的混淆短名", knownPriceIdNode(instock.root))
        assertTrue("真机底栏同时有『加入购物车』", texts.any { PriceNodeMatcher.isBuyAction(it) })
        assertTrue("真机底栏同时有『立即购买』", texts.any { PriceNodeMatcher.isBuyNowAction(it) })

        assertTrue("¥1838.9 现货商详页在真机上过不了门控 = 用户看到的『浮窗不弹』", isProductPage(instock.root, ShopPlatform.JD))
        // 服务侧调用顺序镜像：门控过了才取价/取标题，浮窗必须拿到内容
        val payload = overlayPayloadOf(instock.root, ShopPlatform.JD)
        assertNotNull("门控过了但浮窗链路没产出内容", payload)
        assertEquals(1838.9, payload!!.first, 0.001)
    }

    @Test
    fun `in-stock detail price and title belong to the same product`() {
        val price = extractPriceHit(instock.root, ShopPlatform.JD)
        assertNotNull(price)
        assertEquals("真机主价是『¥1838』+『.9』两个兄弟节点，小数位不能丢", 1838.9, price!!.value, 0.001)
        assertEquals("¥1838.9", price.rawText)
        assertEquals(PriceBasis.PAGE, price.basis)
        assertFalse("门控不再依赖 viaKnownId：真机商详主价没有任何已知 ID", price.viaKnownId)

        val title = extractTitle(instock.root, ShopPlatform.JD)
        assertNotNull(title)
        assertTrue("标题必须是被识别的这件商品：${title!!.text}", title.text.contains("飞天"))
        assertTrue(title.text.contains("500ml"))
        assertTrue(title.text.contains("2026年"))
        assertFalse("标题串到了推荐位的别的商品：${title.text}", title.text.contains("五粮液"))
        assertFalse("标题串到了推荐位的别的商品：${title.text}", title.text.contains("国窖"))
        assertFalse("标题不来自任何已知标题 ID（真机 id 全是混淆短名）", title.viaKnownId)
        assertEquals("茅台 2026年 飞天 500ml 现货页的浮窗内容", 1838.9 to title.text, overlayPayloadOf(instock.root, ShopPlatform.JD))
    }

    @Test
    fun `real-shaped reservation detail page passes the gate`() {
        println("[budget] ${presale.describeBudget()}")
        println("[criteria] ${presale.name} -> ${gateReport(presale.root)}")
        println("[extract] ${presale.name} -> ${extractionReport(presale.root)}")

        val texts = dumpTexts(presale.root)
        assertFalse("夹具里混进了『商品详情』类字样 = 没复刻真机首屏", texts.any { PriceNodeMatcher.isDetailSection(it) })
        assertNull("夹具里混进了语义化主价 ID = 没复刻真机的混淆短名", knownPriceIdNode(presale.root))
        assertFalse("预约型商详真机底栏没有『立即购买』", texts.any { it.contains("立即购买") })
        assertTrue("底栏是『立即预约』+『等待抢购』", texts.any { it.contains("立即预约") } && texts.any { it.contains("等待抢购") })

        assertTrue(
            "『茅台飞天 53%vol 500ml 需预约』¥1759 商详页过不了门控 = 真机第二例『浮窗不弹』",
            isProductPage(presale.root, ShopPlatform.JD)
        )
        val price = extractPriceHit(presale.root, ShopPlatform.JD)
        assertNotNull(price)
        assertEquals(1759.0, price!!.value, 0.001)
        assertFalse(price.viaKnownId)
        val title = extractTitle(presale.root, ShopPlatform.JD)
        assertNotNull(title)
        assertTrue("标题必须是被识别的这件商品：${title!!.text}", title.text.contains("飞天") && title.text.contains("500ml"))
        assertFalse("标题串到了推荐位的别的商品：${title.text}", title.text.contains("五粮液"))
        assertEquals(1759.0 to title.text, overlayPayloadOf(presale.root, ShopPlatform.JD))
    }

    @Test
    fun `both fixtures keep the hand-built real-shape tree`() {
        // 结构钉子：这两棵树的"像真机"完全靠构造纪律维持，节点数/深度变了就是被动过，结论作废。
        assertEquals(34, instock.rawNodeCount)
        assertEquals(9, instock.rawMaxDepth)
        assertEquals(28, presale.rawNodeCount)
        assertEquals(9, presale.rawMaxDepth)
        assertFalse(instock.truncated)
        assertFalse(presale.truncated)
        assertEquals(instock.rawNodeCount, instock.snapshotNodeCount)
        assertEquals(presale.rawNodeCount, presale.snapshotNodeCount)
    }

    // ---------- 诊断辅助（只读，不参与判定） ----------

    /** 门控逐项判据摊开：假阴性时直接看出卡在哪一关 */
    private fun gateReport(root: NodeSnapshot): String {
        val texts = dumpTexts(root)
        val price = extractPriceHit(root, ShopPlatform.JD)
        return "checkoutVeto=" + texts.any { PriceNodeMatcher.isCheckoutContext(it) } +
            " buyAction=" + texts.any { PriceNodeMatcher.isBuyAction(it) } +
            " detailSection=" + texts.any { PriceNodeMatcher.isDetailSection(it) } +
            " buyNow=" + texts.any { PriceNodeMatcher.isBuyNowAction(it) } +
            " price=${price?.let { "${it.value}/『${it.rawText}』knownId=${it.viaKnownId}" } ?: "null"}" +
            " title=${extractTitle(root, ShopPlatform.JD)?.text?.take(30) ?: "null"}" +
            " pass=" + isProductPage(root, ShopPlatform.JD)
    }

    private fun extractionReport(root: NodeSnapshot): String {
        val price = extractPriceHit(root, ShopPlatform.JD)
        val title = extractTitle(root, ShopPlatform.JD)
        return "price=${price?.let { "${it.value}/『${it.rawText}』${it.basis}" } ?: "null"}" +
            " title=${title?.let { "『${it.text}』knownId=${it.viaKnownId} viaCd=${it.viaContentDescription}" } ?: "null"}" +
            " itemId=${extractItemId(root) ?: "null"}"
    }

    /** 树里第一个命中"已知主价 ID 白名单"的节点（真机上应为 null，用于证明夹具没抄到理想 id） */
    private fun knownPriceIdNode(root: NodeSnapshot): NodeSnapshot? =
        bfs(root).firstOrNull {
            PriceNodeMatcher.isKnownPriceId(it.resourceName, ShopPlatform.JD) &&
                PriceNodeMatcher.isExcludedPriceId(it.resourceName).not()
        }

    /** 复刻 `PriceMonitorService.onAccessibilityEvent` 的调用顺序：先门控，过了才取价与标题 */
    private fun overlayPayloadOf(root: NodeSnapshot, platform: ShopPlatform): Pair<Double, String>? {
        if (!isProductPage(root, platform)) return null
        val price = extractPriceHit(root, platform) ?: return null
        return price.value to (extractTitle(root, platform)?.text ?: "")
    }

    private companion object {
        const val INSTOCK_DUMP = "jd_detail_instock_20260929.xml"
        const val PRESALE_DUMP = "jd_detail_presale_20260929.xml"
    }
}
