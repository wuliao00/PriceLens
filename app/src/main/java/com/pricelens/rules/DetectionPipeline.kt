package com.pricelens.rules

import com.pricelens.accessibility.NodeSnapshot
import com.pricelens.accessibility.PageVocabulary
import com.pricelens.accessibility.PriceBasis
import com.pricelens.accessibility.PriceHit
import com.pricelens.accessibility.PriceNodeMatcher
import com.pricelens.accessibility.ShopPlatform
import com.pricelens.accessibility.extractItemId
import com.pricelens.accessibility.extractPriceHit
import com.pricelens.accessibility.extractTitle
import com.pricelens.accessibility.isProductPage
import com.pricelens.accessibility.joinDecimalTail

/**
 * 商详页判定管线：**规则优先，规则没命中回落硬编码启发式**。
 *
 * 这是 [com.pricelens.accessibility.PriceMonitorService] 判定分支的唯一入口
 * （服务本身只剩"取根节点 → 调这里 → 按结果收窗/发事件"）。三条出口与改造前逐行同构：
 *  - [DetectionOutcome.NotProductPage]：非商详（首页/列表/购物车）→ 服务收窗并清签名；
 *  - [DetectionOutcome.NoPrice]：是商详但读不到价（加载中/改版）→ 服务仅在窗口切换事件收窗；
 *  - [DetectionOutcome.Hit]：有内容 → 去重签名 → 发事件。
 *
 * 不出现"规则没命中就什么都不显示"的退步：规则未确认（或确认了但价格解析不出来）时
 * **原封不动**走 `isProductPage` / `extractPriceHit` / `extractTitle` 老路径；
 * 两条路都命中时以规则为准（规则先评估，命中即返回）。
 */
object DetectionPipeline {

    /** 判定来源（进日志说明"是谁命中的"，规则可用性排查的第一现场） */
    enum class DetectionSource(val label: String) {
        RULE("规则命中"),
        HEURISTIC("启发式回落")
    }

    data class Detection(
        val price: PriceHit,
        val title: String?,
        val itemId: String?,
        val source: DetectionSource,
        /** 可读的命中明细（如 `jd@v1/product_detail [title=textRegex:…, price=textRegex:…]`） */
        val matchedBy: String
    )

    sealed interface DetectionOutcome {
        data object NotProductPage : DetectionOutcome
        data object NoPrice : DetectionOutcome
        data class Hit(val detection: Detection) : DetectionOutcome
    }

    /**
     * @param packageName 事件来源包名（规则按它派发，见 [RuleSet.ruleFor]）
     * @param activityName 仅窗口切换事件可拿到 Activity 名；内容变化事件传 null
     *  （[PageRule.matchesActivity] 对 null 放行，详见其注释）
     */
    fun detect(
        root: NodeSnapshot,
        platform: ShopPlatform,
        packageName: String,
        rules: RuleSet,
        activityName: String? = null
    ): DetectionOutcome {
        ruleDetect(root, platform, packageName, rules, activityName)?.let { return DetectionOutcome.Hit(it) }
        // 启发式路径的词表也跟随该宿主的规则：远端规则里改了 `gate` 就能救"电商 App 改文案"，
        // 不必再发一次 APK（2026-10-03 国补页那次发了 2.8.0.1）。没有规则、或规则没带 gate
        // 时退回出厂词表 —— 淘宝/拼多多至今没有规则包，它们一直走的就是出厂词表。
        val rule = rules.ruleFor(packageName)
        return heuristic(root, platform, rules, rule?.vocabulary ?: PageVocabulary.DEFAULT, rule != null)
    }

    /** 规则路径：确认失败 / 价格解析失败 / 拿不出可信标题都返回 null（= 交给启发式，绝不静默什么都不显示） */
    private fun ruleDetect(
        root: NodeSnapshot,
        platform: ShopPlatform,
        packageName: String,
        rules: RuleSet,
        activityName: String?
    ): Detection? {
        val rule = rules.ruleFor(packageName) ?: return null
        val result = RuleExtractor.extract(root, rule, activityName) ?: return null
        val priceHit = resolveRulePrice(root, result) ?: return null
        val page = result.page.name ?: "-"
        return Detection(
            price = priceHit,
            title = pickTitle(root, platform, result) ?: return null,
            itemId = extractItemId(root),
            source = DetectionSource.RULE,
            matchedBy = "规则 ${result.ruleId}@v${result.ruleVersion}/$page [${result.describeFields()}]"
        )
    }

