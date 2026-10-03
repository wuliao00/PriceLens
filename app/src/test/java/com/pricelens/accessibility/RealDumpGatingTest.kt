package com.pricelens.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真机实采回归：2026-09-29 从 vivo V2156A（京东 App 已登录）抓的两棵真实节点树
 * —— 首页 `MainFrameActivity` 与搜索页 `SearchActivity` —— 都**不是商详页**，
 * 浮窗绝不该弹，而两页满是价格与商品标题，正是最容易让 [isProductPage] 误判的输入。
 *
 * 与 [PageGatingTest] 的分工：PageGatingTest 用**手工构造**的夹具证明"规则按设计意图工作"；
 * 本类用**真机原样 dump**（脱敏只动文本叶子，结构/class/resource-id/clickable 未动）
 * 证明"规则在真机上不误判"。用户头号抱怨"点购物平台浮窗显示错误不准确"的根因之一
 * 就是这个门控的假阳性，所以这里的断言方向只有一个：**期望 false**，永不放宽。
 *
 * 夹具来源与规模见 [UiAutomatorDump]（生产侧 4000 节点/64 深度预算在此原样复刻）。
 */
class RealDumpGatingTest {

    private val home = loadRealDump(HOME_DUMP)
    private val search = loadRealDump(SEARCH_DUMP)

    @Test
    fun `real jd home and search dumps never pass the product page gate`() {
        println("[budget] ${home.describeBudget()}")
        println("[budget] ${search.describeBudget()}")
        println("[criteria] ${home.name} -> ${gateSignals(home.root)}")
        println("[criteria] ${search.name} -> ${gateSignals(search.root)}")
        println("[extract] ${home.name} -> ${extractionReport(home.root)}")
        println("[extract] ${search.name} -> ${extractionReport(search.root)}")

        assertFalse("真机京东首页过了商详门控 = 用户看到的浮窗内容必错", isProductPage(home.root, ShopPlatform.JD))
        assertFalse("真机京东搜索页过了商详门控 = 用户看到的浮窗内容必错", isProductPage(search.root, ShopPlatform.JD))
    }

    @Test
    fun `fixtures keep the exact real device tree shape`() {
        // 结构钉子：脱敏/再采集只能改文本叶子。节点数与深度变了 = 夹具被动过，结论作废。
        assertEquals(404, home.rawNodeCount)
        assertEquals(27, home.rawMaxDepth)
        assertEquals(207, search.rawNodeCount)
        assertEquals(25, search.rawMaxDepth)
        // 这两页的真实规模都在生产预算内（uiautomator 只序列化可见子树），所以判定树没有被截断
        assertFalse(home.truncated)
        assertFalse(search.truncated)
        assertEquals(home.rawNodeCount, home.snapshotNodeCount)
        assertEquals(search.rawNodeCount, search.snapshotNodeCount)
    }

    @Test
    fun `home dump does trip the buy action signal and is held only by the detail section rule`() {
        // 真实首页信息流卡片就带 content-desc "加入购物车按钮" → 门控第一级信号为真。
        // 手工夹具 jdHomePage() 没有这个按钮，所以这条**只有真机能证明**：
        // 挡住首页弹窗的不是"有没有购买动作"，而是"商品详情分区标记 / 立购动作 + 已知主价 ID"这一关。
        val texts = dumpTexts(home.root)
        assertTrue("真机首页带『加入购物车』信号（改版挪文案时这条会先红，提示校准词表）",
            texts.any { PriceNodeMatcher.isBuyAction(it) })
        assertFalse(texts.any { PriceNodeMatcher.isDetailSection(it) })
        assertFalse(texts.any { PriceNodeMatcher.isBuyNowAction(it) })
        assertFalse(texts.any { PriceNodeMatcher.isCheckoutContext(it) })
    }

    @Test
    fun `search dump has no buy action and no price at all`() {
        val texts = dumpTexts(search.root)
        assertTrue(texts.isNotEmpty())
        assertFalse(texts.any { PriceNodeMatcher.isBuyAction(it) })
        assertFalse(texts.any { PriceNodeMatcher.isBuyNowAction(it) })
        assertNull("搜索页（搜索历史/搜索发现/今日热搜）整棵树读不出价格", extractPriceHit(search.root, ShopPlatform.JD))
        assertNull(overlayPayloadOf(search.root, ShopPlatform.JD))
    }

