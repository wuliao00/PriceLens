package com.pricelens.coupon.adapters

import com.pricelens.accessibility.container
import com.pricelens.accessibility.jdDetailPage
import com.pricelens.accessibility.leaf
import com.pricelens.accessibility.loadRealDump
import com.pricelens.accessibility.pageWithOnlyRawTexts
import com.pricelens.accessibility.rect
import com.pricelens.coupon.model.CouponState
import com.pricelens.coupon.slots.CouponVocabulary
import com.pricelens.coupon.slots.StateWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 病根①在**无障碍入口**上的形态：金额碎片化（`满` `199` `减` `50` 是四个兄弟节点）。
 *
 * 拼行只用两种相对关系（y 区间真重叠、间隙 ≤ 较大字高），一个绝对像素阈值都没有。
 * 几何判据必须用真机正/负样本钉死，所以每条都同时断言
 * "该拼的拼回了 `¥19.5`"与"不该拼的 `省1元` 没被拼进来"——
 * 只断言拒绝分支的量具会把"静默没有"读成"正确拒绝"。
 */
class NodeAdapterTest {

    /**
     * 正例与反例放进同一个父节点：这样"没拼"只可能是几何判出来的，
     * 而不是因为它们本来就不在同一批候选里。bounds 逐字抄自
     * `jd_home_20260929.xml`（¥ 右下 935,1313 / 19.5 左上 935,1268 / 省1元 900,1235 与 972,1269）。
     */
    @Test
    fun `同一父节点里 y 重叠的碎片拼成一句而 y 不重叠的不拼`() {
        val tree = container(
            kids = arrayOf(
                leaf(text = "省1元", bounds = rect(900, 1235, 972, 1269)),
                leaf(text = "¥", bounds = rect(916, 1279, 935, 1313)),
                leaf(text = "19.5", bounds = rect(935, 1268, 1006, 1316))
            )
        )
        val clauses = NodeAdapter.clauses(tree)
        assertEquals(listOf("省1元", "¥19.5"), clauses.map { it.text })
        // 反例：任何"给 y 加容差"的改动都会把省1元拽进同一行，产出下面这两种句子
        assertTrue(clauses.none { it.text.contains("省1元") && it.text.contains("19.5") })
        // 正例的另一半：省1元 必须作为自己那一条出现，否则"没拼"等于"没找到"
        assertEquals("省1元", clauses[0].text)
        assertEquals(listOf(0), clauses[0].nodePath)
        assertEquals(listOf(1), clauses[1].nodePath)
        assertTrue(clauses.all { it.nodePath.isNotEmpty() })
    }

    /** y 区间判定单独钉一条：两行 x 完全对齐、只差垂直不重叠 ⇒ 不许拼（这条正是"给 y 加容差"的变异会红的地方） */
    @Test
    fun `y 不重叠的两行即使 x 对齐也不拼`() {
        val twoRows = container(
            kids = arrayOf(
                leaf(text = "满", bounds = rect(0, 0, 20, 40)),
                leaf(text = "199", bounds = rect(20, 0, 60, 40)),
                leaf(text = "领", bounds = rect(0, 60, 20, 100)),
                leaf(text = "50", bounds = rect(20, 60, 60, 100))
            )
        )
        assertEquals(listOf("满199", "领50"), NodeAdapter.clauses(twoRows).map { it.text })
    }

    @Test
    fun `真机首页整棵树里分体价格拼回 ¥195 且省1元没有被并进来`() {
        val dump = loadRealDump("jd_home_20260929.xml")
        val texts = NodeAdapter.clauses(dump.root).map { it.text }
        assertTrue("分体价格没拼回 ¥19.5，实际产出=$texts", texts.contains("¥19.5"))
        assertTrue("省1元 被静默丢掉了（它必须作为自己那一条出现），实际产出=$texts", texts.contains("省1元"))
        assertTrue(texts.none { it.contains("省1元") && (it.contains("19.5") || it.contains("¥")) })
    }

    /** 整句（自带金额）是拼行断点：这是"领后把划线价判成到手价"那类假阳性的闸 */
    @Test
    fun `同一行里的两个整句不拼成一句`() {
        val tree = container(
            kids = arrayOf(
                leaf(text = "到手199", bounds = rect(0, 100, 100, 140)),
                leaf(text = "¥13199", bounds = rect(100, 100, 220, 140))
            )
        )
        assertEquals(listOf("到手199", "¥13199"), NodeAdapter.clauses(tree).map { it.text })
    }

