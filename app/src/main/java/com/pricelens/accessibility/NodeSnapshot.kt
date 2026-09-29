package com.pricelens.accessibility

/**
 * 无障碍节点纯数据快照 + 纯函数判定层（A2 可测性改造）。
 *
 * 背景：`AccessibilityNodeInfo` 在 JVM 单测里无法构造（仓库无 mockito/Robolectric），
 * 此前整条"读标题/读价格"链路 0 测试。这里把节点信息压平成纯模型，
 * Service 侧只保留一个 `toSnapshot(node)` 薄适配（在 PriceMonitorService.kt），
 * 全部判定逻辑写成针对本模型的纯函数（extractTitle / extractPriceHit / isProductPage /
 * extractItemId / pickSameCardTitleAndPrice），表驱动单测见
 * `app/src/test/java/com/pricelens/accessibility/`。
 *
 * 测试夹具说明：单测快照均**手工按结构构造**，层级参照真机 `uiautomator dump`
 * 的商详页 XML（root → 卡片容器 → TextView 叶子），文本/ID 为真实商品页 dump 的脱敏改写；
 * 本文件不 import 任何 android.* 类，保证纯 JVM 可测。
 */
data class NodeSnapshot(
    /** AccessibilityNodeInfo.text */
    val text: String?,
    /** AccessibilityNodeInfo.contentDescription —— 旧实现全仓从不读它，商详主标题常挂在这里 */
    val contentDescription: String?,
    /** AccessibilityNodeInfo.className，如 android.widget.TextView */
    val className: String?,
    /** viewIdResourceName，如 com.jingdong.app.mall:id/jd_price */
    val resourceName: String?,
    /** isClickable 或 actionList 含 ACTION_CLICK */
    val clickable: Boolean,
    val children: List<NodeSnapshot> = emptyList()
)

/** 三个已知电商宿主（包名 → 平台映射） */
enum class ShopPlatform {
    JD, TAOBAO, PDD, UNKNOWN;

    companion object {
        fun fromPackage(pkg: String): ShopPlatform = when {
            pkg.startsWith("com.jingdong") -> JD
            pkg.startsWith("com.taobao") -> TAOBAO
            pkg.startsWith("com.xunmeng") -> PDD
            else -> UNKNOWN
        }
    }
}

/**
 * 价格口径（浮窗信息层级第①行的"口径标签"，绝不让裸数字冒充到手价）：
 *  - PAGE 页面价：页面直接展示的价
 *  - AFTER_COUPON 券后价：上下文文本明确带"券后"字样
 *  - NET 到手价：上下文文本明确带"到手"字样
 */
enum class PriceBasis {
    PAGE, AFTER_COUPON, NET;

    /** 从价格上下文文本判定口径；关键词优先级：到手 > 券后 > 页面 */
    companion object {
        fun detect(contextText: String?): PriceBasis {
            if (contextText == null) return PAGE
            return when {
                contextText.contains("到手") -> NET
                contextText.contains("券后") || contextText.contains("领券购买") -> AFTER_COUPON
                else -> PAGE
            }
        }
    }
}

/**
 * 识别依据（防错配硬规则的门闩）：
 *  - ITEM_ID：从页面节点拿到确定性商品 ID（item.jd.com 链接 / sku= 参数）→ 允许显示历史价/多平台比价
 *  - TITLE_ONLY：仅凭标题命中 → 浮窗降级为"识别到标题 · 点击在 App 内搜索"，
 *    绝不把别的 SKU 的历史价当当前商品价（对齐慢慢买官方 FAQ 口径）
 */
enum class DetectionBasis {
    ITEM_ID, TITLE_ONLY
}

/** 标题提取结果 */
data class TitleHit(
    val text: String,
    /** 一级：命中商详已知标题 resource-id 白名单 */
    val viaKnownId: Boolean,
    /** 文本取自 contentDescription 而非 text */
    val viaContentDescription: Boolean
)

/** 价格提取结果 */
data class PriceHit(
    val value: Double,
    val rawText: String,
    val basis: PriceBasis,
    /**
     * 是否命中已知主价 resource-id —— **置信加分项，不是必要条件**。
     * 2026-09-29 真机取证：现版京东商详页的 resource-id 全是混淆短名，
     * [PriceNodeMatcher.isKnownPriceId] 在这棵树上恒不命中，所以商详门控 [isProductPage]
     * 已不再依赖它（历史上曾把它当兜底的必要条件，直接导致真机商详页浮窗不弹）。
     * 保留字段的用途：旧版 App / 其他宿主仍能命中，可用于埋点与置信排序。
     */
    val viaKnownId: Boolean
)

