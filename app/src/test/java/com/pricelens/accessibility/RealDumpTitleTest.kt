package com.pricelens.accessibility

import com.pricelens.rules.DetectionPipeline
import com.pricelens.rules.DetectionPipeline.DetectionOutcome
import com.pricelens.rules.RuleSet
import com.pricelens.rules.loadedBuiltinJdRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真机原树标题回归（2026-10-03，OPPO PLB110 / Android 15，京东 App 已登录）。
 *
 * 触发这条重写的是一次真机走查：用户在浮窗里看到「继续滑动查看图文详细」，而
 * logcat 给出了完整链路（E:/dev/pl-evidence-29/208_logcat.txt）——
 *
 *   A11Y 命中来源=规则命中 规则 jd@v1/product_detail [title=textRegex:^[^¥￥]{10,80}$ …]
 *       price=¥92.9(basis=NET) title=继 续 滑 动 查 看 图 文 详 情
 *   搜索开始: [继 续 滑 动 查 看 图 文 详 情]
 *
 * 两个结论，都是这一批断言要钉住的：
 *  1. **v2.8.0 的规则路径绕过了标题合理性闸**。28cc529 把"导航项不是商品标题"只加在
 *     启发式路径（[extractTitle]）上，而规则先命中就直接 emit，所以用户报的第三个症状
 *     当时并没有被治好（我一度以为治好了，见 §9.1 的更正）。
 *  2. 垃圾标题不只是"看着不对"：它会**以它当关键词发起全网搜索**（当当/识货/什么值得买
 *     各拉一次 90KB HTML），并把这条垃圾写进搜索历史。
 *
 * 夹具是真机 `uiautomator dump` 原样文件（只做过一次手机号/账号扫描，未改结构）：
 *  - `jd_detail_plb110_20261003.xml`：泸州老窖商详，**加载完成后**。用于钉住"改标题闸门
 *    不能把好的那一帧也弄没"：这一帧规则标题本来就是对的（真机 logcat 与本地跑分一致），
 *    价格 ¥92.9 也是对的。
 *    （写这条用例前我猜"京东新版把款式芯片排在商品名上方，所以芯片会被当标题"——
 *    **跑完真机树证明这个猜法是错的**：BFS 顺序里标题节点先于芯片，规则抓到的是正确标题。
 *    留在这里是因为它记录了一次"看起来合理的假设被量具推翻"，别照抄直觉改判定顺序。）
 *  - `tb_detail_plb110_20261003.xml`：淘宝商详的视频态，整棵树 75 个节点里只有 12 条
 *    文本/描述，全是导航外壳（「购物车，按钮」「更多28 按钮」「图片，按钮。双击可进入详情页。」）。
 *    这一页读不出价也读不出标题 ⇒ 必须**不弹窗**，而不是把导航词当商品名弹出来。
 */
class RealDumpTitleTest {

    private val jdDetail = loadRealDump(JD_DUMP)
    private val jdMianfei = loadRealDump(JD_MIANFEI_DUMP)
    private val guobu = loadRealDump(JD_GUOBU_DUMP)
    private val tbDetail = loadRealDump(TB_DUMP)
    private val jdRule = RuleSet(listOf(loadedBuiltinJdRule()))

    @Test
    fun `fixtures are the real device trees and are not budget-truncated`() {
        assertEquals(317, jdDetail.rawNodeCount)
        assertEquals(224, jdMianfei.rawNodeCount)
        assertEquals(264, guobu.rawNodeCount)
        assertEquals(75, tbDetail.rawNodeCount)
        assertFalse(jdDetail.truncated)
        assertFalse(jdMianfei.truncated)
        assertFalse(guobu.truncated)
        assertFalse(tbDetail.truncated)
        assertEquals(jdDetail.rawNodeCount, jdDetail.snapshotNodeCount)
        assertEquals(jdMianfei.rawNodeCount, jdMianfei.snapshotNodeCount)
        assertEquals(guobu.rawNodeCount, guobu.snapshotNodeCount)
        assertEquals(tbDetail.rawNodeCount, tbDetail.snapshotNodeCount)
    }

