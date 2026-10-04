package com.pricelens.coupon

import com.pricelens.accessibility.container
import com.pricelens.accessibility.leaf
import com.pricelens.accessibility.loadRealDump
import com.pricelens.accessibility.rect
import com.pricelens.coupon.model.CouponScope
import com.pricelens.coupon.model.CouponState
import com.pricelens.coupon.model.ExtractSource
import com.pricelens.coupon.model.ExtractorKind
import com.pricelens.coupon.model.PriceSlots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 门面：三个入口 → 同一个 [com.pricelens.coupon.model.Extraction]。
 *
 * 这里钉的是"统一流水线"是否真的成立 —— 三入口拿到**同一句券文案**（甚至节点树里是被拆碎的
 * 同一句）时，抽出的槽位必须一致；不一致就说明某个入口仍在走自己的小九九。
 * 每条判据都同时断言"该出券的出了券"和"该留空的地方留空"。
 */
class CouponExtractorTest {

    private val couponCopy = "领券满4999减300"

    private fun pageOf(vararg texts: String) = container(
        kids = texts.mapIndexed { index, text -> leaf(text = text, bounds = rect(index * 20, 0, index * 20 + 20, 40)) }.toTypedArray()
    )

    @Test
    fun `三入口同一句券文案抽出同一张券`() {
        val clipboard = CouponExtractor.fromClipboard(couponCopy)
        val post = CouponExtractor.fromPost(couponCopy, ageDays = 0)
        val page = CouponExtractor.fromPage(container(kids = arrayOf(leaf(text = couponCopy))))
        for (extraction in listOf(clipboard, post, page)) {
            val coupon = extraction.coupons.single()
            assertEquals(300.0, coupon.discount!!, 0.0001)
            assertEquals(4999.0, coupon.threshold!!, 0.0001)
            assertEquals(CouponScope.UNKNOWN, coupon.scope)
            assertEquals(CouponState.UNKNOWN, coupon.state)
            assertNull(coupon.expiry)
            assertNull(coupon.code)
            assertNull(coupon.url)
            // 三个数字一个都没漏进价格槽：券面额不是"到手价"
            assertEquals(PriceSlots(null, null, null), extraction.price)
            assertEquals(ExtractorKind.RULE, extraction.extractor)
        }
    }

    /** 同一句文案在树里被拆成 6 个兄弟节点时，拼行之后抽出的还是同一张券 */
    @Test
    fun `无障碍入口的碎片拼回一句后与文本入口抽出同一张券`() {
        val fragmented = CouponExtractor.fromPage(pageOf("领", "券", "满", "4999", "减", "300"))
        val clipboard = CouponExtractor.fromClipboard(couponCopy)
        assertEquals(clipboard.coupons.single().discount!!, fragmented.coupons.single().discount!!, 0.0001)
        assertEquals(clipboard.coupons.single().threshold!!, fragmented.coupons.single().threshold!!, 0.0001)
    }

    @Test
    fun `nodePath 只有商品页入口非空`() {
        val clipboard = CouponExtractor.fromClipboard(couponCopy)
        val post = CouponExtractor.fromPost(couponCopy, ageDays = 0)
        val page = CouponExtractor.fromPage(container(kids = arrayOf(leaf(text = couponCopy))))
        assertEquals(ExtractSource.CLIPBOARD, clipboard.source)
        assertEquals(ExtractSource.COMMUNITY, post.source)
        assertEquals(ExtractSource.PAGE_NODE, page.source)
        // 核验层要沿这条链回点复探，所以它必须随槽位一起产出；文本入口恒为空表
        assertEquals(listOf(0), page.coupons.single().nodePath)
        assertTrue(clipboard.coupons.single().nodePath.isEmpty())
        assertTrue(post.coupons.single().nodePath.isEmpty())
        val fragmented = CouponExtractor.fromPage(pageOf("领", "券", "满", "4999", "减", "300"))
        assertEquals(listOf(0), fragmented.coupons.single().nodePath)
    }