/** 列表页"同一张商品卡片"的标题+价格配对结果 */
data class CardPick(
    val title: String,
    val price: PriceHit
)

/** BFS 序：父先于子、左先于右，近似视觉自上而下 */
internal fun bfs(root: NodeSnapshot): List<NodeSnapshot> {
    val out = ArrayList<NodeSnapshot>()
    val queue = ArrayDeque<NodeSnapshot>()
    queue.addLast(root)
    while (queue.isNotEmpty()) {
        val n = queue.removeFirst()
        out.add(n)
        n.children.forEach { queue.addLast(it) }
    }
    return out
}

/** 子树内所有可读文本（text + contentDescription），用于邻近关键词/语境判定 */
internal fun subtreeTexts(node: NodeSnapshot): List<String> {
    val out = ArrayList<String>()
    val queue = ArrayDeque<NodeSnapshot>()
    queue.addLast(node)
    while (queue.isNotEmpty()) {
        val n = queue.removeFirst()
        n.text?.let { if (it.isNotBlank()) out.add(it) }
        n.contentDescription?.let { if (it.isNotBlank()) out.add(it) }
        n.children.forEach { queue.addLast(it) }
    }
    return out
}

/** 自 root 到 target 的祖先链（含 target、含 root；按对象同一性查找；找不到返回空表） */
internal fun ancestorChain(root: NodeSnapshot, target: NodeSnapshot): List<NodeSnapshot> {
    val path = ArrayList<NodeSnapshot>()
    fun walk(n: NodeSnapshot): Boolean {
        path.add(n)
        if (n === target) return true
        for (c in n.children) if (walk(c)) return true
        path.removeAt(path.size - 1)
        return false
    }
    return if (walk(root)) path else emptyList()
}

private fun firstUsable(vararg values: String?): String? =
    values.firstOrNull { !it.isNullOrBlank() }

/**
 * 节点可用的标题文本：自身 text/contentDescription；容器节点（商详标题容器常自身无文本）
 * 再取**直接子节点**的 text/contentDescription。返回 Pair(清洗后文本, 是否来自 contentDescription)。
 */
private fun nodeTitleCandidates(node: NodeSnapshot): List<Pair<String, Boolean>> {
    val out = ArrayList<Pair<String, Boolean>>(4)
    PriceNodeMatcher.cleanTitle(node.text)?.let { out.add(it to false) }
    PriceNodeMatcher.cleanTitle(node.contentDescription)?.let { out.add(it to true) }
    for (c in node.children) {
        PriceNodeMatcher.cleanTitle(c.text)?.let { out.add(it to false) }
        PriceNodeMatcher.cleanTitle(c.contentDescription)?.let { out.add(it to true) }
    }
    return out
}

/**
 * 标题三级取法（A2 P0-1）：
 *  1. 商详已知标题 resource-id 白名单（text 优先，其次 contentDescription）；
 *  2. contentDescription 白名单（resourceName 语义含 title/name/goods/product）；
 *  3. 才退回"最长文本"启发式 —— 先经黑名单词（补贴/免息/退货/参数/评价/推荐/加入购物车…）
 *     与规格行/促销行过滤，且不再要求"不可点击"
 *     （商详主标题常挂在可点击容器上，旧实现的强约束反而必错过）。
 */