    /**
     * 真机京东商详**加载完成后**：浮窗标题必须是商品名、价格必须是 ¥92.9。
     *
     * 这条在加闸门**之前就是绿的**（真机 logcat 同步证实），它的作用是"闸门不许把好的一帧也弄没"：
     * 规则标题不可信时会改走启发式取标题，启发式若在这棵树上读不出商品名，标题就空了、整条规则作废
     * → 浮窗不弹。那样用户看到的就不是"标题错"而是"淘宝/京东全都不弹"，是更严重的退步。
     */
    @Test
    fun `pipeline on real jd detail emits the product name and the real price`() {
        val outcome = DetectionPipeline.detect(
            jdDetail.root, ShopPlatform.JD, JD_PACKAGE, jdRule, JD_DETAIL_ACTIVITY
        )
        assertTrue("真机商详必须命中（不命中=浮窗不弹，同样是用户报的缺陷）：$outcome",
            outcome is DetectionOutcome.Hit)
        val detection = (outcome as DetectionOutcome.Hit).detection
        println("[title] 规则路径标题=${detection.title} 来源=${detection.source.label}")
        assertTrue(
            "浮窗标题必须是商品名，实测拿到的是『${detection.title}』",
            detection.title!!.contains("泸州老窖")
        )
        assertEquals(92.9, detection.price.value, 0.001)
    }

    /**
     * 用户报的第一现场：京东商详**加载中**那一帧，图上只有竖排提示「继 续 滑 动 查 看 图 文 详 情」
     * （京东把竖排文案渲染成逐字 + 空格，所以它是 21 个字符、不含 ¥，正好被兜底选择器吃掉）。
     *
     * 期望的行为是**这一帧不弹窗**（等页面渲染完，下一条用例的真标题出现再弹），
     * 而不是弹一个把界面提示当商品名、还顺手拿它去全网搜一次的浮窗。
     */
    @Test
    fun `jd loading-frame scroll hint is never emitted as a product title`() {
        val loadingFrame = container(
            kids = arrayOf(
                leaf(text = "继 续 滑 动 查 看 图 文 详 情"),
                leaf(text = "¥92.9"),
                leaf(text = "到手价"),
                leaf(text = "加入购物车"),
                leaf(text = "立即购买")
            )
        )
        val outcome = DetectionPipeline.detect(
            loadingFrame, ShopPlatform.JD, JD_PACKAGE, jdRule, JD_DETAIL_ACTIVITY
        )
        assertFalse("加载中那一帧绝不能 emit（emit 出去的就是用户看到的『继续滑动查看图文详情』）：$outcome",
            outcome is DetectionOutcome.Hit)
    }

    /**
     * 真机淘宝视频态：整棵树没有价、没有商品名，只有导航外壳。
     * 这类页面必须判"非商详"收窗 —— 它同时也是「购物车20，按钮」那条脏身份的形态。
     */
    @Test
    fun `text-less taobao detail dump yields no hit instead of nav chrome garbage`() {
        val outcome = DetectionPipeline.detect(
            tbDetail.root, ShopPlatform.TAOBAO, TB_PACKAGE, RuleSet.EMPTY, TB_DETAIL_ACTIVITY
        )
        assertFalse("淘宝视频态没有任何商品文本，绝不能命中：$outcome",
            outcome is DetectionOutcome.Hit)
        val texts = dumpTexts(tbDetail.root)
        assertTrue("夹具应保留读屏说明句（没了说明夹具被换过，本用例失去意义）",
            texts.any { it.contains("按钮") })
    }

