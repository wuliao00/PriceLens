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
        "评论", "晒单", "问答", "关注", "客服", "进店", "物流", "发货", "更多"
    )

    private val CURRENCY_PATTERN = Regex("[¥￥]")
    private val PURE_NUMBER = Regex("\\d{1,9}(?:\\.\\d{1,2})?")
    private val DATEISH = Regex("^[\\d\\s.,%\\-/:年月日]+$")

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
        val t = raw?.replace(INVISIBLE, "")?.trim()?.replace('\u00A0', ' ')?.replace(Regex("\\s+"), " ")
        return t?.takeIf { it.isNotEmpty() }
    }

    /**
     * 标题合理性：
     *  - strict=false（一/二级，ID 已背书）：≥6 字、无货币符号、无换行、不在黑名单；
     *  - strict=true（三级启发式）：>8 字（旧阈值保留）、≤80、无冒号规格行特征、非纯数字/日期。
     */
    fun isPlausibleTitle(text: String, strict: Boolean): Boolean {
        val minLen = if (strict) 9 else 6
        if (text.length < minLen || text.length > if (strict) 80 else 120) return false
        if (CURRENCY_PATTERN.containsMatchIn(text)) return false
        if (text.contains('\n')) return false
        if (DATEISH.matches(text)) return false
        if (TITLE_BLACKLIST_WORDS.any { text.contains(it) }) return false
        if (strict && looksLikeSpecLine(text)) return false
        return true
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

    private val BUY_ACTION_WORDS = listOf(
        "加入购物车", "立即购买", "领券购买", "马上抢", "现在购买", "单独购买", "立即预约"
    )
    /**
     * 商详底栏专属"立购/预约"动作（列表卡片只有"加入购物车"图标，不含这些词）。
     *
     * 「立即预约」是 2026-09-29 真机第二例缺陷的补丁：预约/抢购型商品（茅台飞天需预约）
     * 底栏只有「立即预约」+「等待抢购」，没有「立即购买」，旧词表把它当非商详 → 浮窗不弹。
     * **只能收完整的按钮文案**：同一台真机的京东首页信息流里有「**抢先预约**」
     * （iQOO 16 福袋卡片 content-desc）和「**等待抢购**」（秒杀位），
     * 收「预约」「抢购」这类短子串会把首页判成商详。守门用例见
     * `RealDumpGatingTest."home feed reservation wording never reads as a bottom-bar buy action"`。
     */
    private val BUY_NOW_WORDS = listOf(
        "立即购买", "领券购买", "马上抢", "现在购买", "单独购买", "立即预约"
    )
    /**
     * 商详专属分区标记（列表页卡片的"查看详情"按钮不算）。
     *
     * 注意：**别把裸「详情」「推荐」收进来** —— 新版京东商详首屏顶部 tab 是
     * 「商品 / 大家评 / 详情 / 推荐」（2026-09-29 真机实测），裸「详情」会同时命中列表页的
     * 「查看详情」按钮。首屏没有本词表任何字样，所以门控不能指望这一关，
     * 兜底走 [BUY_NOW_WORDS]（见 isProductPage）。
     */
    private val DETAIL_SECTION_WORDS = listOf("商品详情", "宝贝详情", "图文详情", "商品评价", "宝贝评价")
    /** 购物车/确认订单页特征 */
    private val CHECKOUT_WORDS = listOf("去结算", "提交订单", "立即支付", "合计")

    fun isBuyAction(text: String): Boolean = BUY_ACTION_WORDS.any { text.contains(it) }

    fun isBuyNowAction(text: String): Boolean = BUY_NOW_WORDS.any { text.contains(it) }

    /** 商详分区标记：商品详情/宝贝详情/图文详情/商品评价（列表卡片的"查看详情"按钮不算） */
    fun isDetailSection(text: String): Boolean = DETAIL_SECTION_WORDS.any { text.contains(it) }

    fun isCheckoutContext(text: String): Boolean = CHECKOUT_WORDS.any { text.contains(it) }

    private val PDD_SINGLE_BUY = listOf("单独购买")
    private val PDD_GROUP_BUY = listOf("发起拼单", "立即拼单")

    fun hasPddSingleBuy(text: String): Boolean = PDD_SINGLE_BUY.any { text.contains(it) }
    fun hasPddGroupBuy(text: String): Boolean = PDD_GROUP_BUY.any { text.contains(it) }
}