fun extractTitle(root: NodeSnapshot, platform: ShopPlatform): TitleHit? {
    val nodes = bfs(root)

    // 一级：已知标题 resource-id（容器自身无文本时允许直接子节点供文本）
    for (n in nodes) {
        if (!PriceNodeMatcher.isKnownTitleId(n.resourceName, platform)) continue
        for ((text, fromCd) in nodeTitleCandidates(n)) {
            if (PriceNodeMatcher.isPlausibleTitle(text, strict = false)) {
                return TitleHit(text, viaKnownId = true, viaContentDescription = fromCd)
            }
        }
    }

    // 二级：contentDescription 白名单（ID 语义命中，取 cd 文本）
    for (n in nodes) {
        if (!PriceNodeMatcher.matchesTitleSemantics(n.resourceName)) continue
        for ((text, fromCd) in nodeTitleCandidates(n)) {
            if (fromCd && PriceNodeMatcher.isPlausibleTitle(text, strict = false)) {
                return TitleHit(text, viaKnownId = false, viaContentDescription = true)
            }
        }
    }

    // 三级："最长文本"启发式（黑名单过滤 + 标题长度带打分后才参与）
    var best: String? = null
    var bestFromCd = false
    var bestScore = 0
    for (n in nodes) {
        for (raw in listOfNotNull(n.text, n.contentDescription)) {
            val text = PriceNodeMatcher.cleanTitle(raw) ?: continue
            if (!PriceNodeMatcher.isPlausibleTitle(text, strict = true)) continue
            var score = text.length.coerceAtMost(60)
            // 典型商品标题长度带加权；促销长句/参数行通常超出该带
            if (text.length in 12..45) score += 15
            if (PriceNodeMatcher.looksLikeSpecLine(text)) score -= 25
            if (n.clickable) score -= 4
            if (score > bestScore) {
                bestScore = score
                best = text
                bestFromCd = raw === n.contentDescription || raw == n.contentDescription
            }
        }
    }
    return best?.let { TitleHit(it, viaKnownId = false, viaContentDescription = bestFromCd) }
}

/**
 * 价格取法（A2 P0-2）：
 *  1. 优先已知主价 resource-id（按平台一套白名单，旧历史 ID 也保留兼容）；
 *  2. 否则取 BFS（≈页面自上而下）第一个"带 ¥/￥ 符号"且通过排除规则的价格文本 ——
 *     商详主价在页面上部，先出现；分期数/券面额/存储容量等裸数字不再被当价格
 *     （旧规则"任意 viewId + ≤10 位纯数字即判价"是缺陷根因）；
 *  3. 兜底：裸数字仅当其最近祖先（≤2 层卡片）文本里另有 ¥ 符号时才采信，
 *     并用数值范围 + ID 排除词（installment/coupon 等）二次过滤；
 *  4. 三条出口共用 [priceHitOf]：把真机上被拆成独立节点的**小数位**（「¥1838」+「.9」）拼回主价。
 * 输出带口径标签的 [PriceHit]（页面价/券后价/到手价）。
 */
fun extractPriceHit(root: NodeSnapshot, platform: ShopPlatform): PriceHit? {
    // 一级：已知主价 ID
    for (n in bfs(root)) {
        if (!PriceNodeMatcher.isKnownPriceId(n.resourceName, platform)) continue
        if (PriceNodeMatcher.isExcludedPriceId(n.resourceName)) continue
        val text = firstUsable(n.text, n.contentDescription) ?: continue
        if (PriceNodeMatcher.isPriceExcludedText(text)) continue
        val value = PriceNodeMatcher.extractPrice(text) ?: continue
        if (!PriceNodeMatcher.isPlausiblePriceValue(value)) continue
        return priceHitOf(root, n, text, value, viaKnownId = true)
    }
    // 二级：带 ¥/￥ 符号文本，BFS 第一个通过排除规则者
    for (n in bfs(root)) {
        if (n.resourceName?.let { PriceNodeMatcher.isExcludedPriceId(it) } == true) continue
        for (text in listOfNotNull(n.text, n.contentDescription)) {
            if (PriceNodeMatcher.isPriceExcludedText(text)) continue
            if (!text.contains('¥') && !text.contains('￥')) continue
            val value = PriceNodeMatcher.extractPrice(text) ?: continue
            if (!PriceNodeMatcher.isPlausiblePriceValue(value)) continue
            return priceHitOf(root, n, text, value, viaKnownId = false)
        }
    }
    // 三级：裸数字 + 同一祖先卡片（≤3 层）内有 ¥ 语境
    for (n in bfs(root)) {
        val text = n.text?.takeIf { it.isNotBlank() }
            ?: n.contentDescription?.takeIf { it.isNotBlank() }
            ?: continue
        if (PriceNodeMatcher.isPriceExcludedText(text)) continue
        if (!PriceNodeMatcher.isPureNumber(text)) continue
        if (n.resourceName?.let { PriceNodeMatcher.isExcludedPriceId(it) } == true) continue
        val value = PriceNodeMatcher.extractPrice(text) ?: continue
        if (!PriceNodeMatcher.isPlausiblePriceValue(value)) continue
        if (cardHasCurrency(root, n)) {
            return priceHitOf(root, n, text, value, viaKnownId = false)
        }
    }
    return null
}