    /**
     * 回落链单独钉一条：把规则清空（[RuleSet.EMPTY]，等价于"规则没命中"）后，
     * 启发式路径必须**自己**就能在这棵真机树上读出商品名并命中。
     *
     * 为什么单独立一条：闸门生效后，"规则标题不可信"时会改用启发式的标题。
     * 如果启发式在这棵树上读不出标题，标题为空 → 整条规则作废 → 浮窗不弹，
     * 症状就从"标题错"升级成"商详页压根不弹"。这条绿着，才说明那条回落有底。
     */
    @Test
    fun `heuristic fallback alone still reads the product name out of the same real dump`() {
        val outcome = DetectionPipeline.detect(
            jdDetail.root, ShopPlatform.JD, JD_PACKAGE, RuleSet.EMPTY, JD_DETAIL_ACTIVITY
        )
        assertTrue("启发式回落必须能命中真机商详：$outcome", outcome is DetectionOutcome.Hit)
        val detection = (outcome as DetectionOutcome.Hit).detection
        assertTrue(
            "启发式标题应含商品名，实测『${detection.title}』",
            detection.title!!.contains("泸州老窖")
        )
        assertEquals(DetectionPipeline.DetectionSource.HEURISTIC, detection.source)
    }

    /**
     * 第二棵真机京东树（10:42 同一台机、另一个商品：雷神猎刃S 游戏本）。
     *
     * 这一棵是**我自己那道闸门的反例**：规则兜底选择器抓到的是「已选：【免费升级24G】猎刃S
     * 14代i5HX|5050天青色，16G/1T Pcie固态，1件」——一行 SKU 选择态，带冒号。
     * 而真正的商品名「雷神 【白条24期免息】猎刃S英特尔酷睿…」里带 `24期免息`，
     * 会被标题黑名单整串否决（`免息` 是黑名单词），于是回落启发式也读不出东西。
     * 两个条件叠在一起 = 这一页要么显示错标题，要么干脆不弹。
     *
     * 断言方向：标题必须是商品名（含「猎刃S」）、绝不能是「已选：」那一行；价格 ¥10999 保持对。
     */
    @Test
    fun `real jd installment-promo detail emits the product name not the selected-sku line`() {
        val outcome = DetectionPipeline.detect(
            jdMianfei.root, ShopPlatform.JD, JD_PACKAGE, jdRule, JD_DETAIL_ACTIVITY
        )
        assertTrue("这页有价有标题有立购，必须命中：$outcome", outcome is DetectionOutcome.Hit)
        val detection = (outcome as DetectionOutcome.Hit).detection
        println("[mianfei] title=${detection.title} price=${detection.price.rawText}")
        assertFalse(
            "绝不能把「已选：…」这行 SKU 选择态当商品名：『${detection.title}』",
            detection.title!!.contains("已选")
        )
        assertTrue(
            "商品名必须保住（它带【白条24期免息】促销段，不许被黑名单整串否决）：『${detection.title}』",
            detection.title!!.contains("猎刃S")
        )
        assertEquals(10999.0, detection.price.value, 0.001)
    }

    /** 上面那行「已选：…」确实带规格行特征（冒号）—— 闸门靠这个信号拒它；这条钉住信号本身存在 */
    @Test
    fun `selected-sku line carries the spec-line signal the gate keys on`() {
        val skuLine = "已选：【免费升级24G】猎刃S 14代i5HX|5050天青色，16G/1T Pcie固态，1件"
        assertTrue(PriceNodeMatcher.looksLikeSpecLine(skuLine))
        assertFalse(PriceNodeMatcher.isDisplayableTitle(skuLine))
    }