    /** 碎片长度上限是**字符数**判据（12），不是几何阈值 */
    @Test
    fun `十五字的整句券文案不与邻居拼`() {
        val tree = container(
            kids = arrayOf(
                leaf(text = "点击领取", bounds = rect(0, 100, 80, 140)),
                leaf(text = "¥70无门槛立减券", bounds = rect(80, 100, 200, 140))
            )
        )
        val texts = NodeAdapter.clauses(tree).map { it.text }
        assertEquals(listOf("点击领取", "¥70无门槛立减券"), texts)
        // 正例对照：把整句换成碎片（同样相邻、同样重叠），拼接这条通道是活的
        val fragments = container(
            kids = arrayOf(
                leaf(text = "领", bounds = rect(0, 100, 20, 140)),
                leaf(text = "50", bounds = rect(20, 100, 60, 140)),
                leaf(text = "元券", bounds = rect(60, 100, 100, 140))
            )
        )
        assertEquals(listOf("领50元券"), NodeAdapter.clauses(fragments).map { it.text })
    }

    /**
     * bounds 缺失（虚拟节点 / 部分 ROM）时不许静默丢文本：
     * 没有几何就退回**同一父节点的兄弟文档顺序**当一行，整句断点仍然生效。
     */
    @Test
    fun `没有坐标的碎片按树序拼回一句并且不跨越整句断点`() {
        val noBounds = container(
            kids = arrayOf(
                leaf(text = "满"),
                leaf(text = "199"),
                leaf(text = "减"),
                leaf(text = "50")
            )
        )
        val clauses = NodeAdapter.clauses(noBounds)
        assertEquals(listOf("满199减50"), clauses.map { it.text })
        assertEquals(listOf(0), clauses[0].nodePath)
        // 反例：同一父节点里的三个整句不能因为"没有坐标"就被拼成一句（也不能被丢掉）
        val sentences = container(kids = arrayOf(leaf(text = "¥13199"), leaf(text = "国补领后价"), leaf(text = "¥14699")))
        assertEquals(listOf("¥13199", "国补领后价", "¥14699"), NodeAdapter.clauses(sentences).map { it.text })
    }

    @Test
    fun `祖先文案与同层按钮都进上下文但券自己那行赢`() {
        val popup = loadRealDump("jd_detail_guobu_popup_plb110_20261003.xml")
        val coupon = NodeAdapter.clauses(popup.root).first { it.text.contains("可减1500") }
        assertEquals("当前地区可领,本单可减1500元", coupon.text)
        // 同层"立即领取"按钮是真机券弹层的兄弟节点，必须带进上下文（状态/范围判定要看它）
        assertTrue(coupon.ancestors.contains("立即领取"))
        assertTrue(coupon.nodePath.isNotEmpty())
        // 券自己那行写着"当前地区"，外层的"领取"不许把它判成能领
        assertEquals(CouponState.REGION_LIMITED, StateWords.of(coupon.text, coupon.ancestors))
        assertNotEquals(CouponState.CLAIMABLE, StateWords.of(coupon.text, coupon.ancestors))
    }

    /** 候选闸的另一半：resource-id 带 coupon/promotion 语义时没有券词也进候选 */
    @Test
    fun `resource-id 语义能单独把节点带进候选而无关 id 不能`() {
        val byId = container(kids = arrayOf(leaf(text = "1500", res = "com.jingdong.app.mall:id/coupon_amount")))
        assertEquals(listOf("1500"), NodeAdapter.clauses(byId).map { it.text })
        // 反例：混淆短 id（真机绝大多数）没有语义线索，纯数字也不带货币符号 ⇒ 不进候选
        val plain = container(kids = arrayOf(leaf(text = "1500", res = "com.jingdong.app.mall:id/dme")))
        assertTrue(NodeAdapter.clauses(plain).isEmpty())
    }

    /** 同一个叶子被父节点与祖父节点各看见一次时只出一个结果（否则一张券抽两遍） */
    @Test
    fun `下沉一层看到的叶子不会被父子两层各抽一遍`() {
        val nested = container(kids = arrayOf(container(kids = arrayOf(leaf(text = "满199减50")))))
        val clauses = NodeAdapter.clauses(nested)
        assertEquals(1, clauses.size)
        assertEquals("满199减50", clauses[0].text)
        assertEquals(listOf(0, 0), clauses[0].nodePath)
    }

    @Test
    fun `平台来自 resource-id 的宿主前缀并且能由词表换掉`() {
        assertEquals("jd", NodeAdapter.platform(jdDetailPage()))
        // 反例：全树没有语义 id 时判不出 ⇒ unknown，不猜
        assertEquals("unknown", NodeAdapter.platform(pageWithOnlyRawTexts()))
        val newcomer = container(kids = arrayOf(leaf(text = "券", res = "com.walimmart.app:id/coupon_entry")))
        assertEquals("unknown", NodeAdapter.platform(newcomer))
        // 正例对照：接新宿主是"加一条前缀"而不是发版（前缀表在词表里，不在 Kotlin 常量里）
        val base = CouponVocabulary.DEFAULT
        val extended = base.copy(platformPrefixes = base.platformPrefixes + ("com.walimmart" to "hema"))
        assertEquals("hema", NodeAdapter.platform(newcomer, extended))
    }
}