/**
 * 一次价格读出的收口：拼回被单独成节点的小数位，再打口径标签。
 *
 * 真机（2026-09-29 商详页「茅台 2026年 飞天 500ml」¥1838.9）把主价渲染成
 * 「¥1838」+「.9」两个紧邻兄弟节点（整数大号字、小数小号字），只读前者会把 1838.9 报成 1838，
 * 属于用户抱怨的"浮窗显示不准确"的一种。见 `RealDetailShapeGateTest` 与
 * `PriceExtractionTest."split decimal tail is glued onto the integer part"`。
 */
private fun priceHitOf(
    root: NodeSnapshot,
    node: NodeSnapshot,
    text: String,
    value: Double,
    viaKnownId: Boolean
): PriceHit {
    val joined = joinDecimalTail(root, node, text)
        ?: return PriceHit(value, text, PriceBasis.detect(text), viaKnownId)
    return PriceHit(joined.second, joined.first, PriceBasis.detect(joined.first), viaKnownId)
}

/** 独立成节点的小数尾巴：只有 `.9` / `.90` 这种形态才认 */
private val DECIMAL_TAIL = Regex("^\\.\\d{1,2}$")

/** 把同一父节点内紧随价格节点之后的 `.dd` 尾巴并回主价文本；没有可拼的尾巴返回 null */
private fun joinDecimalTail(root: NodeSnapshot, node: NodeSnapshot, text: String): Pair<String, Double>? {
    if (text.contains('.')) return null // 主价文本自带小数，不需要拼
    val chain = ancestorChain(root, node)
    val parent = chain.getOrNull(chain.size - 2) ?: return null
    val idx = parent.children.indexOfFirst { it === node }
    if (idx < 0) return null
    for (sib in parent.children.asSequence().drop(idx + 1).take(2)) {
        val tail = firstUsable(sib.text, sib.contentDescription)?.trim()?.takeIf { DECIMAL_TAIL.matches(it) }
            ?: continue
        val joined = text + tail
        val joinedValue = PriceNodeMatcher.extractPrice(joined)
            ?.takeIf { PriceNodeMatcher.isPlausiblePriceValue(it) }
            ?: return null
        return joined to joinedValue
    }
    return null
}

/** 裸数字兜底语境判定：目标节点向上最多 3 层祖先的子树里，存在别的带 ¥ 文本 */
private fun cardHasCurrency(root: NodeSnapshot, target: NodeSnapshot): Boolean {
    val chain = ancestorChain(root, target)
    val parentIdx = chain.size - 2 // 链尾是 target 自己
    if (parentIdx < 0) return false
    for (i in parentIdx downTo maxOf(0, parentIdx - 2)) {
        val ancestor = chain[i]
        val hasCurrency = subtreeTexts(ancestor).any {
            (it.contains('¥') || it.contains('￥')) && !PriceNodeMatcher.isPriceExcludedText(it)
        }
        if (hasCurrency) return true
    }
    return false
}

/**
 * 确定性商品 ID 提取（防错配硬规则的输入）：
 * 扫全部 text/contentDescription，识别京东商详链接（含 m 站 `item.m.jd.com/product/…`）、
 * `sku=` / `goods_id=` / `id=` 参数。找不到返回 null（= 只能按 TITLE_ONLY 处理）。
 */
fun extractItemId(root: NodeSnapshot): String? {
    for (n in bfs(root)) {
        for (raw in listOfNotNull(n.text, n.contentDescription)) {
            PriceNodeMatcher.findJdSku(raw)?.let { return it }
        }
    }
    return null
}