    @Test
    fun `置信是模板置信乘来源可靠度乘时效衰减`() {
        assertEquals(0.95, CouponExtractor.fromClipboard(couponCopy).confidence, 0.0001)
        assertEquals(0.9025, CouponExtractor.fromPage(container(kids = arrayOf(leaf(text = couponCopy)))).confidence, 0.0001)
        assertEquals(0.76, CouponExtractor.fromPost(couponCopy, ageDays = 0).confidence, 0.0001)
        // 正例对照：>30 天的帖子按 0.5 衰减，但券留在结果里（丢弃是"无核验直出"的另一种形状）
        val stale = CouponExtractor.fromPost(couponCopy, ageDays = 60)
        assertEquals(0.38, stale.confidence, 0.0001)
        assertEquals(1, stale.coupons.size)
        assertTrue(stale.stackNote!!.contains("历史信息"))
        // clamp 两端：没有证据就是 0，任何入口都不许越过 1
        assertEquals(0.0, CouponExtractor.fromClipboard("今天天气不错").confidence, 0.0)
        val clipboardConfidence = CouponExtractor.fromClipboard(couponCopy).confidence
        assertTrue(clipboardConfidence in 0.0..1.0)
    }

    @Test
    fun `三档边界是闭区间下界`() {
        // 0.85 归 HIGH、0.60 归 MEDIUM：写成 `>` 会把 0.9×0.95 这类正常命中降一档
        assertEquals(Tier.HIGH, Tiers.of(0.85))
        assertEquals(Tier.MEDIUM, Tiers.of(0.8499))
        assertEquals(Tier.MEDIUM, Tiers.of(0.6))
        assertEquals(Tier.LOW, Tiers.of(0.5999))
        assertEquals(Tier.HIGH, Tiers.of(1.0))
        assertEquals(Tier.LOW, Tiers.of(0.0))
    }

    @Test
    fun `真机券弹层抽出限地区的1500券而不把划线价当券`() {
        val extraction = CouponExtractor.fromPage(loadRealDump("jd_detail_guobu_popup_plb110_20261003.xml").root)
        // 这棵树里**有两处 1500**：弹层里那句「当前地区可领，本单可减1500元」，
        // 以及绿色国补条那句「领后减¥1500 立即领」。两张都是真存在的表述，状态本来就不一样
        // （前者限地区、后者按钮写着立即领）⇒ **不许用 `first { discount == 1500.0 }`**，
        // 那等于把"取到哪张"交给遍历顺序，节点顺序一变就随机红/随机绿。按原文选。
        val coupon = extraction.coupons.single { it.sourceText.contains("当前地区") }
        assertEquals(CouponState.REGION_LIMITED, coupon.state)
        assertNull(coupon.threshold)
        assertTrue(coupon.nodePath.isNotEmpty())
        // 另一张 1500（「领后减¥1500 立即领」）判成可领是**对的**，这里把它钉住，
        // 免得下次有人为了过上面那条断言把整棵树的状态判成同一个值。
        // 用 any 而不是 single：证据段里带不带按钮文案取决于拼行细节，那种细节会变；
        // 这条要守住的只是"限地区那张不许把可领那张一起改掉"。
        val states = extraction.coupons.map { it.state }
        assertTrue("树里应至少有一张 CLAIMABLE 的券，实际状态=$states", extraction.coupons.any { it.state == CouponState.CLAIMABLE })
        // 反例：同一棵树里的 ¥13199 / ¥14699 是页面价与划线价，一个都不许进券槽位
        assertTrue(extraction.coupons.none { it.discount == 13199.0 || it.discount == 14699.0 })
        assertEquals(ExtractSource.PAGE_NODE, extraction.source)
    }

    @Test
    fun `到手价在社区帖入口只进价格槽`() {
        val post = CouponExtractor.fromPost("百亿补贴到手19元", ageDays = 0)
        assertEquals(19.0, post.price.finalPrice!!, 0.0001)
        assertTrue(post.coupons.isEmpty())
        // 正例对照：同一入口换成券文案就出券（"不出券"不是判型把路判死了）
        assertTrue(CouponExtractor.fromPost("领券满199减50", ageDays = 0).coupons.isNotEmpty())
    }

