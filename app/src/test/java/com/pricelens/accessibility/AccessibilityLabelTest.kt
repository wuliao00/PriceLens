package com.pricelens.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 读屏标签清洗与"导航项不是商品标题"的判定。
 *
 * 红证据不是这里给的，是真机给的：2026-10-03 上午用户在淘宝里逛了一圈，
 * `.dev` 库里落下两条脏身份（标题 `购物车，按钮` / `购物车20，按钮`，各自绑了一个价格）
 * 与两条脏搜索历史——那是 2.8.0 就有的行为。本文件把当时实际出现的字符串逐条钉住。
 *
 * **本文件同时是"我一度以为治好了"的更正记录**：28cc529 只把闸加在启发式路径上，
 * 而 v2.8.0 起规则路径先命中就直接 emit（见 [RealDumpTitleTest] 的 logcat 取证），
 * 所以用户报的「继续滑动查看图文详细」当天又复现了一次。下面第二批用例治的是
 * "什么样的字符串不是商品名"，规则路径那道闸在 DetectionPipeline 里另有一条用例钉住。
 */
class AccessibilityLabelTest {

    @Test
    fun `剥掉尾部读屏角色后缀`() {
        assertEquals("购物车20", AccessibilityLabel.stripRoles("购物车20，按钮"))
        assertEquals("购物车", AccessibilityLabel.stripRoles("购物车，按钮"))
        assertEquals("购物车", AccessibilityLabel.stripRoles("购物车，按钮，已选中"))
        assertEquals("查看详情", AccessibilityLabel.stripRoles("查看详情, 链接"))
        assertEquals("第 2 张图", AccessibilityLabel.stripRoles("第 2 张图"))
    }

    /** 标题中间出现"按钮"两字是真实商品名，不能整串删 */
    @Test
    fun `只吃尾部不吃中间`() {
        val title = "红色按钮衬衫 长袖 男装"
        assertEquals(title, AccessibilityLabel.stripRoles(title))
    }

    @Test
    fun `导航词与带角标的导航词都判为界面外壳`() {
        assertTrue(AccessibilityLabel.looksLikeNavLabel("购物车20"))
        assertTrue(AccessibilityLabel.looksLikeNavLabel("购物车20，按钮"))
        assertTrue(AccessibilityLabel.looksLikeNavLabel("首页"))
        assertTrue(AccessibilityLabel.looksLikeNavLabel("消息3"))
        assertTrue(AccessibilityLabel.looksLikeNavLabel("拍照搜索，按钮"))
        assertTrue(AccessibilityLabel.looksLikeNavLabel("购物车 20 件"))
    }

    /** 判别例：真实商品名不许被误杀（"我的/购物车/收藏"作为子串出现也不行，判定是整串相等） */
    @Test
    fun `不误杀含导航字的真实商品名`() {
        assertFalse(AccessibilityLabel.looksLikeNavLabel("我的健身计划 训练手册"))
        assertFalse(AccessibilityLabel.looksLikeNavLabel("购物车收纳袋 挂袋置物架"))
        assertFalse(AccessibilityLabel.looksLikeNavLabel("小米 14 Ultra 16GB+512GB 白色 影像旗舰"))
    }

    @Test
    fun `cleanTitle 顺带剥角色后缀并清零宽`() {
        assertEquals("购物车20", PriceNodeMatcher.cleanTitle("购物车20，按钮"))
        assertEquals("小米手机", PriceNodeMatcher.cleanTitle("小米\u200B手机"))
        assertEquals(null, PriceNodeMatcher.cleanTitle("，按钮"))
    }

    /** 主回归：当年过掉的那两级门槛（ID 背书的 strict=false，门槛只有 6 字）现在必须挡住 */
    @Test
    fun `脏标题过不了一二级门槛`() {
        val dirty = PriceNodeMatcher.cleanTitle("购物车20，按钮") ?: ""
        assertEquals("购物车20", dirty)
        assertFalse(PriceNodeMatcher.isPlausibleTitle(dirty, strict = false))
        assertFalse(PriceNodeMatcher.isPlausibleTitle("首页", strict = false))
    }

    @Test
    fun `真商品标题照常通过`() {
        val real = PriceNodeMatcher.cleanTitle("小米 REDMI Book 14 2026 Core Ultra5 32GB+1TB 星光银") ?: ""
        assertTrue(PriceNodeMatcher.isPlausibleTitle(real, strict = false))
        assertTrue(PriceNodeMatcher.isPlausibleTitle(real, strict = true))
    }

