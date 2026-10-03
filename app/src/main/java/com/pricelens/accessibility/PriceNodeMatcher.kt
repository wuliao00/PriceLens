package com.pricelens.accessibility

/**
 * §1.2 已知电商控件 ID 白名单 + 文本规则库（A2 重写）。
 *
 * 旧版三层判定的缺陷（取证见 2026-09 静态审查）：
 *  - "任意 viewId + ≤10 位纯数字"判价 → 分期数 12、券面额 300、存储 256 全被当价格；
 *  - 京东 resource-id 写死全限定名，改版即失效 → 现改为"ID 名后缀"匹配（跨版本存活），
 *    历史全限定 ID 表保留兼容；
 *  - 从不读 contentDescription → 白名单同时覆盖 text 与 contentDescription。
 *
 * 本对象只保留"文本/ID 规则"，树遍历逻辑在 NodeSnapshot.kt 的纯函数里（可 JVM 单测）。
 * 白名单集中在此、按平台分套，真机回归时按 App 版本校准即可，无需改动遍历代码。
 */
object PriceNodeMatcher {

    // ---------- 宿主 APP ----------

    private val KNOWN_APP_PREFIXES = listOf(
        "com.jingdong", "com.taobao", "com.xunmeng"
    )

    fun isKnownApp(packageName: String): Boolean =
        KNOWN_APP_PREFIXES.any { packageName.startsWith(it) }

    // ---------- 价格 ID：主价白名单（历史全限定 ID 兼容保留） ----------

    private val LEGACY_FULL_PRICE_IDS = setOf(
        "com.jingdong.app.mall:id/jd_price",
        "com.jingdong.app.mall:id/new_price",
        "com.jd.lib.productdetail:id/price_tv",
        "com.taobao.taobao:id/detail_price",
        "com.taobao.taobao:id/tv_price",
        "com.xunmeng.pinduoduo:id/goods_price",
        "com.xunmeng.pinduoduo:id/tv_price"
    )

    /** 按平台的"ID 名后缀"白名单：只认 `:id/<name>` 的 name 段，包壳改版不失效 */
    private val JD_PRICE_ID_NAMES = setOf(
        "jd_price", "new_price", "price_tv", "main_price", "price_view",
        "final_price", "detail_price", "tv_price", "product_price"
    )
    private val TB_PRICE_ID_NAMES = setOf(
        "detail_price", "tv_price", "item_price", "price_text", "main_price"
    )
    private val PDD_PRICE_ID_NAMES = setOf(
        "goods_price", "tv_price", "price_view", "sku_price", "group_price", "main_price"
    )

    /** 排除性 ID 片段：分期/券面额/存储容量/数量/评价等节点即使带数字也不是价格 */
    private val EXCLUDED_ID_TOKENS = listOf(
        "installment", "fenqi", "period", "coupon", "youhui", "voucher",
        "storage", "capacity", "count", "num_", "quantity", "star", "comment", "evaluate"
    )

    private fun idName(resourceName: String?): String? =
        resourceName?.substringAfterLast(":id/")?.takeIf { it.isNotEmpty() && it != resourceName }

    fun isKnownPriceId(resourceName: String?, platform: ShopPlatform): Boolean {
        if (resourceName == null) return false
        if (resourceName in LEGACY_FULL_PRICE_IDS) return true
        val name = idName(resourceName) ?: return false
        return when (platform) {
            ShopPlatform.JD -> name in JD_PRICE_ID_NAMES
            ShopPlatform.TAOBAO -> name in TB_PRICE_ID_NAMES
            ShopPlatform.PDD -> name in PDD_PRICE_ID_NAMES
            ShopPlatform.UNKNOWN -> name in (JD_PRICE_ID_NAMES + TB_PRICE_ID_NAMES + PDD_PRICE_ID_NAMES)
        }
    }

    /** 主价 ID 命中但名字含排除片段（如 jd_price_installment）也要否决 */
    fun isExcludedPriceId(resourceName: String?): Boolean {
        val name = idName(resourceName)?.lowercase() ?: return false
        return EXCLUDED_ID_TOKENS.any { name.contains(it) }
    }

    // ---------- 标题 ID / contentDescription 白名单 ----------

    private val JD_TITLE_ID_NAMES = setOf(
        "goods_title", "item_title", "product_title", "main_title", "title_view",
        "tv_title", "title_text", "goods_name", "good_name", "title"
    )
    private val TB_TITLE_ID_NAMES = setOf(
        "item_title", "detail_title", "goods_title", "main_title", "title_view",
        "tv_title", "title_text", "title"
    )
    private val PDD_TITLE_ID_NAMES = setOf(
        "goods_title", "item_title", "tv_title", "title_view", "goods_name", "title_text", "title"
    )