    @Test
    fun `剪贴板分享文案带上平台与口令而不碰网络`() {
        val extraction = CouponExtractor.fromClipboard("【￥K8Z3c1Abc￥】m.tb.cn/h.GqXbK1 领券满199减50")
        assertEquals("taobao", extraction.platform)
        val coupon = extraction.coupons.single()
        assertEquals("￥K8Z3c1Abc￥", coupon.url)
        assertEquals(50.0, coupon.discount!!, 0.0001)
        assertEquals(199.0, coupon.threshold!!, 0.0001)
        // 反例：没有链接线索时是 unknown，不猜一个宿主
        assertEquals("unknown", CouponExtractor.fromClipboard("满199减50").platform)
        assertNull(CouponExtractor.fromClipboard("满199减50").coupons.single().url)
    }

    /**
     * #61 的端到端一半：**真机 dump 的整棵树**走 `fromPage`。
     *
     * 为什么必须用真树而不是合成树：评测集里 page_node 那 26 条的 `raw` 是单条节点文案，
     * 从这里只能证明"给到这句话读得对"，证不了"能不能从一棵真实树里把这句话挑出来"——
     * 而后者才是用户看到的"入口几乎不可能出券"。这两件事在 LlmProbeReceiver 的注释里也写明了分工。
     */
    @Test
    fun `真机首页树里角标券被挑出来`() {
        val extraction = CouponExtractor.fromPage(loadRealDump("jd_home_20260929.xml").root)
        val keys = extraction.coupons.map { Pair(it.discount, it.threshold) }
        assertTrue("「17元外卖餐补」这条角标必须成券，实际=$keys", keys.contains(Pair(17.0, null)))
        // 「试用专享券」这类"有券名没金额"的节点：留一张全 null 券，而不是静默没有
        assertTrue("首页角标「试用专享券」要留一张空券，实际=$keys", keys.contains(Pair(null, null)))
    }

    @Test
    fun `真机商详底栏的领券按钮留成一张空券`() {
        val extraction = CouponExtractor.fromPage(loadRealDump("jd_detail_instock_20260929.xml").root)
        val nullSlots = extraction.coupons.filter { it.discount == null && it.threshold == null }
        assertTrue("底栏「领券」该产一张全 null 券（页面确有可领券），实际=${extraction.coupons}", nullSlots.isNotEmpty())
        // 证据句是那张券自己那一段，不许是空串（空串在展示层会被读成"这句没内容"）
        assertTrue("空券也要带得出证据句", nullSlots.all { it.sourceText.isNotBlank() })
    }

    /** #61 的加法合并：两路读到同一张券只算一张，另一路独有的必须补进来 */
    @Test
    fun `两路输入做加法而重复的券只算一张`() {
        val tree = CouponExtractor.fromPage(pageOf("满199减50"))
        val text = CouponExtractor.fromClipboard("满199减50；17元外卖餐补")
        val merged = CouponExtractor.merge(tree, text)
        val keys = merged.coupons.map { Pair(it.discount, it.threshold) }
        assertEquals("同面额同门槛不许出现两遍，实际=$keys", 2, keys.size)
        assertTrue(keys.contains(Pair(50.0, 199.0)))
        assertTrue("关键词那一路独有的 17 元不许被树换掉，实际=$keys", keys.contains(Pair(17.0, null)))
        // 补进来的那张保留**它自己的**证据句（出处逐行可追，不是合并后才编的）
        assertEquals("17元外卖餐补", merged.coupons[1].sourceText)
        // 置信取较大值：档位说的是"这一组里最强的一条"，不是平均数
        assertEquals(maxOf(tree.confidence, text.confidence), merged.confidence, 0.0001)
        // 主路一张都不许丢
        assertEquals(tree.coupons[0], merged.coupons[0])
    }

    @Test
    fun `合并不拿次路的价格槽覆盖主路已读出的价格`() {
        // 一行里同时有价格词与券词才会成为券的一路输入（`NodeAdapter` 的候选闸）：
        // 纯价格行「原价5499」单独成行时**不归找券这条链读**（那是浮窗价格链路的事）
        val tree = CouponExtractor.fromPage(pageOf("原价5499 满199减50"))
        val text = CouponExtractor.fromClipboard("到手3999 满199减50")
        val merged = CouponExtractor.merge(tree, text)
        assertEquals(5499.0, merged.price.listPrice!!, 0.0001)
        // 主路没有的槽位才由次路补：这就是"只填不盖"
        assertEquals(3999.0, merged.price.finalPrice!!, 0.0001)
        assertEquals(1, merged.coupons.size)
    }
}
