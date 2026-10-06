package com.pricelens.coupon

import com.pricelens.coupon.adapters.PostAdapter
import com.pricelens.coupon.adapters.PostKind
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
        // 京豆/库存/淘金币是本包按真机补的整句增量，不在 accessibility 那五个词里
        assertTrue(consume("最高返659京豆 满1000减100").slots.isEmpty())
        assertTrue(CouponVocabulary.DEFAULT.excludedNumberWords.containsAll(listOf("京豆", "库存", "淘金币", "分期", "免息")))
        // 「销量」不在整句那一档：京东首页的 content-desc 一条里同时有真价与销量角标，
        // 整句作废会连价一起吃掉（评测集 jd-home-04）。它走**邻接作废**那张表。
        assertTrue("「销量」必须已经离开整句作废那张表", "销量" !in CouponVocabulary.DEFAULT.excludedNumberWords)
        assertTrue(CouponVocabulary.DEFAULT.counterNumberWords.contains("销量"))
        assertEquals(1579.9, consume("人民币1579.90 入会到手价 销量3万+").price.finalPrice!!, 0.0)
        // 正例对照：整句作废那一档没被削弱 —— 同一句券文案摘掉排除词才出一张券
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
        // 「领券」到这里**不再是空**（2026-10-04 改）：它以前被当成本条断言的例子，
        // 可评测集把「领券」(jd-instock-02) 与「试用专享券」(jd-home-01) 标的是"有券、金额没写"的
        // 全 null 券 —— 两条一直是漏检。判据搬到 emptyCouponWords（整句无数字 + 券名词），
        // 正反例都在下面的专项用例里钉着。
        assertEquals(1, consume("领券").slots.size)
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
    fun `百分号后面的是费率不是金额而显式无门槛是零门槛`() {
        // 真机：`参与立减15%` 被抽成过一张 ¥15 的券 —— 误抽比漏抽贵，
        // 因为它带着正确的 scope/state 一起出现，看起来完全像真券。
        assertTrue("立减15% 不该产出券，实际=${consume("参与立减15%优惠活动").slots}", consume("参与立减15%优惠活动").slots.isEmpty())
        // 正例对照：同一个数字不带百分号就是面额（证明不是"把这条通道关死了"）
        assertEquals(15.0, oneSlot("立减15元").discount!!, 0.0)

        // 「无门槛」是**显式零门槛**：threshold 写 0.0，与"没提门槛"的 null 不是一回事
        val noThreshold = oneSlot("【点击领取】¥70无门槛立减券")
        assertEquals(70.0, noThreshold.discount!!, 0.0)
        assertEquals(0.0, noThreshold.threshold!!, 0.0)
        // 反例：没写「无门槛」的句子门槛仍是 null（不许被上面那条规则顺手改掉）
        assertNull(oneSlot("领50元券").threshold)
        assertEquals(199.0, oneSlot("满199减50").threshold!!, 0.0)

        // 「省」在真机国补页是面额的正字法（`PLUS会员等，本单含支付省¥134.99`），不是降幅
        assertEquals(134.99, oneSlot("PLUS会员等，本单含支付省¥134.99").discount!!, 0.0001)
        // 反例：隔着一段话的「省」不许再认领数字（glue 规则仍在）
        assertTrue(consume("国家补贴至高省15%，热度643.0万").slots.isEmpty())
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

    /**
     * 「数字在前、替身说法在后」（`17元外卖餐补`）。这类形状是 `AmountRole.of` 的**结构性盲区**：
     * 那把尺子只看数字之前的词，所以它归模板管，而不是往左侧词表里塞「餐补」。
     */
    @Test
    fun `数字在前的替身说法由模板认领成面额`() {
        // 正例：真机京东首页角标原文（评测集 jd-home-02，之前一直是漏检）
        val meal = oneSlot("17元外卖餐补")
        assertEquals(17.0, meal.discount!!, 0.0)
        assertNull(meal.threshold)
        // 证据句必须延伸到**角色词**那里（段原本只截到最后一个数字 ⇒ 显示层会写成"这句里读出来的：17"，
        // 用户既看不出抽对了也看不出抽错了。2026-10-05 接 #61 时撞出来的）
        assertEquals("17元外卖餐补", meal.sourceText)
        // 左侧「补贴」直接领数字：`国家补贴500元` 说的是确定减额（评测集 cm-youhui-06 漏的那张）
        assertEquals(500.0, oneSlot("国家补贴500元优惠活动").discount!!, 0.0)
        // 反例（PLB110 抓到的那条，逼出"确定性复核"的同一句）：5998 是**售价**，
        // 逗号切断间隙 ⇒ 模板不许跨标点认领；499 才是面额，两个价格数字留在价格槽
        val post = consume("天猫精选此款目前活动售价5998元，参与官方限时补贴减499元，实付低至4999元")
        assertEquals("期望一张券，实际=${post.slots}", 1, post.slots.size)
        assertEquals(499.0, post.slots[0].discount!!, 0.0)
        assertNull(post.slots[0].threshold)
        assertEquals(5998.0, post.price.listPrice!!, 0.0)
        // `实付低至4999元` 的 4999 现在是**到手价**（#68，2026-10-05）。此前它判不出角色：
        // 尺子允许的连接字是 `[\s¥￥了至到价]`，里面没有「低」，所以既不进券也不进价格槽。
        // 真值表（两条 smzdm 夹具里 9 句 `实付低至X元`）见下面那两条测试的注释。
        assertEquals(4999.0, post.price.finalPrice!!, 0.0)
        // 反例：`至高减500元 晒单返红包` 的 500 落在模板间隙里，但整句带「晒单」⇒ 一个数字都不采信
        val claim = consume("国家补贴 至高减500元 晒单返红包")
        assertTrue("晒单句一张券都不许产，实际=${claim.slots}", claim.slots.isEmpty())
    }

    /**
     * `实付低至X元` 是社区帖写到手价的**正字法**，不是罕见变体（2026-10-05 真值表）：
     * 夹具 `smzdm_faxian.html` / `smzdm_youhui.html` 里共 9 句，覆盖评测集
     * cm-youhui-01/02/03/04/06 与 cm-faxian-01/02/03/04 —— 判不出等于**每帖都少一个到手价**。
     *
     * 这条同时钉住三件容易各自做错的事：多句取**最早**那个数（cm-youhui-03 的 7.35，
     * 不是后面的 7.16，也不是「实付可低至2.9元以下」的 2.9）、标价照常、券一张都不许被挤掉。
     */
    @Test
    fun `社区帖里实付低至X元落进到手价槽，多句取最早那条`() {
        val post = CouponExtractor.fromPost(
            "口碑钢化膜，耐磨抗摔~天猫精选此款目前活动售价19.87元，下单领取满15减8元优惠券，下单1件，实付低至7.35元。" +
                "淘金币可抵扣0.19元，实付低至7.16元。今日有天猫app专属红包，满5.01减5，实付可低至2.9元以下，更有零元购。",
            0L
        )
        assertEquals(7.35, post.price.finalPrice!!, 0.0001)
        assertEquals(19.87, post.price.listPrice!!, 0.0001)
        assertEquals("补 FINAL 词不许把券挤掉，实际=${post.coupons.map { it.discount to it.threshold }}", 2, post.coupons.size)
    }

    /**
     * 数字紧跟「折」是**折扣率**不是金额（与 `%` 同一条费率闸）。
     * 真样本：`国庆出行好物低至5折` 是京东搜索页上的一个节点（夹具 jd_search_20260929.xml，评测集 jd-search-02）。
     * 这条是 `低至` 进 FINAL 词表**之后**才出现的风险：词表说"这数是到手价"，费率闸说"它连着折，不是金额"，
     * 少一道就会把"5 折"显示成"到手 ¥5"。正例对照放在最后，防止这条闸退化成"低至一律判不出"。
     */
    @Test
    fun `低至5折的 5 是折扣率不是到手价`() {
        val outcome = consume("国庆出行好物低至5折")
        assertNull("5折不许进到手价槽，实际=${outcome.price.finalPrice}", outcome.price.finalPrice)
        assertTrue("这句没有券，实际=${outcome.slots}", outcome.slots.isEmpty())
        assertEquals(4999.0, consume("下单1件，实付低至4999元").price.finalPrice!!, 0.0)
    }

    /** 「低」字面相似、但**不是**到手价词的两个真形状：比较句与「N天新低」角标 */
    @Test
    fun `低于上次爆料价与天新低角标都不许变成到手价`() {
        // 真样本 cm-faxian-05：到手价来自「当前到手价5499.00元」；5699 是**上一次爆料价**（比较句）
        val faxian = CouponExtractor.fromPost(
            "天猫商城该商品参加1件8.5折的促销活动，当前到手价5499.00元，降价前售价为5999.00元，本次降幅8%，低于上次爆料价5699.00元。",
            0L
        )
        assertEquals(5499.0, faxian.price.finalPrice!!, 0.0001)
        assertTrue("这句没有券，实际=${faxian.coupons}", faxian.coupons.isEmpty())
        // 比较句单拎出来判：`低于` 里那个 `低` 后面跟的是「于」，不是数字，`低至` 不许跨字命中
        assertNull(consume("低于上次爆料价5699.00元").price.finalPrice)
        // 角标 `995天新低`：数字在 `新低` **前面**，而判据只看数字左邻
        assertNull(consume("近995天新低").price.finalPrice)
    }

    /** 虚拟货币抵扣（淘金币/京豆）与券不是一回事：整句数字不采信，但不许牵连同帖别的句子 */
    @Test
    fun `虚拟货币抵扣那句里的数字一律不采信`() {
        val outcome = consume("使用淘金币再省0.12元起，根据账号情况可能抵更多")
        assertTrue("淘金币抵扣不是券，实际=${outcome.slots}", outcome.slots.isEmpty())
        // 正例对照：分句是边界，同帖另一句的券照常抽（评测集 cm-youhui-05 的 4|11）
        val kept = oneSlot("领取满11减4元优惠券")
        assertEquals(4.0, kept.discount!!, 0.0)
        assertEquals(11.0, kept.threshold!!, 0.0)
    }

    /**
     * 「有券名、整句没数字」产一张全 null 的券（评测集两条标了"全 null 券"的条目）。
     * 两个反例是这条判据的边界：宽词会把赠品/折扣活动也说成券，而误抽比漏抽贵。
     */
    @Test
    fun `有券名但整句没数字产一张空券`() {
        val bare = oneSlot("领券")
        assertNull(bare.discount)
        assertNull(bare.threshold)
        // 置信只能落在 CONTEXT_ONLY：没有金额就没有可核验的东西，展示层据此显示"待核验"
        assertEquals(CouponTemplates.CONTEXT_ONLY_CONFIDENCE, consume("领券").confidence, 0.0001)
        assertEquals(1, consume("试用专享券").slots.size)
        // 反例一：有数字但没角色词（9折）⇒ 不产券，否则折扣活动变成一张金额不明的券
        assertTrue(consume("下单返9折券").slots.isEmpty())
        // 反例二：无数字但不是券名（满赠）⇒ 不产券。这就是不复用 couponHints 宽词的理由
        assertTrue(consume("满赠").slots.isEmpty())
    }

    /**
     * **走生产路径**的误抽钉子（2026-10-05）。
     * 为什么必须走 `fromPost` 而不是直接喂 consume：规整层会把全角逗号换成半角
     * （`活动售价3499元,参与补贴…`），而 consume 层的用例看不到这件事 ——
     * 当时 subsidy-tail 模板把「,参与补贴」当成合法间隙，于是 3499（**售价**）变成了一张 ¥3499 的券，
     * 同时 list 槽静默变空。两条都是"看起来正常"的错，只有全量评测+生产路径才抓得到。
     */
    @Test
    fun `售价数字走生产路径也不许变成券面额`() {
        val post = "京东此款目前活动售价3499元，参与补贴15%起减500元，PLUS专享立减17.49元优惠活动，下单1件，实付低至2981.51元。"
        val extraction = CouponExtractor.fromPost(post, 0L)
        val values = extraction.coupons.map { it.discount }
        assertTrue("3499 是售价，不许进券槽，实际=$values", values.none { it == 3499.0 })
        assertEquals("售价槽不许静默变空", 3499.0, extraction.price.listPrice!!, 0.0)
        assertTrue("17.49 是真券，必须还在，实际=$values", values.contains(17.49))
    }

    /**
     * 尾判接进管线之后收下的那五条原文（2026-10-06，评测 `[价格槽对照]` 里
     * `final … → n/a` 的全部四条 + `list … → n/a` 的一条）。
     * 这五句的共同形状是**价词写在数字后面**（或中间隔一个「为」），
     * 左邻尺子结构性看不见，所以到手价槽在评测里一直空着。
     */
    @Test
    fun `价词在后的真机原文落进到手价槽，售价为止的那条落进标价槽`() {
        assertEquals(92.9, consume("¥92.9，到手价").price.finalPrice!!, 0.0)
        assertEquals(11499.0, consume("¥11499，国补领后价划线价¥12999").price.finalPrice!!, 0.0)
        assertEquals(
            "京东首页把标题/价格/销量拼进同一条 content-desc（评测集 jd-home-04 的原文形状），" +
                "「销量」只作废它紧贴的 3，不作废整句",
            1579.9,
            consume("迪卡侬公路车 人民币1579.90 入会到手价 销量3万+").price.finalPrice!!,
            0.0
        )
        // 反面对照：那枚角标自己的数字（3）不许变成价格
        assertNull(consume("销量3万+").price.finalPrice)
        assertNull(consume("销量3万+").price.listPrice)
        assertEquals(0.99, consume("0.99元（需用券）").price.finalPrice!!, 0.0)
        assertEquals(5999.0, consume("降价前售价为5999.00元").price.listPrice!!, 0.0)
        // 这五句全是"只有价格没有券"：券槽一位都不许多出来（误抽比漏抽贵）
        assertTrue(consume("¥92.9，到手价").slots.isEmpty())
        assertTrue("「需用券」只说这价要券，不给面额 ⇒ 不许产券", consume("0.99元（需用券）").slots.isEmpty())
        assertTrue(consume("降价前售价为5999.00元").slots.isEmpty())
    }

    /**
     * 管线里那条**次序即闸**：尾判只在左邻尺子与模板都没认出角色时才回落。
     * `满1000减100，到手价5499元` 是最危险的形状 —— 100 的右边紧贴「到手」，
     * 尾判单看会给它 FINAL；但 100 已经被左边的「减」认成 DISCOUNT，回落分支走不到它。
     * 这条如果哪天变红，意思是到手价槽里混进了券面额，而不是"尾判太保守"。
     */
    @Test
    fun `满1000减100后面那句到手价5499里的 100 不许被尾判认领成到手价`() {
        val outcome = consume("满1000减100，到手价5499元")
        assertEquals("到手价必须是 5499，不是券面额 100", 5499.0, outcome.price.finalPrice!!, 0.0)
        assertEquals("期望恰好一张券，实际=${outcome.slots}", 1, outcome.slots.size)
        assertEquals(100.0, outcome.slots[0].discount!!, 0.0)
        assertEquals(1000.0, outcome.slots[0].threshold!!, 0.0)
    }

    /**
     * 比例封顶不产券（2026-10-06）—— 评测里仅剩的两个 FP 是同一条形状在两个入口各计一次：
     * `参与补贴15%起减500元` 的 500 被认成券面额。
     *
     * golden 自己就把分野写死了，所以正反例必须成对钉：
     *  - `国家补贴15%减500元`（cm-faxian-01/02）**要产券** —— 那是活动明写的减免额；
     *  - `补贴15%起减500元`（cm-faxian-04 及其 clipboard 副本）**不许产券** —— 一个「起」字
     *    把 500 变成比例的封顶。少了任何一边，这条量具就退化成"照样本凑正则"。
     */
    @Test
    fun `比例后面带起的数字是封顶不是券，不带起的照旧产券`() {
        val capped = consume("京东此款目前活动售价3499元，参与补贴15%起减500元，PLUS专享立减17.49元优惠活动，下单1件，实付低至2981.51元。")
        val cappedDiscounts = capped.slots.map { it.discount }
        assertTrue("500 是 15% 比例的封顶，不许进券槽，实际=$cappedDiscounts", cappedDiscounts.none { it == 500.0 })
        assertTrue("17.49 是真立减，必须还在，实际=$cappedDiscounts", cappedDiscounts.contains(17.49))
        assertEquals("价格槽一位不许因为这条闸而变空", 3499.0, capped.price.listPrice!!, 0.0)
        assertEquals(2981.51, capped.price.finalPrice!!, 0.0)

        // 正例对照：同一句去掉「起」，500 就是减免额，必须产券
        val stated = consume("参与官方限时补贴减499元，国家补贴15%减500元优惠活动")
        val statedDiscounts = stated.slots.map { it.discount }
        assertTrue("15%减500 没有「起」，是明写的减免额，实际=$statedDiscounts", statedDiscounts.contains(500.0))
        assertTrue(statedDiscounts.contains(499.0))
    }

    /**
     * 社区那条"只说需用券、不给面额"的价格标签此前**整条不进抽取**
     * （`classifyKind` 里没有能认出它的爆料词 ⇒ 判成闲聊），到手价槽于是永远空着。
     * 这里走 `fromPost` 整条链，量的就是"它到底进没进来"，而不只是分句之后的判定。
     */
    @Test
    fun `需用券的标签进到抽取后只产到手价不产券`() {
        val raw = "0.99元（需用券）"
        assertEquals("标签文本得先是爆料帖，否则整条不进抽取", PostKind.TIP, PostAdapter.classifyKind(raw))
        val extraction = CouponExtractor.fromPost(raw, 0L)
        assertEquals(0.99, extraction.price.finalPrice!!, 0.0)
        assertTrue(
            "标签只说这价要用券、没写面额 ⇒ 一张券都不许产，实际=${extraction.coupons.map { it.discount }}",
            extraction.coupons.isEmpty()
        )
    }
}
