package com.pricelens.coupon

import com.pricelens.accessibility.container
import com.pricelens.accessibility.leaf
import com.pricelens.domain.LinkParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #63：剪贴板一次读取，同时喂「链接」与「找券」两条链。
 *
 * 交接本身很薄（单槽 + 时间戳），真正要钉的是两件事：
 *  - **同一次读取**必须既给出链接结果又给出原文（旧版只在认出链接时才返回，
 *    分享文本里的券因此永远进不了找券链；而找券段自己再读一次就是二次读取）；
 *  - 三路输入（页面树 / 剪贴板文本 / 关键词）的取舍是**都做加法**，
 *    只有"过期"和"空"才让某一路退出，不存在谁覆盖谁。
 */
class ClipboardCaptureTest {

    private fun tree(at: Long) = PageCapture.Capture(
        signature = "sig",
        packageName = "com.jingdong.app.mall",
        itemId = null,
        capturedAtElapsedMs = at,
        root = leaf(text = "满199减50")
    )

    @After
    fun tearDown() {
        PageCapture.clear()
        ClipboardCapture.clear()
    }

    // ---------- 单槽交接 ----------

    @Test
    fun `剪贴板原文进单槽且只留最新一条`() {
        ClipboardCapture.publish("第一段", atElapsedMs = 10L)
        ClipboardCapture.publish("第二段", atElapsedMs = 20L)
        assertEquals("第二段", ClipboardCapture.latest.value?.raw)
        assertEquals(20L, ClipboardCapture.latest.value?.capturedAtElapsedMs)
    }

    @Test
    fun `空白文本不进槽`() {
        ClipboardCapture.publish("   ", atElapsedMs = 1L)
        assertNull(ClipboardCapture.latest.value)
        ClipboardCapture.publish("有内容", atElapsedMs = 2L)
        ClipboardCapture.publish("", atElapsedMs = 3L)
        assertEquals("有内容", ClipboardCapture.latest.value?.raw)
    }

    // ---------- 三路输入的取舍 ----------

    @Test
    fun `三路都有时三路都做加法而页面树排第一`() {
        val plan = LocalCouponInputPlanner.plan(
            capture = tree(1_000L),
            clipboard = ClipboardCapture.Reading("领券满199减50", 1_500L),
            keyword = "小米14",
            nowElapsedMs = 1_500L
        )
        assertEquals(
            listOf(LocalCouponInputPlanner.Input.PAGE_TREE, LocalCouponInputPlanner.Input.CLIPBOARD_TEXT),
            plan.inputs.filter { it != LocalCouponInputPlanner.Input.KEYWORD_TEXT }
        )
        assertEquals(
            listOf(
                LocalCouponInputPlanner.Input.PAGE_TREE,
                LocalCouponInputPlanner.Input.CLIPBOARD_TEXT,
                LocalCouponInputPlanner.Input.KEYWORD_TEXT
            ),
            plan.inputs
        )
    }

    @Test
    fun `只有剪贴板文本时它就是唯一输入`() {
        val plan = LocalCouponInputPlanner.plan(
            capture = null,
            clipboard = ClipboardCapture.Reading("【￥K8Z3￥】m.tb.cn/h.abc 领券满199减50", 5_000L),
            keyword = "",
            nowElapsedMs = 5_000L
        )
        assertEquals(listOf(LocalCouponInputPlanner.Input.CLIPBOARD_TEXT), plan.inputs)
    }

    @Test
    fun `剪贴板文本过期就退回关键词`() {
        val at = 1_000L
        val limit = LocalCouponInputPlanner.MAX_AGE_MS
        val plan = LocalCouponInputPlanner.plan(
            capture = null,
            clipboard = ClipboardCapture.Reading("很久以前复制的", at),
            keyword = "小米14",
            nowElapsedMs = at + limit + 1
        )
        assertEquals(listOf(LocalCouponInputPlanner.Input.KEYWORD_TEXT), plan.inputs)
    }

    @Test
    fun `树与剪贴板同时过期而关键词为空时没有任何输入`() {
        val at = 1_000L
        val limit = LocalCouponInputPlanner.MAX_AGE_MS
        val plan = LocalCouponInputPlanner.plan(
            capture = tree(at),
            clipboard = ClipboardCapture.Reading("旧文本", at),
            keyword = "  ",
            nowElapsedMs = at + limit + 1
        )
        assertEquals(emptyList<LocalCouponInputPlanner.Input>(), plan.inputs)
    }

