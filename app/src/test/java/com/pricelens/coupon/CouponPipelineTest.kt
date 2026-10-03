package com.pricelens.coupon

import com.pricelens.coupon.model.AmountRole
import com.pricelens.coupon.model.CouponScope
import com.pricelens.coupon.model.CouponSlot
import com.pricelens.coupon.model.CouponState
import com.pricelens.coupon.normalize.Clause
import com.pricelens.coupon.rules.CouponTemplates
import com.pricelens.coupon.slots.CouponVocabulary
import com.pricelens.coupon.slots.WordRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抽取管线（**一个数字进哪个槽**在这里定，适配器只管把句子喂进来）。
 *
 * 这里刻意绕开三个适配器直接喂 [Clause]，这样"槽位判定"与"句子怎么来"两件事的红绿
 * 不会互相掩盖。病根①（槽位混淆）与病根③（多券混并）各有一组正反例。
 */
class CouponPipelineTest {

    private fun consume(
        text: String,
        ancestors: List<String> = emptyList(),
        vocabulary: CouponVocabulary = CouponVocabulary.DEFAULT
    ): CouponPipeline.ClauseOutcome = CouponPipeline.consume(
        clause = Clause(text = text, ancestors = ancestors),
        templates = CouponTemplates.builtin(),
        vocabulary = vocabulary
    )

    /**
     * "这句应当正好抽出一张券"的取法。**不用 `slots.single()`**：
     * single() 失败时只说 "List has more than one element"，看不出多出来的是哪一张，
     * 每次都要再改一遍测试才能定位。这里把实际产出写进断言消息，一次红就能看清。
     */
    private fun oneSlot(outcome: CouponPipeline.ClauseOutcome): CouponSlot {
        assertEquals("期望恰好一张券，实际=${outcome.slots}", 1, outcome.slots.size)
        return outcome.slots[0]
    }

    private fun oneSlot(text: String, ancestors: List<String> = emptyList()): CouponSlot = oneSlot(consume(text, ancestors))

    @Test
    fun `券金额与商品价格分属两个类型`() {
        val outcome = consume("原价5499 到手3999 降了1500")
        assertEquals(3999.0, outcome.price.finalPrice!!, 0.0)
        assertEquals(5499.0, outcome.price.listPrice!!, 0.0)
        assertEquals(1500.0, outcome.price.drop!!, 0.0)
        // 反例：三个数字一个都不许进券槽位（"到手价被当券面额"从类型上就该不可能）
        assertTrue(outcome.slots.isEmpty())
        // 正例对照：同一把尺子在真有券词时必须出券，而且价格槽位反过来留空
        val coupon = oneSlot("满199减50")
        assertEquals(50.0, coupon.discount!!, 0.0)
        assertEquals(199.0, coupon.threshold!!, 0.0)
        assertNull(consume("满199减50").price.finalPrice)
    }

    @Test
    fun `一句三张券拆开并且每张的范围各判各的`() {
        val outcome = consume("店铺券满199减20，品类券满300减50，平台券满500减100")
        assertEquals(3, outcome.slots.size)
        assertEquals(listOf(20.0, 50.0, 100.0), outcome.slots.map { it.discount })
        assertEquals(listOf(199.0, 300.0, 500.0), outcome.slots.map { it.threshold })
        // 按整句判范围的话三张都是 SHOP —— 范围错的券和没拆开的券一样会误导"能不能叠"
        assertEquals(listOf(CouponScope.SHOP, CouponScope.CATEGORY, CouponScope.PLATFORM), outcome.slots.map { it.scope })
        // 正例对照：范围词写在数字后面时，段里判不出要退回整句判定（新逻辑不许把单张券判成 UNKNOWN）
        val tailScope = oneSlot("满199减20的店铺券")
        assertEquals(CouponScope.SHOP, tailScope.scope)
        assertEquals(20.0, tailScope.discount!!, 0.0)
    }

    @Test
    fun `模板没覆盖的数字由上下文词判角色`() {
        // 千分位：man-jian-pair 的 `[\d.]+` 撞不上逗号，但 `满`/`减` 两个词各自还在
        val outcome = consume("满4,999减300")
        val coupon = oneSlot(outcome)
        assertEquals(4999.0, coupon.threshold!!, 0.0)
        assertEquals(300.0, coupon.discount!!, 0.0)
        // 没有模板命中却有券 ⇒ 兜底置信（宁低不高，但不丢弃）
        assertEquals(CouponTemplates.CONTEXT_ONLY_CONFIDENCE, outcome.confidence, 0.0001)
        // 正例对照：去掉千分位写法就命中模板，置信是模板自己的 0.95
        assertEquals(0.95, consume("满4999减300").confidence, 0.0001)
    }

    @Test
    fun `分期与免息句子里的数字一律不采信`() {
        assertTrue(consume("24期免息 满1000减100").slots.isEmpty())
        // 京豆/销量/库存是本包按真机补的增量，不在 accessibility 那五个词里
        assertTrue(consume("最高返659京豆 满1000减100").slots.isEmpty())
        assertTrue(CouponVocabulary.DEFAULT.excludedNumberWords.containsAll(listOf("京豆", "销量", "库存", "分期", "免息")))
        // 正例对照：同一句券文案摘掉排除词就该出一张券（证明不是"永远不出券"）
        assertEquals(1, consume("满1000减100").slots.size)
        assertEquals(100.0, oneSlot("满1000减100").discount!!, 0.0)
    }

