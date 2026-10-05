package com.pricelens.coupon.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [LlamaDraftParser] 与 [LlamaFallbackExtractor] 的钉子。
 *
 * 夹具**直接抄自 PLB110 的真机输出**（两条 `json=` 原样），不是手写的理想 JSON ——
 * 手写的理想 JSON 只能证明"解析器能读理想输入"，而真机输出才是它要面对的东西
 * （缩进、空行、字段顺序、`null` 与缺字段混着来）。
 *
 * 最要紧的一组是"复核"：模型给的数字什么时候采信、什么时候丢掉。
 */
class LlamaFallbackExtractorTest {

    /** PLB110 实测：`满199减50，券码：ABCD1234` → 两个槽位都对 */
    private val deviceJsonSimple = """
{
  "platform":"unknown",
  "coupons":[
    {
      "discount":50,
      "threshold":199,
      "scope":"ITEM",
      "state":"CLAIMABLE",
      "expiry":null,
      "url":null
    }
  ],
  "price":{
    "final":null,
    "list":null,
    "drop":null
  },
  "confidence":0.8
}
"""

    /** PLB110 实测：把「活动售价5998元」的 5998 写成了门槛 —— 这份夹具就是那条反例 */
    private val deviceJsonListPriceAsThreshold = """
{
  "platform":"taobao",

  "coupons":[
    {
      "discount":499,
      "threshold":5998,
      "scope":"ITEM",
      "state":"CLAIMABLE",
      "expiry":null,
      "url":null
    }
  ],
  "price":{
    "final":null,
    "list":null,
    "drop":null
  },
  "confidence":0.8
}
"""

    private val hardText = "天猫精选此款目前活动售价5998元，参与官方限时补贴减499元，国家补贴15%减500元优惠活动，实付低至4999元"

    private fun extractor(returning: String?) = LlamaFallbackExtractor(ClauseModel { returning })

    @Test
    fun `真机输出的 JSON 能解析成草稿`() {
        val draft = LlamaDraftParser.parse(deviceJsonSimple)!!
        assertEquals("unknown", draft.platform)
        assertEquals(50.0, draft.discount!!, 0.0)
        assertEquals(199.0, draft.threshold!!, 0.0)
        assertEquals(0.8, draft.confidence!!, 0.0001)
    }

    @Test
    fun `不是 JSON 或空输出都当作模型没参与`() {
        assertNull(LlamaDraftParser.parse(null))
        assertNull(LlamaDraftParser.parse("\t")) // 只有空白也算"没输出"（用制表符：ktlint 不许连写两个空格）
        assertNull(LlamaDraftParser.parse("我觉得这张券是满199减50")) // 没用语法约束时的样子
        assertNull(extractor(null).extract(listOf("满199减50")))
        assertNull(extractor("没参与").extract(listOf("满199减50")))
    }

    @Test
    fun `规则也认的数字才采信且两槽都对`() {
        val draft = extractor(deviceJsonSimple).extract(listOf("满199减50，券码：ABCD1234"))!!
        assertEquals(50.0, draft.discount!!, 0.0)
        assertEquals(199.0, draft.threshold!!, 0.0)
        assertEquals(1, draft.agreement)
    }

    @Test
    fun `售价被模型写成门槛时要被规则尺子挡掉`() {
        // 真机反例：5998 在原文里是「活动售价」，规则层（含售价词）判它是 LIST，不是 THRESHOLD
        val draft = extractor(deviceJsonListPriceAsThreshold).extract(listOf(hardText))!!
        assertEquals(499.0, draft.discount!!, 0.0)
        assertNull("5998 是售价，不许被当成门槛", draft.threshold)
        // 挡掉的证据要留下（上层据此显示"待核验"，而不是悄悄丢掉）
        assertEquals(1, draft.conflict.size)
        assertEquals(true, draft.conflict[0].startsWith("threshold:"))
    }

    @Test
    fun `原文里找不到的数字一律丢掉`() {
        val invented = """
        {"platform":"jd","coupons":[{"discount":888,"threshold":null}],"confidence":0.9}
        """
        assertNull("模型编的数不许进结果", extractor(invented).extract(listOf("满199减50")))
    }

    @Test
    fun `规则判不出角色但原文确有的数字采信并标冲突`() {
        // `17元外卖餐补`：原文有 17、但没有任何角色词 ⇒ 规则判不出角色。
        // 这种情况**采信模型**（这正是兜底的意义）但把它记进 conflict 当"待核验"。
        val json = """{"platform":"jd","coupons":[{"discount":17,"threshold":null}],"confidence":0.7}"""
        val draft = extractor(json).extract(listOf("17元外卖餐补"))!!
        assertEquals(17.0, draft.discount!!, 0.0)
        assertEquals(1, draft.agreement)
        assertEquals("采信了就要留痕（降置信的依据）", 1, draft.conflict.size)
        assertEquals(ConsensusVerdict.DOWNWEIGHT, draft.verdict())
    }

    @Test
    fun `模型把百分比当券面额时也要被挡掉`() {
        // 提示词 v2 放开了「补贴/省」这些替身说法之后，这一类最容易变成误抽：
        // `国家补贴至高省15%` 里的 15 是费率。规则层的守卫与这里同口径 —— 都不认百分号。
        val json = """{"platform":"jd","coupons":[{"discount":15,"threshold":null}],"confidence":0.8}"""
        assertNull(extractor(json).extract(listOf("国家补贴至高省15%")))
    }

    @Test
    fun `模型把折扣率当券面额时同样被挡掉`() {
        // 与上面那条共用**同一个**守卫 `slots.followedByRatioUnit`（2026-10-05 之前这里是同名的一份拷贝）。
        // 真样本：`国庆出行好物低至5折` 是京东搜索页上的节点（评测集 jd-search-02，期望不产券也不产价格）。
        val json = """{"platform":"jd","coupons":[{"discount":5,"threshold":null}],"confidence":0.8}"""
        assertNull(extractor(json).extract(listOf("国庆出行好物低至5折")))
        // 正例对照：同一个数字后面不跟「折」时守卫不该拦它（否则这条闸会退化成"5 一律不采信"）
        assertNotNull(extractor(json).extract(listOf("领券满199减5")))
    }

    @Test
    fun `空分句不调用模型`() {
        val never = LlamaFallbackExtractor(ClauseModel { error("空输入不该调用模型") })
        assertNull(never.extract(emptyList()))
    }
}