    /**
     * 规则抽出的标题只是**候选**，必须过与启发式同一道合理性闸。
     *
     * 为什么必须补这一刀（2026-10-03 PLB110 真机 logcat 取证，用户报的第三个症状）：
     * 出厂规则 `jd@v1/product_detail` 的标题兜底选择器是 `textRegex: ^[^¥￥]{10,80}$`
     * —— 一条"任何 10~80 字符且不含货币符号的文本"的网（**这个 80 后来被证明低于真机分布**：
     * 六棵真机树的商品名实测 32/61/145/170 字，网改成 `{10,200}` 之后，第五棵用例
     * `jd_detail_guobu_popup_plb110_20261003.xml` 才拿到真商品名而不是促销行）。京东商详**加载中那一帧**，
     * 图上只有竖排提示「继 续 滑 动 查 看 图 文 详 情」（21 字符、无 ¥），于是：
     *   `A11Y 命中来源=规则命中 … title=继 续 滑 动 查 看 图 文 详 情` → 浮窗把它当商品名显示
     *   → 紧接着 `搜索开始: [继 续 滑 动 查 看 图 文 详 情]` 拿它去全网搜了一遍
     *     （当当/识货/什么值得买各拉一次 90KB HTML），还落进搜索历史。
     * 28cc529 当时只把闸加在启发式路径（[extractTitle]）上，规则先命中就直接 emit，
     * 所以那次修复对这个症状**没有生效**（真机复验才发现，见 §9.1 的更正）。
     *
     * 取舍：规则标题不可信时**保留规则的价格**（价格选择器是按 ID/正则精确写的，比启发式
     * 的"第一个带 ¥ 的文本"更准），只把标题换成启发式的打分结果；两边都拿不出可信标题时
     * 整条规则判为未命中 —— 与启发式路径同一条口径："读不出可信标题就不算这一页有可展示的
     * 单商品"，宁可晚一帧再弹，也不弹一个错标题。
     *
     * 第二棵真机树（10:42 雷神猎刃S）补掉了这道闸的第二个形态：规则抓到
     * `已选：【免费升级24G】猎刃S 14代i5HX|…`（SKU 选择态，带冒号），而那一页真正的商品名
     * 里带「【白条24期免息】」——`免息` 在标题黑名单里，整串否决会让启发式也读不出标题。
     * 所以闸门用的是 [PriceNodeMatcher.isDisplayableTitle]（多拒规格/选择态行），
     * 而黑名单那一边改成"先剥掉【…】徽章段再判、剥空了才否决"（理由与"为什么不剥裸写促销语"写在 BRACKET_BADGE 上）。
     *
     * 第三刀（第四棵真机树，13:29 雷神 MIX 国补页）：那道闸**放行了促销行**。规则抓到
     * `叠加以旧换新下单，可再减1964元` —— 17 字、无冒号、不含黑名单词，闸门管不着；
     * 同一棵树上启发式按分数抓到的是真商品名 `自营雷神（ThundeRobot）MIX-G 高性能…`。
     * 所以"过闸就赢"还不够，规则标题要与启发式结果用**同一把尺**
     * [PriceNodeMatcher.titleScore] 比完再定 —— 兜底选择器 `^[^¥￥]{10,80}$` 给的是
     * "BFS 里第一个像句子的东西"，那是树序的巧合，不是质量判断。
     *
     * 代价（改口：先前写的"正常路径不付额外开销"已经不成立）：现在每次规则命中都会跑一遍
     * [extractTitle]。它是一次 O(节点数) 的纯内存扫描，而 emit 前有签名去重、
     * 不是每个 a11y 事件都会走到这里，可以接受。
     */
    private fun pickTitle(root: NodeSnapshot, platform: ShopPlatform, result: RuleExtractResult): String? {
        val fromRule = result.title?.takeIf { PriceNodeMatcher.isDisplayableTitle(it) }
        val fromHeuristic = extractTitle(root, platform)?.text?.takeIf { PriceNodeMatcher.isDisplayableTitle(it) }
        if (fromRule == null) return fromHeuristic
        if (fromHeuristic == null) return fromRule
        // 平手取规则那份：maxByOrNull 返回**第一个**最大值，而 fromRule 排在前面
        return listOf(fromRule, fromHeuristic).maxByOrNull { PriceNodeMatcher.titleScore(it) }
    }

    /**
     * 规则价格 → [PriceHit]：
     *  - 复用老路径的"小数位分体拼接"（真机主价常渲染成「¥1838」+「.9」两个兄弟节点，
     *    只读前者会把 1838.9 报成 1838，属"浮窗显示不准确"）；
     *  - 口径（页面/券后/到手）从命中节点的完整文本判定，捕获组里的裸数字不带上下文；
     *  - 数值合理性过滤与老路径同尺（0.01..300000）。
     */
    private fun resolveRulePrice(root: NodeSnapshot, result: RuleExtractResult): PriceHit? {
        val hit = result.fields["price"] ?: return null
        val glued = joinDecimalTail(root, hit.node, hit.value)
        val text = glued?.first ?: hit.value
        val value = glued?.second ?: PriceNodeMatcher.extractPrice(text) ?: return null
        if (!PriceNodeMatcher.isPlausiblePriceValue(value)) return null
        val basis = PriceBasis.detect(hit.nodeText.ifBlank { text })
        return PriceHit(value, text, basis, viaKnownId = false)
    }

    /**
     * 老路径逐行保留（改造前的 PriceMonitorService.onAccessibilityEvent 分支顺序）。
     *
     * @param vocabularyFromRule 门控词表是不是来自该宿主的规则 `gate`（只影响日志文案，
     *  排查"改了规则怎么还不生效"时这条是第一个要看的字段）
     */
    private fun heuristic(
        root: NodeSnapshot,
        platform: ShopPlatform,
        rules: RuleSet,
        vocabulary: PageVocabulary,
        vocabularyFromRule: Boolean
    ): DetectionOutcome {
        if (!isProductPage(root, platform, vocabulary)) return DetectionOutcome.NotProductPage
        val priceHit = extractPriceHit(root, platform) ?: return DetectionOutcome.NoPrice
        val titleHit = extractTitle(root, platform)
        val gateNote = if (vocabularyFromRule) "，词表=规则 gate" else ""
        return DetectionOutcome.Hit(
            Detection(
                price = priceHit,
                title = titleHit?.text,
                itemId = extractItemId(root),
                source = DetectionSource.HEURISTIC,
                matchedBy = if (rules.isEmpty) "启发式回落（无规则）" else "启发式回落（规则未命中$gateNote）"
            )
        )
    }
}