    @Test
    fun `判不出角色就不产生槽位而低置信也不丢弃`() {
        // `编号` 不是任何角色词 ⇒ 这个数字哪儿都不去
        val nothing = consume("编号1500")
        assertTrue(nothing.slots.isEmpty())
        assertNull(nothing.price.finalPrice)
        assertEquals(0.0, nothing.confidence, 0.0)
        // 只有门槛、没写面额：有券证据时出一张 discount=null 的券
        val thresholdOnly = oneSlot("满299可用")
        assertNull(thresholdOnly.discount)
        assertEquals(299.0, thresholdOnly.threshold!!, 0.0)
        // 正例对照：低置信（兜底 0.5）的券留在结果里，丢弃是"无核验直出"的另一种形状
        assertEquals(CouponTemplates.CONTEXT_ONLY_CONFIDENCE, consume("满299可用").confidence, 0.0001)
        assertTrue(consume("领券").slots.isEmpty())
    }

    @Test
    fun `日期只认写明年月的形态不猜年份`() {
        val coupon = oneSlot("2026-10-08到期，店铺券满199减20 券码：ABCD1234")
        assertEquals("2026-10-08", coupon.expiry)
        assertEquals("ABCD1234", coupon.code)
        // 反例：只写"10月8日"时补 2026 还是 2027 都是编造 ⇒ null
        assertNull(oneSlot("10月8日到期，满199减20").expiry)
        assertEquals("2026-10-08", CouponPipeline.expiryOf("2026年10月8日"))
        assertNull(CouponPipeline.expiryOf("2026-13-45"))
        assertNull(CouponPipeline.expiryOf("到手19元"))
    }

    @Test
    fun `口令与短链只当字符串带进 url 字段不展开`() {
        assertEquals("￥K8Z3c1Abc￥", oneSlot("￥K8Z3c1Abc￥ 满199减20").url)
        // 反例：没有链接就是 null，不拿商品名凑一个"看起来像 ID"的串
        assertNull(oneSlot("满199减20").url)
    }

    @Test
    fun `每张券都带着抽出它的那句原文`() {
        // 这条钉的是**下游能不能复盘**：展示层要说"这句里读出来的"，
        // 本地错例导出（一键"这条不对"）要写成与 tools/golden/coupons.jsonl 同格式的 JSONL，
        // 而那份格式的第一字段就是原文。少了 sourceText，用户纠错回灌不进评测集。
        val outcome = consume("店铺券满199减20，品类券满300减50")
        assertEquals(2, outcome.slots.size)
        assertEquals(
            listOf("店铺券满199减20", "品类券满300减50"),
            outcome.slots.map { it.sourceText }
        )
        // 反例：段首那个「，」是上一张券留下的分隔符，留着它展示层会出现半截句
        assertTrue(outcome.slots.none { it.sourceText.startsWith("，") || it.sourceText.startsWith(",") })
        // 正例对照：单张券时证据句就是整句（不许因为"分段"逻辑把单张券的句子切坏）
        assertEquals("满199减50", oneSlot("满199减50").sourceText)
    }

    @Test
    fun `角色词不许认领隔着一段话的数字`() {
        // 真机形态：券文案后面跟着"券码 / 口令 / 编号"。1234 前面二十多字有个 `减`，
        // 不限量"词与数字之间隔了多远"时它会被判成 DISCOUNT ⇒ 凭空多出一张 ¥1234 的券，
        // 而且带着**正确的** scope/state 一起出现，比少抽一张券难发现得多。
        val outcome = consume("2026-10-08到期，店铺券满199减20 券码：ABCD1234")
        assertEquals("只该有一张券，实际=${outcome.slots.map { it.discount to it.threshold }}", 1, outcome.slots.size)
        assertEquals(20.0, oneSlot(outcome).discount!!, 0.0)
        // 正例对照：只隔"不构成新语义"的字符时照常认领 —— 空白、货币符号、了、价
        assertEquals(50.0, oneSlot("满 199 减 50").discount!!, 0.0)
        assertEquals(1500.0, consume("原价5499 降了1500").price.drop!!, 0.0)
        assertEquals(92.9, consume("到手价¥92.9").price.finalPrice!!, 0.0001)
    }

    @Test
    fun `状态与范围都按词表判并且祖先能补位`() {
        val claimed = oneSlot("已领完的店铺券满199减20")
        assertEquals(CouponState.SOLD_OUT, claimed.state)
        assertEquals(CouponScope.SHOP, claimed.scope)
        // 券行自己没说状态、同层按钮说了 ⇒ 用上下文（NodeAdapter 把兄弟文案放进 ancestors）
        assertEquals(CouponState.CLAIMABLE, oneSlot("满199减20", ancestors = listOf("立即领取")).state)
        // 反例：两处都没有就保持 UNKNOWN，不许默认 CLAIMABLE / SHOP
        assertEquals(CouponState.UNKNOWN, oneSlot("满199减20").state)
        assertEquals(CouponScope.UNKNOWN, oneSlot("满199减20").scope)
    }

    @Test
    fun `角色词表换成远端版本就必须换判定结果`() {
        // 出厂词表里没有"抵现"这个角色词（也没有模板认识它）⇒ 判不出角色 ⇒ 不产生券
        assertTrue(consume("新客抵现100元").slots.isEmpty())
        val base = CouponVocabulary.DEFAULT
        val discountWords = base.amountRoles.getValue(AmountRole.DISCOUNT) + WordRule.literal("抵现")
        val extended = base.copy(amountRoles = base.amountRoles + (AmountRole.DISCOUNT to discountWords))
        assertEquals(100.0, consume("新客抵现100元", vocabulary = extended).slots.single().discount!!, 0.0)
        // 反例：换词表不影响别的角色（`到手` 由模板声明，依然进价格槽、不进券槽）
        assertTrue(consume("到手100元", vocabulary = extended).slots.isEmpty())
    }
}
