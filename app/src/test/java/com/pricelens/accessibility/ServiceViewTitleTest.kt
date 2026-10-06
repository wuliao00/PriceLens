package com.pricelens.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用**服务视角**的真机树钉住一条判据：没有位置的节点不许当商品名。
 *
 * 为什么这条要单独有一批用例（真机 2026-10-06 19:38~19:40，vivo V2156A，京东已登录）：
 * 之前所有真机夹具都是 `uiautomator dump` 的 XML，而判据跑的是 `rootInActiveWindow`
 * 那棵树 —— 同一页两者规模差两个数量级（19~21 节点 vs 1279~1291），**边界框归属也不同**。
 * 那批夹具因此从来没暴露过"未布局节点漂进标题"这个形态。
 *
 * 换成 TEMP-DUMP 抓服务自己的树之后，形态当场出现：
 *  - 完整商详那棵里，`b=null` 的文本节点恰好就是历史上出过事的几种：
 *    竖排加载提示 `继\n续\n滑\n动…`、`送货上门·预约送货·部分收货`
 *    （**这条今天 17:43 真的变成过一条 ¥120 的盯价身份**，见 §9.28 二）、
 *    `23:10前付款，预计明天送达`、一个自提点名称（已脱敏）；
 *  - 迷你页那棵里，屏上那件（`荣耀Magic8 Pro Air薄至6.1mm…`，`b=568,1913,1020,1942`）
 *    有位置，而信息流里**下一件**（`一加 13T …`）与一个榜单角标（`排行榜超粉嫩手机榜第2名`）都没有。
 *
 * 所以判据是"标题候选必须有位置"，不是"再往黑名单里塞一个词"——
 * 后者是一条样本一个词地补，补不完（§9.28 六 记的就是这个上限）。
 */
class ServiceViewTitleTest {

    private val mini by lazy { ServiceTreeDump.load(FIXTURE_MINI) }
    private val detail by lazy { ServiceTreeDump.load(FIXTURE_DETAIL) }

    /** 夹具本身的可信度：解析没漂、两种边界框形态都在（漂了就等于用例在测空气） */
    @Test
    fun `service-view fixtures parse with both laid-out and unlaid-out nodes`() {
        val miniNodes = bfs(mini)
        val detailNodes = bfs(detail)
        assertTrue("迷你页树只有 ${miniNodes.size} 个节点，夹具被换过？", miniNodes.size >= 150)
        assertTrue("完整商详树只有 ${detailNodes.size} 个节点，夹具被换过？", detailNodes.size >= 400)
        for (tree in listOf(miniNodes, detailNodes)) {
            assertTrue("树里没有 b=null 的节点 = 夹具不是服务视角的", tree.any { it.bounds == null })
            assertTrue("树里没有带位置的节点 = 夹具不是服务视角的", tree.any { it.bounds != null })
        }
        // 探针把文本截到 90 字，所以这里断言"截断后仍然够长"而不是页面上的全长
        assertNotNull(
            "完整商详那棵里屏上商品的标题节点不见了（夹具被换过？）",
            detailNodes.firstOrNull { it.text?.contains("荣耀Magic8") == true && it.bounds != null }
        )
    }

    /**
     * 两棵真机树上现在的判定必须是**屏上那件**。
     * 这条在加"必须有位置"之前就是绿的（真机上屏上那件确实最长），
     * 它的作用是**闸门不许把好的一帧也弄没**——与 §9.6 那条同一条红线。
     */
    @Test
    fun `both real service trees still name the product on screen`() {
        val miniTitle = extractTitle(mini, ShopPlatform.JD)?.text
        val detailTitle = extractTitle(detail, ShopPlatform.JD)?.text
        assertNotNull("迷你页读不出标题 = 浮窗不弹", miniTitle)
        assertNotNull("完整商详读不出标题 = 浮窗不弹", detailTitle)
        assertTrue("屏上是荣耀 Magic8，读出来却是别的：$miniTitle", miniTitle!!.contains("荣耀Magic8"))
        assertTrue("屏上是荣耀 Magic8，读出来却是别的：$detailTitle", detailTitle!!.contains("荣耀Magic8"))
    }