    @Test
    fun `边界与异常时钟沿用同一条口径`() {
        val at = 10_000L
        val limit = LocalCouponInputPlanner.MAX_AGE_MS
        // 恰好等于上限算新鲜（闭区间下界，与三档阈值同口径）
        assertEquals(
            listOf(LocalCouponInputPlanner.Input.PAGE_TREE, LocalCouponInputPlanner.Input.CLIPBOARD_TEXT),
            LocalCouponInputPlanner.plan(
                tree(at),
                ClipboardCapture.Reading("文本", at),
                "",
                at + limit
            ).inputs
        )
        // 时钟回拨按过期处理，而不是"永远新鲜"
        assertEquals(
            emptyList<LocalCouponInputPlanner.Input>(),
            LocalCouponInputPlanner.plan(tree(at), null, "", at - 1).inputs
        )
        // 年龄随判据一起交出去（日志与出处行要用）
        assertEquals(3_000L, LocalCouponInputPlanner.plan(tree(at), null, "券", at + 3_000L).treeAgeMs)
        assertEquals(-1L, LocalCouponInputPlanner.plan(null, null, "券", at).treeAgeMs)
    }

    // ---------- 三路结果的加法合并 ----------

    @Test
    fun `三路合并时相同面额门槛的券只算一张而每张保留自己的证据句`() {
        val tree = CouponExtractor.fromPage(container(kids = arrayOf(leaf(text = "满199减50"))))
        val clipboard = CouponExtractor.fromClipboard("领券满199减50；17元外卖餐补")
        val keyword = CouponExtractor.fromClipboard("小米14 12+256G")
        val merged = CouponExtractor.mergeAll(listOf(tree, clipboard, keyword))
        val keys = merged.coupons.map { Pair(it.discount, it.threshold) }
        assertEquals("实际=$keys", listOf(Pair(50.0, 199.0), Pair(17.0, null)), keys)
        assertEquals(
            "证据句逐张保留自己那句",
            listOf("满199减50", "17元外卖餐补"),
            merged.coupons.map { it.sourceText }
        )
        val best = listOf(tree.confidence, clipboard.confidence, keyword.confidence).maxOrNull() ?: 0.0
        assertEquals(best, merged.confidence, 0.0001)
    }

    @Test
    fun `合并空列表等于没有结果而不是崩溃`() {
        val merged = CouponExtractor.mergeAll(emptyList())
        assertEquals(0, merged.coupons.size)
        assertEquals(0.0, merged.confidence, 0.0)
    }

    /**
     * 「一次读取喂两条链」的纯判据（`ClipboardTriage`，不带 android 依赖所以能在 JVM 里钉）。
     *
     * 旧版把两件事混成一个条件：只有认出链接才把文本交出去 ⇒ 分享文本里的券永远进不了找券链。
     * 现在两条链各自判断：**横幅**要认得链接且没提示过，**券**只要文本非空且是新的。
     */
    @Test
    fun `不是链接的分享文本照样喂给券而不弹横幅`() {
        val share = "京东这个好好用，领券满199减50，到手149"
        val decision = ClipboardTriage.decide(previousHash = 0, text = share, link = null)
        assertEquals(false, decision.showBanner)
        assertEquals(share, decision.publishText)
    }

    @Test
    fun `认出链接时两条链一起拿到`() {
        val withLink = "【淘宝】https://m.tb.cn/h.GqXbK1 领券满199减50"
        val parsed = LinkParser.parse(withLink)
        assertEquals(true, parsed != null)
        val decision = ClipboardTriage.decide(previousHash = 0, text = withLink, link = parsed)
        assertEquals(true, decision.showBanner)
        assertEquals(withLink, decision.publishText)
        assertEquals(withLink.hashCode(), decision.newHash)
    }

    @Test
    fun `同一段内容第二次读到时两条链都不再被打扰`() {
        val text = "领券满199减50"
        val first = ClipboardTriage.decide(previousHash = 0, text = text, link = null)
        val second = ClipboardTriage.decide(previousHash = text.hashCode(), text = text, link = null)
        assertEquals(text, first.publishText)
        assertNull(second.publishText)
        assertEquals(false, second.showBanner)
    }

    @Test
    fun `空白文本什么都不交`() {
        val decision = ClipboardTriage.decide(previousHash = 7, text = "   ", link = null)
        assertNull(decision.publishText)
        assertEquals(false, decision.showBanner)
        // 哈希保持不变：下一次读到同样空白不该被当成"新内容"
        assertEquals(7, decision.newHash)
    }
}
