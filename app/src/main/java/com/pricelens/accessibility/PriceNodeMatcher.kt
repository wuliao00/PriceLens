package com.pricelens.accessibility

/**
 * §1.2 已知 APP 价格控件 ID 映射 + 价格文本启发式判断。
 *
 * 三层判定（优先级从高到低）：
 *  1. viewIdResourceName 命中已知价格控件 ID 表 → 精准定位（不误读评论里的价格）
 *  2. 已知电商包名 + 文本形如 "¥123.45" → 兜底
 *  3. 其他 → 不判定为价格
 */
object PriceNodeMatcher {

    // 京东
    private val JD_PRICE_IDS = setOf(
        "com.jingdong.app.mall:id/jd_price",
        "com.jingdong.app.mall:id/new_price",
        "com.jd.lib.productdetail:id/price_tv"
    )

    // 淘宝
    private val TB_PRICE_IDS = setOf(
        "com.taobao.taobao:id/detail_price",
        "com.taobao.taobao:id/tv_price"
    )

    // 拼多多
    private val PDD_PRICE_IDS = setOf(
        "com.xunmeng.pinduoduo:id/goods_price",
        "com.xunmeng.pinduoduo:id/tv_price"
    )

    private val KNOWN_PRICE_IDS = JD_PRICE_IDS + TB_PRICE_IDS + PDD_PRICE_IDS

    private val KNOWN_APP_PREFIXES = listOf(
        "com.jingdong", "com.taobao", "com.xunmeng"
    )

    private val PRICE_TEXT = Regex(".*[¥￥]\\s*\\d+.*")
    private val PURE_NUMBER = Regex("\\d+\\.?\\d*")

    fun isKnownApp(packageName: String): Boolean =
        KNOWN_APP_PREFIXES.any { packageName.startsWith(it) }

    fun isPriceNode(viewId: String?, text: String?): Boolean {
        if (viewId != null && viewId in KNOWN_PRICE_IDS) {
            return text != null && extractPrice(text) != null
        }
        if (viewId == null || text == null) return false
        val inKnownApp = KNOWN_APP_PREFIXES.any { viewId.startsWith(it) }
        val looksLikePrice = text.matches(PRICE_TEXT) ||
            (text.length <= 10 && text.matches(PURE_NUMBER))
        return inKnownApp && looksLikePrice
    }

    /** "¥7,999" / "7999.00" / "到手价￥129" → 129.0；解析失败返回 null */
    fun extractPrice(text: String): Double? {
        val m = Regex("[¥￥]\\s*([\\d,]+(?:\\.\\d+)?)").find(text)
            ?: Regex("^\\s*([\\d,]+(?:\\.\\d+)?)\\s*(元)?$").find(text)
            ?: return null
        return m.groupValues[1].replace(",", "").toDoubleOrNull()?.takeIf { it > 0.0 }
    }
}