    /** 二级白名单的"语义 ID"：名字含标题语义、且不含用户/店铺/搜索等噪声语义 */
    private val TITLE_SEMANTIC_TOKENS = listOf("title", "goods", "product", "item", "name")
    private val TITLE_NEGATIVE_TOKENS = listOf(
        "user", "nick", "shop", "store", "search", "address", "comment", "tab", "nav"
    )

    fun isKnownTitleId(resourceName: String?, platform: ShopPlatform): Boolean {
        val name = idName(resourceName) ?: return false
        return when (platform) {
            ShopPlatform.JD -> name in JD_TITLE_ID_NAMES
            ShopPlatform.TAOBAO -> name in TB_TITLE_ID_NAMES
            ShopPlatform.PDD -> name in PDD_TITLE_ID_NAMES
            ShopPlatform.UNKNOWN -> false
        }
    }

    fun matchesTitleSemantics(resourceName: String?): Boolean {
        val name = idName(resourceName)?.lowercase() ?: return false
        if (TITLE_NEGATIVE_TOKENS.any { name.contains(it) }) return false
        return TITLE_SEMANTIC_TOKENS.any { name.contains(it) }
    }

    // ---------- 标题文本规则 ----------

    /** 黑名单词：命中即非商品名（促销长句/服务承诺/参数区/评价区/推荐位/按钮） */
    private val TITLE_BLACKLIST_WORDS = listOf(
        "补贴", "免息", "退货", "参数", "评价", "推荐", "加入购物车",
        "领券", "红包", "运费险", "售后", "发票", "包邮", "秒杀", "抢购",
        "评论", "晒单", "问答", "关注", "客服", "进店", "物流", "发货", "更多", "好店"
    )

    private val CURRENCY_PATTERN = Regex("[¥￥]")

    /**
     * 商品名长度上限（**清洗后**的字符数）。
     *
     * 这里曾经是 `if (strict) 80 else 120` 两个各自拍的数，2026-10-03 晚被真机连抽两次脸：
     *  1. 规则兜底网 `{10,80}` 把 145 字的真商品名排除在网外，网里只剩促销行
     *     `当前地区可领，本单可减1500元` ⇒ 浮窗显示促销语并拿它全网搜了一遍
     *     （`jd_detail_guobu_popup_plb110_20261003.xml`，logcat 为证）；把网抬到 200 之后
     *     规则**确实**取到了真名，却被本函数这道闸又判死——同一个数在两个地方各写一遍；
     *  2. 六棵真机树的商品名实测长度 32 / 61 / 145 / 170（170 那棵含零宽填充，清洗后约 155）。
     *
     * 所以：一个数、两路共用、值取"实测最长 170 再留一档"。上限存在的意义是挡住
     * "整段说明文字/拼接正文"，不是挡商品名——真商品名比想象的长得多。
     * 改这个数之前先去看 `app/src/test/resources/fixtures` 目录里最长的那条商品名。
     */
    const val TITLE_MAX_LEN = 220
    private val PURE_NUMBER = Regex("\\d{1,9}(?:\\.\\d{1,2})?")
    private val DATEISH = Regex("^[\\d\\s.,%\\-/:年月日]+$")

    /**
     * 【…】徽章段。剥它而不是让它否决整串的理由（真机 2026-10-03 10:42）：
     * 京东把「【白条24期免息】」这类徽章**写进商品名本身**
     * （`雷神 【白条24期免息】猎刃S英特尔酷睿i7…游戏本`），而 `免息` 在标题黑名单里 ——
     * 整串否决的结局不是"少一条脏数据"，而是这一页**读不出标题**，于是门控判"非商详"、
     * 浮窗干脆不弹（比显示错标题更糟）。
     *
     * 只剥方括号段，不剥裸写的 `24期免息`：那样会把 `12期免息 满3000减300` 这类
     * 促销句放进来（去掉免息后整句没有黑名单词了）。真机见过的分期促销都在【】里，
     * 裸写的形态留给以后有证据再处理。剥完空了说明这串本来就是个徽章 ⇒ 仍然否决。
     */
    private val BRACKET_BADGE = Regex("【[^】]*】")

