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

    /**
     * 计数枚举形状（真机 2026-10-03 21:45 迷你沉浸详情页）：`1个视频，4张图片` 被启发式当商品名
     * 返回，随后拿它去全网搜了一遍。判据是形状（数字+短非数字段，逗号串起来，≥2 段），不是词表。
     *
     * 三条 positive control 是这条判据的**下限**：真实商品名里有数字、有逗号、有单位，
     * 只断言"角标被拒"而不管住这些，就等于把误杀换成了漏检。
     */
    @Test
    fun `media counters are rejected while numeric product names survive`() {
        assertTrue(AccessibilityLabel.isCountEnumeration("1个视频，4张图片"))
        assertTrue(AccessibilityLabel.isCountEnumeration("3条评价，12张图片"))
        assertFalse("带空格与单位的规格写法不是计数枚举", AccessibilityLabel.isCountEnumeration("500ml 2瓶 酱香型白酒"))
        assertFalse("含逗号的真实商品名不许被误杀", AccessibilityLabel.isCountEnumeration("泸州老窖 窖龄30年，52度 500mL 6瓶"))
        assertFalse("单段计数不是枚举", AccessibilityLabel.isCountEnumeration("240Hz高刷"))
        assertFalse(PriceNodeMatcher.isDisplayableTitle("1个视频，4张图片"))
        assertTrue(PriceNodeMatcher.isDisplayableTitle("泸州老窖 窖龄30年，52度 500mL 6瓶 礼盒装"))
    }

    /**
     * 用户评价正文被当成商品名（真机 2026-10-06 15:14，取证 `E:/dev/pl-builds/shots/w-case1.png`）：
     * 详情页顶部那行是「十分满意的一次购物 刚开始在网上买电子产品 还有些忐忑不安 收到…」，
     * 紧接着这句被拿去全网搜了一遍 —— §9.24 那轮我把"商品对不上"记成点错卡，真正的成因在这里：
     * 标题挑错，价格与券是从同一页读对的，商品却是搜索回来的另一件。
     *
     * 判据取**句式**而不是词表：商品名是名词堆叠，评价是带功能词的句子。阈值定在"≥2 个不同功能词"，
     * 因为单个「的」在真实商品名里完全可能出现（`透气舒适的跑鞋`），散文式的多个不会。
     *
     * 七条 positive control 是从六棵真机树里逐字取回的商品名，含**纯中文、无空格、带逗号**三种形态：
     * 只断言"评价被拒"而不管住误杀，等于把脏数据换成读不出标题（那是比错标题更糟的结局，
     * 见 [PriceNodeMatcher.BRACKET_BADGE] 上记着的那次教训）。
     */
    @Test
    fun `review prose is rejected while real product names survive`() {
        val review = "十分满意的一次购物 刚开始在网上买电子产品 还有些忐忑不安 收到"
        assertFalse(PriceNodeMatcher.isPlausibleTitle(review, strict = true))
        assertFalse(PriceNodeMatcher.isDisplayableTitle(review))
        for (name in REAL_PRODUCT_NAMES) {
            assertTrue("真实商品名不许被误杀：$name", PriceNodeMatcher.isDisplayableTitle(name))
        }
        // 促销行 `叠加以旧换新下单，可再减1964元` 在 §9.26 当时**不归这道闸管**（只有一个「了」），
        // 靠的是 §9.6 那套"规则标题与启发式标题同尺比分"。同夜从设备库里又抓到一条同族真样本
        // （`…可再减800元`，那一页比分没兜住），于是加了词表闸 `可再减`（§9.28）——
        // 现在它由词表直接否决。这条断言留在原地，是为了别把它的功劳记给比分那一道。
        assertFalse(PriceNodeMatcher.isDisplayableTitle("叠加以旧换新下单，可再减1964元"))
    }

    /** 真机商品名（逐字取自 `app/src/test/resources/fixtures` 的六棵树） */
    private val REAL_PRODUCT_NAMES = listOf(
        "贵州茅台 飞天茅台 53%vol 酱香型白酒 500ml 2026年",
        "五粮液 第八代 52度 浓香型白酒 500ml",
        "泸州老窖 国窖1573 52度 500ml",
        "泸州老窖 窖龄30年，52度 500mL 6瓶 礼盒装",
        "LAN兰时光立体紧致修护油蜜面膜敏感肌专用深层水感润养4盒装中秋送女友礼物面膜护肤礼盒",
        "雷神 【白条24期免息】猎刃S英特尔酷睿i7锐龙9高性能5060独显Ai学生轻薄16英寸电竞游戏本",
        "自营雷神（ThundeRobot）MIX-G 高性能游戏电竞设计台式电脑mini迷你主机(i9-14900HX RT"
    )

    /**
     * 两条**从设备库里挖出来的错身份**（真机 2026-10-06 17:43，
     * `run-as com.pricelens.dev` 读 `pricelens.db` 的 `watch_identity` 表）：
     *
     * ```
     * productId=ovl:i2airm2qyvp4  title=送货上门·预约送货·部分收货      lastPrice=120.0  basis=PAGE
     * productId=ovl:21hv4or90d19  title=叠加以旧换新下单，可再减800元    lastPrice=3699.0 basis=PAGE
     * ```
     *
     * 第一条是**服务条款行**：11 字、无货币符号、无冒号、不含任何既有黑名单词，
     * 也不含 §9.26 那条句式判据要的功能词 —— 它两条闸都不碰，于是成了盯价身份，
     * 还带着 ¥120 挂在努比亚折叠屏那一页上（`search_records` 里能查到拿它搜过一遍）。
     * 第二条是 §9.6 那个促销形态的**又一个真样本**（同族上一条是 `…可再减1964元`）：
     * 那一页它被"规则/启发式同尺比分"挡住了，这一页没有——比分只在**真标题也在树里**时才有用，
     * 所以它需要一道词表闸兜着，不能只靠比分。
     *
     * 只收**每条一个**标记词（`送货上门` / `可再减`）而不是把串里每个词都收进来：
     * 一条真样本配一个词，误杀面最小。
     */
    @Test
    fun `service-terms and stacked-promo lines never become a product title`() {
        assertFalse(PriceNodeMatcher.isDisplayableTitle("送货上门·预约送货·部分收货"))
        assertFalse(PriceNodeMatcher.isDisplayableTitle("叠加以旧换新下单，可再减800元"))
        assertFalse(PriceNodeMatcher.isPlausibleTitle("送货上门·预约送货·部分收货", strict = true))
        for (name in REAL_PRODUCT_NAMES) {
            assertTrue("真实商品名不许被误杀：$name", PriceNodeMatcher.isDisplayableTitle(name))
        }
        // 徽章写法不受影响（黑名单前先剥【…】，理由见 PriceNodeMatcher.BRACKET_BADGE）
        assertTrue(PriceNodeMatcher.isDisplayableTitle("【送货上门】全实木沙发三人位 现代简约布艺小户型"))
        // 已知代价：把服务承诺**裸写**进商品名的整串否决，会退回"这一页读不出标题"。
        // 真机六棵树里没有这种形态；出现过就来加白名单，别把它当通过。
        assertFalse(PriceNodeMatcher.isDisplayableTitle("实木沙发三人位 送货上门"))
    }
}
