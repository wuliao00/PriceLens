package com.pricelens.rules

import com.pricelens.accessibility.NodeSnapshot
import com.pricelens.accessibility.PriceNodeMatcher
import com.pricelens.accessibility.bfs

/**
 * 提取引擎（设计文档 §3.4 的思路，输入换成仓库既有的 [NodeSnapshot] 纯模型）：
 * 纯函数，不碰 `AccessibilityNodeInfo`，JVM 可测。
 *
 * 契约：
 *  - 页面按声明顺序尝试，[PageRule.matchesActivity] 不通过就整体跳过；
 *  - 每个字段的多个选择器是 fallback 链，按顺序尝试；链内命中谁，分两种口径：
 *    · **title 取全树 [PriceNodeMatcher.titleScore] 最高的那个**（[TITLE_FIELD] 写了为什么）；
 *    · 其余字段取树序（BFS，父先于子、左先于右）里第一个命中的 —— price/buyNow/checkout 的语义
 *      就是「页面上先出现的那个」，改成最高分反而会把划线价与到手价混掉；
 *  - 文本一律先过 [PriceNodeMatcher.cleanTitle]（删零宽字符）再匹配与输出 —— 真机京东用
 *    U+200B 填充文案，不清洗会"规则明明写了还是不命中"（见 InvisibleTextSanitizingTest）；
 *  - 全部字段抽完后校验 [ConfirmRule]；确认失败返回 null（= 规则未命中，由管线回落到启发式）；
 *  - 永不抛异常：正则运行时异常、空树、null 文本全部按"未命中"处理。
 */
object RuleExtractor {

    /**
     * 只有这个字段走「全树最高分」。
     *
     * 真机取证（2026-10-03 21:45 PLB110，国补商品叠「领取国家补贴」半屏弹层那一帧）：
     * 真商品名挂在 **depth=23**，而促销行 `当前地区可领，本单可减1500元` 在 **depth=8**，
     * BFS 先撞到浅的那条 ⇒ 浮窗显示促销语，并且**拿它去全网搜了一遍**
     * （当当/什么值得买/识货各一次，logcat 为证）。同一条兜底网 `^[^¥￥]{10,N}$` 里两条都合法，
     * 所以这不是词表问题、也不是长度上限问题（80→200 实测救不回来），是「第一个命中」这个口径
     * 本身的问题：树序是渲染层结构的巧合，不是「哪个更像商品名」的判断。
     * 分数用 [PriceNodeMatcher.titleScore] —— 与启发式路径、与
     * [com.pricelens.rules.DetectionPipeline.pickTitle] 的比对，三处共用同一把尺。
     */
    private const val TITLE_FIELD = "title"

    fun extract(root: NodeSnapshot, rule: PlatformRule, activityName: String? = null): RuleExtractResult? {
        for (page in rule.pages) {
            if (!page.matchesActivity(activityName)) continue
            val fields = LinkedHashMap<String, RuleFieldHit>()
            for ((name, selectors) in page.extract) {
                val hit = selectors.firstNotNullOfOrNull { matchField(root, name, it) } ?: continue
                fields[name] = hit
            }
            if (page.confirm.holds(fields.keys)) {
                return RuleExtractResult(rule.id, rule.version, page, fields)
            }
        }
        return null
    }

    private fun matchField(root: NodeSnapshot, field: String, selector: RuleSelector): RuleFieldHit? {
        val regex = when (selector.by) {
            SelectorBy.TEXT_REGEX, SelectorBy.DESC_REGEX ->
                runCatching { Regex(selector.value) }.getOrNull() ?: return null
            else -> null
        }
        var best: RuleFieldHit? = null
        for (node in bfs(root)) {
            val hit = when (selector.by) {
                SelectorBy.VIEW_ID -> matchViewId(node, selector)
                SelectorBy.TEXT -> clean(node.text)?.takeIf { it == selector.value }
                SelectorBy.DESC -> clean(node.contentDescription)?.takeIf { it == selector.value }
                SelectorBy.TEXT_REGEX -> regexGroup(regex, clean(node.text), selector)
                SelectorBy.DESC_REGEX -> regexGroup(regex, clean(node.contentDescription), selector)
            } ?: continue
            val nodeText = clean(node.text) ?: clean(node.contentDescription) ?: continue
            val candidate = RuleFieldHit(field, hit, nodeText, selector, node)
            if (field != TITLE_FIELD) return candidate
            // 严格大于：同分时保留树序更靠前的那条（平手不改变既有行为）
            val previous = best
            if (previous == null || PriceNodeMatcher.titleScore(hit) > PriceNodeMatcher.titleScore(previous.value)) {
                best = candidate
            }
        }
        return best
    }

    /** viewId：全限定名精确匹配；或只写 `:id/` 后的名字段（与主价白名单的"名后缀"策略同源） */
    private fun matchViewId(node: NodeSnapshot, selector: RuleSelector): String? {
        val resourceName = node.resourceName ?: return null
        val matched = resourceName == selector.value ||
            (
                !selector.value.contains(":id/") &&
                    resourceName.substringAfterLast(":id/", missingDelimiterValue = "") == selector.value
                )
        if (!matched) return null
        return clean(node.text) ?: clean(node.contentDescription)
    }

    /**
     * 正则命中：group=0 取整个匹配；group>0 取第 n 个捕获组。
     * 组存在但为空（如 `(a)?` 未匹配）视为**该节点不命中**，继续扫下一个节点 ——
     * 返回空串会让空值冒充有效字段去满足 confirm。
     */
    private fun regexGroup(regex: Regex?, source: String?, selector: RuleSelector): String? {
        if (regex == null || source == null) return null
        val match = regex.find(source) ?: return null
        if (selector.group == 0) return match.value
        return match.groupValues.getOrNull(selector.group)?.takeIf { it.isNotEmpty() }
    }

    private fun clean(raw: String?): String? = PriceNodeMatcher.cleanTitle(raw)
}