    /**
     * 不可见控制字符：零宽空格/零宽不连/零宽连、词连接符、BOM，以及双向文本的标记符。
     * 它们**不是** Unicode 空白字符 —— `String.trim()`（按 Char.isWhitespace）与正则 `\s`
     * 都认不出来，所以必须显式删。
     */
    private val INVISIBLE = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060\\u2066-\\u2069\\uFEFF]")

    /**
     * 规整标题文本：先删不可见控制字符（真机京东商详页用 U+200B 填充标题防爬，
     * 见 `InvisibleTextSanitizingTest` 的取证说明），再 trim + 连续空白压缩为单空格；空返回 null
     */
    fun cleanTitle(raw: String?): String? {
        // 先剥读屏角色后缀（"购物车20，按钮" → "购物车20"）：这类串来自 contentDescription，
        // 不是页面原文（真机取证见 AccessibilityLabel 的注释）
        val deRolled = AccessibilityLabel.stripRoles(raw ?: return null)
        val t = deRolled.replace(INVISIBLE, "").trim().replace('\u00A0', ' ').replace(Regex("\\s+"), " ")
        return t.takeIf { it.isNotEmpty() }
    }

    /**
     * 标题合理性：
     *  - strict=false（一/二级，ID 已背书）：≥6 字、无货币符号、无换行、不在黑名单；
     *  - strict=true（三级启发式）：>8 字（旧阈值保留）、≤80、无冒号规格行特征、非纯数字/日期。
     *  - **两级都拒绝导航/工具位**（"购物车20"、"首页"、"消息3"）：一/二级只看 ID 语义，
     *    而淘宝把这些节点的 contentDescription 也做成了语义化 id，长度门槛 6 字挡不住（真机脏数据见 AccessibilityLabel）。
     *  - **两级都拒绝竖排逐字文案与读屏说明句**（真机取证见 AccessibilityLabel 第二批注释）。
     */
    fun isPlausibleTitle(text: String, strict: Boolean): Boolean {
        val minLen = if (strict) 9 else 6
        if (text.length < minLen || text.length > TITLE_MAX_LEN) return false
        if (AccessibilityLabel.looksLikeNavLabel(text)) return false
        if (AccessibilityLabel.isVerticalSpacedText(text)) return false
        if (AccessibilityLabel.looksLikeTalkbackText(text)) return false
        if (CURRENCY_PATTERN.containsMatchIn(text)) return false
        if (text.contains('\n')) return false
        if (DATEISH.matches(text)) return false
        val withoutBadges = BRACKET_BADGE.replace(text, "")
        if (withoutBadges.isBlank()) return false
        if (TITLE_BLACKLIST_WORDS.any { withoutBadges.contains(it) }) return false
        if (strict && looksLikeSpecLine(text)) return false
        return true
    }

    /**
     * 能不能当**浮窗上那行商品名**用：过合理性闸，且不是规格/选择态行。
     *
     * 比 [isPlausibleTitle] 多一道 `looksLikeSpecLine`（含 `:` 或 `：`）：真机规则兜底选择器
     * 抓到过 `已选：【免费升级24G】猎刃S 14代i5HX|5050天青色，16G/1T Pcie固态，1件`
     * —— 那是 SKU 选择态，不是商品名（2026-10-03 10:42 真机）。三级启发式本来就因
     * strict=true 拒它，一/二级（ID 背书）与规则路径不拒，所以这道要单独有名字给它们用。
     */
    fun isDisplayableTitle(text: String): Boolean =
        isPlausibleTitle(text, strict = false) && !looksLikeSpecLine(text) &&
        !AccessibilityLabel.isCountEnumeration(text)

    /**
     * 标题候选打分 —— [extractTitle] 的三级启发式与 [com.pricelens.rules.DetectionPipeline]
     * 比较"规则标题 vs 启发式标题"用的是**同一把尺**（两处各写一套分数迟早会漂移）。
     *
     * 长度本身是正分（商品名通常比界面文案长），但只到 60 封顶；
     * 12~45 是典型商品名长度带，额外加权；规格参数行重罚。
     * 调用方各自的附加项（如"节点可点击减 4 分"）留在调用处，不进这个公共尺。
     */
    fun titleScore(text: String): Int {
        var score = text.length.coerceAtMost(60)
        if (text.length in 12..45) score += 15
        if (looksLikeSpecLine(text)) score -= 25
        return score
    }

    /** 规格参数行特征："屏幕尺寸：6.7英寸"、"存储容量：256GB" */
    fun looksLikeSpecLine(text: String): Boolean =
        text.contains(':') || text.contains('：')

    // ---------- 价格文本规则 ----------

    private val PRICE_SYMBOL = Regex("[¥￥]\\s*([\\d,]+(?:\\.\\d+)?)")
    private val BARE_AMOUNT = Regex("^\\s*([\\d,]+(?:\\.\\d+)?)\\s*(元)?\\s*$")

    /** 分期/免息/首付语境不是价格本体（"12期免息"的 12） */
    private val PRICE_TEXT_EXCLUDE_WORDS = listOf("分期", "免息", "首付", "评价", "晒单")

    fun isPriceExcludedText(text: String): Boolean =
        PRICE_TEXT_EXCLUDE_WORDS.any { text.contains(it) }

    fun isPureNumber(text: String): Boolean = PURE_NUMBER.matches(text.trim())

    fun hasCurrency(text: String): Boolean = text.contains('¥') || text.contains('￥')

    /** 价格数值合理区间：排除 0、负数与超过 30 万的"总销量/浏览量"类数字 */
    fun isPlausiblePriceValue(value: Double): Boolean = value in 0.01..300_000.0

    /** "¥7,999" / "7999.00" / "到手价￥129" → 129.0；解析失败返回 null */
    fun extractPrice(text: String): Double? {
        val m = PRICE_SYMBOL.find(text) ?: BARE_AMOUNT.find(text) ?: return null
        return m.groupValues[1].replace(",", "").toDoubleOrNull()?.takeIf { it > 0.0 }
    }

    // ---------- 确定性商品 ID ----------

    /** 京东商详链接（含 m 站 item.m.jd.com/product/…，与 PriceRepository.buildHistory 口径一致） */
    private val JD_URL_SKU = Regex("item(?:\\.m)?\\.jd\\.com/(?:product/)?(\\d{6,})")
    private val ID_PARAM = Regex("[?&](?:sku|goods_id|product_id|id)=(\\d{6,})")

    fun findJdSku(text: String): String? =
        JD_URL_SKU.find(text)?.groupValues?.get(1) ?: ID_PARAM.find(text)?.groupValues?.get(1)

    // ---------- 页面结构特征（商详门控） ----------
    /*
     * 词表本体搬到了 [PageVocabulary]（2026-10-03 国补页改文案逼出来的改动）：
     * 这里只留"子串匹配"这一条语义和四个判定函数，词表本身变成**可被远端规则包覆盖的数据**
     * （`rules/<id>.json` 的 `gate` 块，见 [com.pricelens.rules.RuleJson]）。
     * 每个函数的 [PageVocabulary.DEFAULT] 默认参保持既有调用点（含全部真机门控用例）
     * 一行不改也成立——它们测的就是出厂词表。
     */

    /** 这页有没有"购买动作"（含加入购物车，所以它不足以单独判商详） */
    fun isBuyAction(text: String, vocabulary: PageVocabulary = PageVocabulary.DEFAULT): Boolean =
        vocabulary.buyAction.any { text.contains(it) }

    /** 这页有没有**商详底栏专属**的立购/预约动作（红线：只收完整按钮文案，见 [PageVocabulary]） */
    fun isBuyNowAction(text: String, vocabulary: PageVocabulary = PageVocabulary.DEFAULT): Boolean =
        vocabulary.buyNow.any { text.contains(it) }

    /** 商详分区标记：商品详情/宝贝详情/图文详情/商品评价（列表卡片的"查看详情"按钮不算） */
    fun isDetailSection(text: String, vocabulary: PageVocabulary = PageVocabulary.DEFAULT): Boolean =
        vocabulary.detailSection.any { text.contains(it) }

    /** 购物车/确认订单页特征：任一命中一票否决 */
    fun isCheckoutContext(text: String, vocabulary: PageVocabulary = PageVocabulary.DEFAULT): Boolean =
        vocabulary.checkout.any { text.contains(it) }

    /** PDD 商详底栏左侧"单独购买" */
    fun hasPddSingleBuy(text: String, vocabulary: PageVocabulary = PageVocabulary.DEFAULT): Boolean =
        vocabulary.pddSingleBuy.any { text.contains(it) }

    /** PDD 商详底栏右侧"发起拼单/立即拼单"（与 [hasPddSingleBuy] 成对才算商详） */
    fun hasPddGroupBuy(text: String, vocabulary: PageVocabulary = PageVocabulary.DEFAULT): Boolean =
        vocabulary.pddGroupBuy.any { text.contains(it) }
}