    /**
     * 第四棵真机树（13:29 雷神 MIX 国补页，264 节点）—— 打脸"规则标题可以无条件优先"。
     *
     * 这条页面上规则路径确实命中了（`jd@v2`，也就是「领取补贴购买」收词生效），
     * 但它抓到的是**促销行** `叠加以旧换新下单，可再减1964元`：17 字、不含冒号、
     * 不含任何黑名单词，所以 `isDisplayableTitle` 放行。而同一棵树上启发式按分数抓到的是
     * 真商品名 `自营雷神（ThundeRobot）MIX-G 高性能游戏电竞设计台式电脑mini迷你主机…`。
     *
     * 结论：兜底选择器 `^[^¥￥]{10,80}$` 的"BFS 第一个匹配"是**按树序猜**，不是按质量猜；
     * 规则标题必须与启发式标题比分数（同一把尺 [PriceNodeMatcher.titleScore]）再决定用谁。
     */
    @Test
    fun `real jd subsidy detail prefers the product name over a promo line`() {
        val outcome = DetectionPipeline.detect(
            guobu.root, ShopPlatform.JD, JD_PACKAGE, jdRule, JD_DETAIL_ACTIVITY
        )
        assertTrue("国补页必须命中（这条同时也是「领取补贴购买」收词的真机判据）：$outcome",
            outcome is DetectionOutcome.Hit)
        val detection = (outcome as DetectionOutcome.Hit).detection
        println("[guobu] 来源=${detection.source.label} 标题=${detection.title}")
        assertFalse("不许把促销行当商品名：『${detection.title}』", detection.title!!.contains("以旧换新"))
        assertTrue("必须是商品名：『${detection.title}』", detection.title!!.contains("MIX-G"))
        assertEquals(11499.0, detection.price.value, 0.001)
    }

    /**
     * 真机第五棵（2026-10-03 21:45 PLB110，国补商品**叠着「领取国家补贴」半屏弹层**那一帧）。
     *
     * 这一帧是用户报"把界面提示当商品名"的**第四个形态**，成因和前三次都不一样，
     * 而且这次是 adb 走查当场抓到的（logcat 为证）：
     * `A11Y 命中来源=规则命中 规则 jd@v3/product_detail [title=textRegex:^[^¥￥]{10,80}$ …]
     *  title=当前地区可领，本单可减1500元` → 紧接着拿这句促销语去全网搜了一遍。
     *
     * 真因不在词表、不在打分，而在**规则标题兜底网的长度上限**：
     * 这一页真正的商品名是 145 字（`雷神（ThundeRobot）猎刃S 电竞游戏本笔记本电脑…`），
     * 被 `{10,80}` 直接排除在网外 ⇒ 网里剩下的第一个"像句子的文本"就是那句 17 字促销语。
     * 六棵真机树的商品名实测长度 32 / 61 / 145 / 170（含零宽前缀），80 这个数**低于真实分布**，
     * 是照最早那棵样本凑的。所以这条用例钉的是"上限必须容得下真商品名"，
     * 而不是"再给促销语加一个词"。
     */
    @Test
    fun `real jd detail with the subsidy popup open keeps the 145-char product name`() {
        val popup = loadRealDump(GUOBU_POPUP_DUMP)
        val outcome = DetectionPipeline.detect(popup.root, ShopPlatform.JD, JD_PACKAGE, jdRule, JD_DETAIL_ACTIVITY)
        assertTrue("国补弹层那一帧也必须命中（价格 ¥13199 就在同一棵树上）：$outcome", outcome is DetectionOutcome.Hit)
        val detection = (outcome as DetectionOutcome.Hit).detection
        println("[guobu-popup] 来源=${detection.source.label} 标题长度=${detection.title?.length} 标题=${detection.title}")
        val title = detection.title ?: "（无标题）"
        assertFalse("不许把补贴弹层的促销行当商品名：『$title』", title.contains("可领") || title.contains("可减"))
        assertTrue("必须是那串 145 字的商品名：『$title』", title.contains("猎刃S"))
    }

    private companion object {
        const val JD_DUMP = "jd_detail_plb110_20261003.xml"
        const val JD_MIANFEI_DUMP = "jd_detail_mianfei_plb110_20261003.xml"
        const val JD_GUOBU_DUMP = "jd_detail_guobu_plb110_20261003.xml"
        const val TB_DUMP = "tb_detail_plb110_20261003.xml"
        const val GUOBU_POPUP_DUMP = "jd_detail_guobu_popup_plb110_20261003.xml"
        const val JD_PACKAGE = "com.jingdong.app.mall"
        const val TB_PACKAGE = "com.taobao.taobao"
        const val JD_DETAIL_ACTIVITY = "com.jd.lib.productdetail.ProductDetailActivity"
        const val TB_DETAIL_ACTIVITY = "com.taobao.android.detail2.core.framework.NewDetailActivity"
    }
}