    /**
     * 红的那条：这一帧里**只有**没布局的东西可读（屏上商品名被回收掉了）——
     * 宁可不弹，也不许把未布局的模块文本当商品名。
     *
     * 候选文本逐字取自 `svc_mini_honor_20261006.txt` 里一个 `b=null` 节点。
     * 它今天能过所有闸：12 字、无冒号、无黑名单词、无功能词（§9.26 那条句式判据管不着它）。
     * 真机上它没成为标题只是因为屏上那件更长 —— 也就是说**它一直站在门后面**，
     * 换一帧（商品名被回收）就会顶上去，然后被拿去全网搜一遍、再落成一条盯价身份。
     */
    @Test
    fun `unlaid-out nodes are not title candidates`() {
        val frame = NodeSnapshot(
            text = null,
            contentDescription = null,
            className = "android.widget.FrameLayout",
            resourceName = null,
            clickable = false,
            children = listOf(
                NodeSnapshot(
                    text = "排行榜超粉嫩手机榜第2名", contentDescription = null, className = "android.widget.TextView",
                    resourceName = null, clickable = false, bounds = null
                ),
                NodeSnapshot(
                    text = "23:10前付款，预计明天送达", contentDescription = null, className = "android.widget.TextView",
                    resourceName = null, clickable = false, bounds = null
                )
            )
        )
        assertNull(
            "这一帧没有布局好的商品名 ⇒ 应该读不出标题，而不是拿未布局的模块文本顶上：${extractTitle(frame, ShopPlatform.JD)?.text}",
            extractTitle(frame, ShopPlatform.JD)
        )
        // 同一段文本挂上位置就是合法候选（判据是"有没有位置"，不是这段文字本身）
        val laidOut = frame.copy(
            children = frame.children.map {
                it.copy(bounds = NodeBounds(48, 1844, 1032, 1900))
            }
        )
        assertNotNull("有位置的候选不该被这条判据挡掉", extractTitle(laidOut, ShopPlatform.JD))
    }

    /**
     * 门控侧不许被这条改动带偏：完整商详那棵树仍然算商详（它读得出标题与价格）。
     *
     * 这里**故意不对迷你页那棵下断言**：这一棵（19:38 抓的，182 个节点）与 §9.27 那棵
     * （17:17 抓的，1279 个节点）不是一种帧 —— 它有 `立即购买`（x4），那棵没有。
     * 迷你页的结论是"帧与帧差得很大、不能一句话概括"，已记在 §9.27 / §9.29，
     * 拿一棵夹具去钉"迷你页永远不是商详"会是假的确定性。
     */
    @Test
    fun `page gate still accepts the real detail tree`() {
        assertTrue(
            "完整商详判成非商详 = 用户看到的『浮窗不弹』",
            isProductPage(detail, ShopPlatform.JD)
        )
    }

    /** 探针落盘的表头行不参与判定，但夹具要是被改坏要能立刻发现 */
    @Test
    fun `fixture header line is intact`() {
        assertEquals("activity=-", readFirstLine(FIXTURE_MINI))
        assertEquals("activity=-", readFirstLine(FIXTURE_DETAIL))
    }

    private fun readFirstLine(fileName: String): String = checkNotNull(
        javaClass.classLoader?.getResourceAsStream("fixtures/$fileName")
    ) { "缺少夹具 fixtures/$fileName" }.use { it.bufferedReader().readLine() }

    private companion object {
        const val FIXTURE_MINI = "svc_mini_honor_20261006.txt"
        const val FIXTURE_DETAIL = "svc_detail_honor_20261006.txt"
    }
}
