package com.pricelens.rules

import com.pricelens.accessibility.NodeSnapshot
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
        ruleDetect(root, packageName, rules, activityName)?.let { return DetectionOutcome.Hit(it) }
        return heuristic(root, platform, rules)
    }

    /** 规则路径：确认失败 / 价格解析失败都返回 null（= 交给启发式，绝不静默什么都不显示） */
    private fun ruleDetect(root: NodeSnapshot, packageName: String, rules: RuleSet, activityName: String?): Detection? {
        val rule = rules.ruleFor(packageName) ?: return null
        val result = RuleExtractor.extract(root, rule, activityName) ?: return null
        val priceHit = resolveRulePrice(root, result) ?: return null
        val page = result.page.name ?: "-"
        return Detection(
            price = priceHit,
            title = result.title,
            itemId = extractItemId(root),
            source = DetectionSource.RULE,
            matchedBy = "规则 ${result.ruleId}@v${result.ruleVersion}/$page [${result.describeFields()}]"
        )
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

    /** 老路径逐行保留（改造前的 PriceMonitorService.onAccessibilityEvent 分支顺序） */
    private fun heuristic(root: NodeSnapshot, platform: ShopPlatform, rules: RuleSet): DetectionOutcome {
        if (!isProductPage(root, platform)) return DetectionOutcome.NotProductPage
        val priceHit = extractPriceHit(root, platform) ?: return DetectionOutcome.NoPrice
        val titleHit = extractTitle(root, platform)
        return DetectionOutcome.Hit(
            Detection(
                price = priceHit,
                title = titleHit?.text,
                itemId = extractItemId(root),
                source = DetectionSource.HEURISTIC,
                matchedBy = if (rules.isEmpty) "启发式回落（无规则）" else "启发式回落（规则未命中）"
            )
        )
    }
}