    /**
     * 预约/抢购词表的假阳性哨兵（加词前 grep 过这棵树，加词后由这条复验）。
     *
     * 真机首页信息流里有「**抢先预约**」（iQOO 16 福袋卡片的 content-desc）和「**等待抢购**」
     * （京东秒杀位）两类字样。给 `BUY_ACTION_WORDS`/`BUY_NOW_WORDS` 收「立即预约」这类
     * **完整底栏按钮文案**是安全的；收「预约」「抢购」这种短子串，首页立刻会变成假商详
     * —— 而首页没有"当前商品的单一价格"（见 [home page price is readable but belongs to no single product so the overlay stays closed]）。
     */
    @Test
    fun `home feed reservation wording never reads as a bottom-bar buy action`() {
        val texts = dumpTexts(home.root)
        val reservationish = texts.filter { it.contains("预约") || it.contains("抢购") }
        assertTrue("真机首页应带预约/抢购类字样（没了就说明夹具被换过，本哨兵失去意义）", reservationish.isNotEmpty())
        assertTrue(reservationish.any { it.contains("抢先预约") })
        assertTrue(reservationish.any { it.contains("等待抢购") })
        for (t in reservationish) {
            assertFalse("首页文案『$t』被判成底栏立购动作 → 首页会误弹窗", PriceNodeMatcher.isBuyNowAction(t))
            assertFalse("首页文案『$t』被判成底栏购买动作 → 首页会误弹窗", PriceNodeMatcher.isBuyAction(t))
        }
    }

    /**
     * 「去掉 viaKnownId 兜底后，只看 hasBuyNow 会不会把首页放进来」的正面验证：
     * 首页 buyAction / 价格 / 标题三项全有（旧兜底靠 viaKnownId=false 拦住它，而真机商详页同样
     * viaKnownId=false，那条兜底是死条件），唯一还拦住首页的就是「底栏立购动作」这一关为 false。
     */
    @Test
    fun `home is held out by the bottom-bar buy-now signal alone`() {
        val texts = dumpTexts(home.root)
        assertTrue(texts.any { PriceNodeMatcher.isBuyAction(it) })
        assertNotNull(extractPriceHit(home.root, ShopPlatform.JD))
        assertNotNull(extractTitle(home.root, ShopPlatform.JD))
        assertFalse("首页没有商详分区标记", texts.any { PriceNodeMatcher.isDetailSection(it) })
        assertFalse("首页没有底栏立购/预约动作", texts.any { PriceNodeMatcher.isBuyNowAction(it) })
        assertFalse("仅凭 hasBuyAction+有价+有标题 就把首页当商详 = 用户看到的浮窗内容必错",
            isProductPage(home.root, ShopPlatform.JD))
    }

    /**
     * 配对用例（门控为 false 时浮窗链路根本不会被调用）：
     * 真机首页**读得出价也读得出标题**——`¥9998` 是信息流里"iQOO 16 预约福袋"卡片的价格文案，
     * 属于另一张卡片，且整页 `extractItemId` 为空（没有确定性 SKU）→ 这一页**没有"当前商品的单一价格"**。
     * 旧版正是在这种页面上 emit，于是"点进商品看到的却是别家的价"。
     * 今天挡住它的只有 [isProductPage] 这一关：门控不过 → 后面三次 extract 一次都不执行。
     */
    @Test
    fun `home page price is readable but belongs to no single product so the overlay stays closed`() {
        val price = extractPriceHit(home.root, ShopPlatform.JD)
        assertNotNull("真机首页应能读出信息流卡片价（读不出说明夹具文本被改坏）", price)
        assertEquals("首页第一个被采信的价格是 iQOO 福袋卡片的 ¥9998", 9998.0, price!!.value, 0.001)
        assertEquals("¥9998", price.rawText)
        assertFalse("首页没有任何已知主价 ID", price.viaKnownId)
        assertNotNull("真机首页也能读出信息流标题（说明光靠『读得到内容』挡不住浮窗）",
            extractTitle(home.root, ShopPlatform.JD))
        assertNull("首页没有确定性商品 ID：即便过门控也只能 TITLE_ONLY，绝不能当『这个商品的现价』",
            extractItemId(home.root))
        // 服务侧调用顺序的镜像：门控不过 → 链路不产出任何 Detected
        assertNull("非商详页必须收窗：浮窗链路（价格+标题+ID）不得被调用",
            overlayPayloadOf(home.root, ShopPlatform.JD))
    }

    @Test
    fun `converter honours the production node budget`() {
        val flat = parseRealDumpFromXml("flat.xml", flatDump(4_100))
        println("[budget] ${flat.describeBudget()}")
        assertEquals(4_101, flat.rawNodeCount)
        assertTrue("4100 个兄弟节点必须触发节点预算截断", flat.truncated)
        assertEquals(REAL_DUMP_MAX_SNAPSHOT_NODES + 1, flat.snapshotNodeCount)

        val deep = parseRealDumpFromXml("deep.xml", chainDump(100))
        println("[budget] ${deep.describeBudget()}")
        assertEquals(100, deep.rawNodeCount)
        assertEquals(99, deep.rawMaxDepth)
        assertEquals(REAL_DUMP_MAX_SNAPSHOT_DEPTH, deep.snapshotMaxDepth)
        assertEquals(REAL_DUMP_MAX_SNAPSHOT_DEPTH + 1, deep.snapshotNodeCount)
    }