    // ---------- 2026-10-03 真机补采的第二批脏文案 ----------
    //
    // 上一批只覆盖了"导航项 + 角色后缀"。同日在 PLB110 上还抓到三类形态完全不同的读屏/渲染外壳，
    // 它们都不带「，按钮」，所以 stripRoles 与 looksLikeNavLabel 两道都拦不住：
    //  - 京东把竖排文案渲染成**逐字加空格**（「继 续 滑 动 查 看 图 文 详 情」），21 字符、不含 ¥，
    //    正好落进规则兜底选择器 `^[^¥￥]{10,80}$` 的字符带里；
    //  - 淘宝/京东的读屏说明句是**整句**（「视频，按钮。双击可暂停或播放视频。」），角色词后面还有话，
    //    所以"只吃尾部"剥不动它；
    //  - 角色词也可以只用**空格**分隔（「更多28 按钮」），不带逗号。

    @Test
    fun `竖排逐字文案判为渲染外壳`() {
        assertTrue(AccessibilityLabel.isVerticalSpacedText("继 续 滑 动 查 看 图 文 详 情"))
        // 判别例：真实标题里也有空格，但词是多字的
        assertFalse(AccessibilityLabel.isVerticalSpacedText("泸州老窖 窖龄30年 浓香型白酒 52度 250ml单瓶装"))
        assertFalse(AccessibilityLabel.isVerticalSpacedText("52度 250mL 6瓶 窖龄"))
        assertFalse(AccessibilityLabel.isVerticalSpacedText("第 2 张图"))
    }

    @Test
    fun `读屏说明句判为界面文字`() {
        assertTrue(AccessibilityLabel.looksLikeTalkbackText("视频，按钮。双击可暂停或播放视频。"))
        assertTrue(AccessibilityLabel.looksLikeTalkbackText("图片，按钮。双击可进入详情页。"))
        assertTrue(AccessibilityLabel.looksLikeTalkbackText("更多28 按钮"))
        // 判别例：以"按钮"结尾的真实商品名不许误杀（角色词必须是独立成词的）
        assertFalse(AccessibilityLabel.looksLikeTalkbackText("工业防水按钮"))
        assertFalse(AccessibilityLabel.looksLikeTalkbackText("泸州老窖 窖龄30年 浓香型白酒 52度 250ml单瓶装"))
    }

    @Test
    fun `第二批脏文案过不了一二级门槛`() {
        for (dirty in listOf(
            "继 续 滑 动 查 看 图 文 详 情",
            "视频，按钮。双击可暂停或播放视频。",
            "图片，按钮。双击可进入详情页。",
            "更多28 按钮",
            "生变化，最终以订单结算页的价格为准。若商家单独对价格进行说明的，以商家的表述为准",
            "天猫五星好店"
        )) {
            assertFalse("脏文案被判成商品标题：『$dirty』", PriceNodeMatcher.isPlausibleTitle(dirty, strict = false))
            assertFalse("脏文案（strict）被判成商品标题：『$dirty』", PriceNodeMatcher.isPlausibleTitle(dirty, strict = true))
        }
    }

    @Test
    fun `以按钮结尾的真实商品名照常通过`() {
        val real = "工业防水按钮 微动开关 LW-12mm 两脚自锁"
        assertTrue(PriceNodeMatcher.isPlausibleTitle(real, strict = false))
        assertTrue(PriceNodeMatcher.isPlausibleTitle(real, strict = true))
    }

    /**
     * 判别例（真机 10:42 第二棵树）：`免息` 在标题黑名单里，而京东把「【白条24期免息】」
     * 写进了**商品名本身**。整串否决的结局不是"少一条脏数据"，而是这一页**读不出标题**
     * ⇒ 门控判非商详 ⇒ 浮窗干脆不弹。所以先剥掉【…】徽章段再判黑名单，剥空了才否决。
     * 故意**不**剥裸写的 `24期免息`：那样会把 `12期免息 满3000减300` 这类促销句放进来
     * （去掉免息后整句就没有黑名单词了）—— 下面两条 assertFalse 就是这条边界的钉子。
     */
    @Test
    fun `分期免息促销段不否决整串而裸促销语仍否决`() {
        val real = "雷神 【白条24期免息】猎刃S英特尔酷睿i7锐龙9高性能5060独显Ai学生轻薄16英寸电竞游戏本"
        assertTrue("带分期段的真实商品名不许被整串否决", PriceNodeMatcher.isPlausibleTitle(real, strict = false))
        assertTrue(PriceNodeMatcher.isPlausibleTitle(real, strict = true))
        assertTrue(PriceNodeMatcher.isDisplayableTitle(real))
        assertFalse(PriceNodeMatcher.isPlausibleTitle("12期免息", strict = false))
        assertFalse(PriceNodeMatcher.isPlausibleTitle("24期免息 晒单返红包", strict = false))
    }
}
