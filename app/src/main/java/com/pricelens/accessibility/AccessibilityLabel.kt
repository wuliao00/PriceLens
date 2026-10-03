package com.pricelens.accessibility

/**
 * 读屏（TalkBack）标签的清洗与"这是界面控件不是商品"的判定（纯 Kotlin，JVM 可测）。
 *
 * 为什么需要它（2026-10-03 真机撞出来的，OPPO PLB110 / Android 15 / 淘宝）：
 * 无障碍节点的 `contentDescription` 常常不是页面文字，而是**给读屏念的句子**——
 * 淘宝底部导航那个"购物车"的 cd 实测是 `购物车20，按钮`（"20" 是角标计数、"，按钮" 是角色后缀）。
 * 而商详标题的一/二级取法走的是"ID 语义白名单 + contentDescription"，长度门槛只有 6 字，
 * 于是这串界面外壳被当成商品标题：库里落下两条脏身份（`ovl:…` 标题"购物车，按钮"/"购物车20，按钮"，
 * 各自还绑了一个价格），搜索历史也被写进两条"购物车，按钮"。**2.8.0 同样有这个问题。**
 *
 * 两道防线分开做，别指望一道够：
 *  1. [stripRoles] 把角色/状态后缀剥掉（展示与匹配都更干净）；
 *  2. [looksLikeNavLabel] 把"导航项 + 角标数字"这种形态**判为不是商品标题**——
 *     只剥后缀不够：`购物车20` 剥完还是 6 个字，照样能过长度门槛。
 */
object AccessibilityLabel {

    /**
     * 读屏角色/状态后缀。Android 的 TalkBack 用「，」拼接，厂商 ROM 也有用「,」的，两种都吃。
     * 只吃**尾部**（标题中间出现"按钮"两字的商品名是真实存在的，不能整串删）。
     */
    private val ROLE_SUFFIX = Regex(
        "(?:，|,)\\s*(?:按钮|图片|图像|图标|已选中|未选中|可点击|已禁用|已隐藏|可双击|已勾选|未勾选|" +
            "复选框|单选按钮|单选框|开关|滑块|编辑框|文本框|搜索框|下拉列表|列表项|标签页|标签|链接|标题|" +
            "第\\s*\\d+\\s*个|共\\s*\\d+\\s*项|已展开|已折叠|可展开|已展开状态|第\\s*\\d+\\s*页|共\\s*\\d+\\s*页)" +
            "(?:\\s*(?:，|,)\\s*(?:按钮|图片|已选中|未选中|可点击|已禁用|列表项|标签页|标题))*$"
    )

    /** 角标计数：导航词后面常拖一个纯数字（购物车20 / 消息3 / 购物车 20 件） */
    private val TRAILING_COUNT = Regex("\\s*(?:\\d+|\\d+\\s*件|99\\+|\\d+\\.\\d+)\\s*$")

    /**
     * 电商 App 底部/顶部导航与工具位的固定词。判定是**整串相等**（先剥角色后缀、再剥尾部计数），
     * 不是包含——"我的健身计划""购物车收纳盒"这类真实商品名不能被误杀。
     */
    private val NAV_LABELS = setOf(
        "购物车", "首页", "我的", "淘宝", "消息", "订单", "收藏", "搜索", "视频", "分类", "推荐",
        "关注", "店铺", "客服", "返回", "拍照搜索", "扫一扫", "更多", "签到", "领现金", "拼小圈",
        "个人中心", "我的淘宝", "我的订单", "会员", "物流", "售后", "帮助", "设置"
    )

    /** 反复剥尾部角色后缀（`购物车，按钮，已选中` 这种叠了两个后缀的也要一次剥净） */
    fun stripRoles(raw: String): String {
        var s = raw.trim()
        while (true) {
            val next = ROLE_SUFFIX.replace(s, "").trim()
            if (next == s) return s
            s = next
        }
    }

    /** 这串是不是"界面导航/工具位"而不是商品名 */
    fun looksLikeNavLabel(text: String): Boolean {
        val stripped = stripRoles(text).trim()
        if (stripped.isEmpty()) return true
        if (NAV_LABELS.contains(stripped)) return true
        val noCount = TRAILING_COUNT.replace(stripped, "").trim()
        return noCount.isNotEmpty() && noCount != stripped && NAV_LABELS.contains(noCount)
    }

    /**
     * 剥完角色后缀还剩东西、且确实剥掉了什么 ⇒ 这串是读屏句子而不是页面原文。
     * 标题取法用它做二次拒绝：真商品名不会以"，按钮"结尾。
     */
    fun hadRoleSuffix(raw: String): Boolean = raw.trim() != stripRoles(raw)

    /**
     * 竖排文案被逐字拼接的形态（≥5 个单字、每字之间一个空格）。
     *
     * 真机取证（2026-10-03 PLB110 / 京东商详加载中那一帧）：`继 续 滑 动 查 看 图 文 详 情`。
     * 京东把"继续滑动查看图文详情"画成竖排，无障碍树里读出来就是逐字加空格 ——
     * 它 21 个字符、不含货币符号、没有任何黑名单词，所以**长度带和黑名单都挡不住**，
     * 而规则路径的兜底选择器 `^[^¥￥]{10,80}$` 会原样收下它。
     *
     * 判别依据是"每个词都只有一个字"：真实商品名的空格是分隔音节/规格段的
     * （「泸州老窖 窖龄30年 浓香型白酒」），不会出现整串单字。
     */
    private val VERTICAL_SPACED = Regex("^(?:\\S\\s){4,}\\S$")

    fun isVerticalSpacedText(text: String): Boolean = VERTICAL_SPACED.matches(text.trim())

    /**
     * 角色词**独立成词**挂在尾部（分隔符可以是逗号，也可以只是空格）。
     * [ROLE_SUFFIX] 只认「，按钮」这类带逗号的后缀，而真机同时存在「更多28 按钮」这种写法。
     */
    private val ROLE_TOKEN_TAIL = Regex(
        "[，,\\s](按钮|图片|图标|图像|已选中|未选中|可点击|已禁用|可双击|链接|标签页|标题)$"
    )

    /**
     * 句末标点。商品名是**短语**不是**句子**：出现「。」/「；」/省略号，说明抓到的是一段
     * 说明文字或价格免责声明（真机取证：「…以订单结算页的价格为准。若商家单独对价格进行
     * 说明的，以商家的表述为准」）。
     *
     * 故意不收「！」和「？」—— 电商商品名里带问号的营销写法真实存在，收了会误杀。
     */
    private val SENTENCE_PUNCT = Regex("[。；…]")

    /**
     * 这串是"给读屏念的说明句"或"一段正文"，不是商品名。
     *
     * 真机取证（淘宝商详视频态整棵树的文本就这几条）：
     * `视频，按钮。双击可暂停或播放视频。`、`图片，按钮。双击可进入详情页。`、`更多28 按钮`。
     * 这些串角色词后面还有话，[stripRoles] 的"只吃尾部"吃不动，必须整串拒绝。
     */
    fun looksLikeTalkbackText(text: String): Boolean {
        val s = text.trim()
        return ROLE_TOKEN_TAIL.containsMatchIn(s) || SENTENCE_PUNCT.containsMatchIn(s)
    }
}