/**
 * 商详页门控（A2 P1-5）：非商详（首页/搜索列表/购物车/确认订单）不 emit、并收窗。
 * 判定基于结构特征而非包名：
 *  - 购买动作信号：加入购物车/立即购买/领券购买/立即预约（京东、淘宝）
 *    或 单独购买+发起拼单 成对按钮（PDD 商详底栏；列表卡片只有"去拼单"不会成对出现）；
 *  - 购物车/确认订单特征（去结算/提交订单/立即支付/合计）一票否决；
 *  - 还需"商品详情/宝贝详情"等商详分区标记，**或**底栏"立即购买/立即预约"这类商详专属动作；
 *  - 必须同时能解析出价格与标题。
 *
 * 2026-09-29 真机修正（"商详页浮窗不弹"的根因）：兜底原本是 `hasBuyNow && priceHit.viaKnownId`，
 *  但现版京东商详页 76 个 distinct resource-id 全是混淆短名（dme/c_s/by2…），主价 ID 白名单
 *  一个都命中不了 → `viaKnownId` 恒 false；同时首屏顶部 tab 是「商品/大家评/详情/推荐」，
 *  没有 [PriceNodeMatcher] 的商详分区字样 → `hasDetailSection` 恒 false。两条兜底同时死掉，
 *  真机商详页首屏必被拒（滚到详情区后标题/主价节点又被 RecyclerView 回收出树，仍然拒）。
 *  现在兜底只看底栏动作信号：[PriceNodeMatcher.isBuyNowAction] 收的是**商详底栏专属**按钮文案，
 *  真机首页信息流卡片只有"加入购物车"图标（`RealDumpGatingTest` 里 buyNow=false 的实测钉子），
 *  且首页的"抢先预约/等待抢购"字样不在词表内，故首页/搜索页两棵真树仍不过门控。
 * 注：各 App 改版可能挪动按钮文案，词表集中在 [PriceNodeMatcher]，真机回归时按版本校准。
 */
fun isProductPage(root: NodeSnapshot, platform: ShopPlatform): Boolean {
    val texts = subtreeTexts(root)
    if (texts.isEmpty()) return false
    if (texts.any { PriceNodeMatcher.isCheckoutContext(it) }) return false
    val hasBuyAction = texts.any { PriceNodeMatcher.isBuyAction(it) }
    val pddPair = texts.any { PriceNodeMatcher.hasPddSingleBuy(it) } &&
        texts.any { PriceNodeMatcher.hasPddGroupBuy(it) }
    if (!hasBuyAction && !pddPair) return false
    // 读不出价就不是"有当前价的商品页"，标题也一样：门控要的是"这一页有可展示的单一商品"
    extractPriceHit(root, platform) ?: return false
    if (extractTitle(root, platform) == null) return false
    // 商详分区标记是最稳的信号；新版商详首屏没有该字样（真机实测），退而要求
    // "立即购买/领券购买/马上抢/立即预约"这类**商详底栏专属**动作。
    // viaKnownId 在这里只作为加分项参与置信（见 PriceHit），不再当必要条件：真机 id 全是混淆短名。
    val hasDetailSection = texts.any { PriceNodeMatcher.isDetailSection(it) }
    if (hasDetailSection) return true
    return texts.any { PriceNodeMatcher.isBuyNowAction(it) }
}

/**
 * 列表页"同一张商品卡片"取标题+价格（A2 可测性要求中的 pickSameCardTitleAndPrice）：
 * 旧实现在列表页把"第一张卡片价 + 全页最长商品名"拼成一条假数据。
 * 这里把两者约束在同一祖先卡片内：自 BFS 找第一个子树同时含
 * "标题级文本"与"¥ 价格文本"的可点击容器，卡片内成对返回。
 *
 * 当前版本商详门控已拦列表页（列表页不 emit），本函数作为纯函数先行落地并测试，
 * 为后续"列表页长按卡片比价"预留（见交付报告集成说明）。
 */
fun pickSameCardTitleAndPrice(root: NodeSnapshot, platform: ShopPlatform): CardPick? {
    for (n in bfs(root)) {
        if (!n.clickable) continue
        val texts = subtreeTexts(n)
        if (texts.none { PriceNodeMatcher.hasCurrency(it) }) continue
        if (n.children.isEmpty()) continue
        // 卡片规模上限：商品卡片子树不会太大；整页根容器（首页）文本过多时按长度带过滤
        val title = extractTitle(n, platform)?.text ?: continue
        if (PriceNodeMatcher.isPlausibleTitle(title, strict = true).not()) continue
        val price = extractPriceHit(n, platform) ?: continue
        if (title.length < 9) continue
        return CardPick(title, price)
    }
    return null
}