    /**
     * 「领取补贴购买」加词哨兵（2026-10-03 真机：京东国补商品的**完整商详页**底栏用它替换了
     * 「立即购买」，resource-id 仍是 `feature:id/b34`，不加词这一页就判不成商详、浮窗不弹）。
     *
     * 加词前核对过：这五个字只出现在商详底栏，**首页与搜索页的真机树里一次都没有**。
     * 这条就是那个核对的固化 —— 哪天京东把它搬进首页信息流，这里先红，提示重新校准词表，
     * 而不是等用户发现"首页也弹浮窗了"。
     */
    @Test
    fun `subsidy buy wording is absent from the real home and search dumps`() {
        for (dump in listOf(home, search)) {
            val texts = dumpTexts(dump.root)
            assertFalse(
                "${dump.name} 出现「领取补贴购买」→ 收进立购词表会让这一页误判成商详，需重新校准",
                texts.any { it.contains("领取补贴购买") }
            )
        }
        assertTrue(PriceNodeMatcher.isBuyNowAction("领取补贴购买"))
        assertTrue("真机底栏整串是「国补后¥9399 领取补贴购买」，含价格前缀也要认",
            PriceNodeMatcher.isBuyNowAction("国补后¥9399 领取补贴购买"))
    }

    // ---------- 诊断辅助（只读，不参与判定） ----------

    /** 门控逐项判据：isProductPage 的每个分支都摊开打印，假阳性时直接看出卡在哪一关 */
    private fun gateSignals(root: NodeSnapshot): String {
        val texts = dumpTexts(root)
        return "texts=${texts.size}" +
            " checkoutVeto=" + texts.any { PriceNodeMatcher.isCheckoutContext(it) } +
            " buyAction=" + texts.any { PriceNodeMatcher.isBuyAction(it) } +
            " pddPair=" + (texts.any { PriceNodeMatcher.hasPddSingleBuy(it) } &&
                texts.any { PriceNodeMatcher.hasPddGroupBuy(it) }) +
            " detailSection=" + texts.any { PriceNodeMatcher.isDetailSection(it) } +
            " buyNow=" + texts.any { PriceNodeMatcher.isBuyNowAction(it) }
    }

    private fun extractionReport(root: NodeSnapshot): String {
        val price = extractPriceHit(root, ShopPlatform.JD)
        val title = extractTitle(root, ShopPlatform.JD)
        return "price=${price?.let { "${it.value}/『${it.rawText}』knownId=${it.viaKnownId}" } ?: "null"}" +
            " title=${title?.let { "『${it.text.take(30)}』knownId=${it.viaKnownId} viaCd=${it.viaContentDescription}" } ?: "null"}" +
            " itemId=${extractItemId(root) ?: "null"}"
    }

    /**
     * 复刻 `PriceMonitorService.onAccessibilityEvent` 的调用顺序（第 57-93 行）：
     * 先门控，门控不过**直接 return**，价格/标题/ID 一次都不取，也就不会 emit。
     * 服务本身依赖 android.* 不可在 JVM 单测里实例化，这里把"门控在前"这条链路抽成纯函数来断言。
     */
    private fun overlayPayloadOf(root: NodeSnapshot, platform: ShopPlatform): Pair<Double, String>? {
        if (!isProductPage(root, platform)) return null
        val price = extractPriceHit(root, platform) ?: return null
        return price.value to (extractTitle(root, platform)?.text ?: "")
    }

    private fun flatDump(childCount: Int): String {
        val sb = StringBuilder(HEADER_XML)
        sb.append("<node index=\"0\" text=\"根\" resource-id=\"\" class=\"android.widget.FrameLayout\" ")
            .append("content-desc=\"\" clickable=\"false\">")
        repeat(childCount) { i ->
            sb.append("<node index=\"").append(i).append("\" text=\"条目").append(i)
                .append("\" resource-id=\"\" class=\"android.widget.TextView\" ")
                .append("content-desc=\"\" clickable=\"false\" />")
        }
        sb.append("</node></hierarchy>")
        return sb.toString()
    }

    private fun chainDump(depth: Int): String {
        val sb = StringBuilder(HEADER_XML)
        repeat(depth) { i ->
            sb.append("<node index=\"0\" text=\"层").append(i)
                .append("\" resource-id=\"\" class=\"android.widget.FrameLayout\" content-desc=\"\" ")
                .append("clickable=\"false\">")
        }
        repeat(depth) { sb.append("</node>") }
        sb.append("</hierarchy>")
        return sb.toString()
    }

    private companion object {
        const val HOME_DUMP = "jd_home_20260929.xml"
        const val SEARCH_DUMP = "jd_search_20260929.xml"
        const val HEADER_XML = "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\">"
    }
}
