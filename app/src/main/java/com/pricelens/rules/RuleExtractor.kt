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
 *  - 每个字段的多个选择器是 fallback 链，按顺序尝试，**在树序（BFS，父先于子、左先于右）里
 *    第一个命中的节点胜出**；
 *  - 文本一律先过 [PriceNodeMatcher.cleanTitle]（删零宽字符）再匹配与输出 —— 真机京东用
 *    U+200B 填充文案，不清洗会"规则明明写了还是不命中"（见 InvisibleTextSanitizingTest）；
 *  - 全部字段抽完后校验 [ConfirmRule]；确认失败返回 null（= 规则未命中，由管线回落到启发式）；
 *  - 永不抛异常：正则运行时异常、空树、null 文本全部按"未命中"处理。
 */
object RuleExtractor {

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
        for (node in bfs(root)) {
            val hit = when (selector.by) {
                SelectorBy.VIEW_ID -> matchViewId(node, selector)
                SelectorBy.TEXT -> clean(node.text)?.takeIf { it == selector.value }
                SelectorBy.DESC -> clean(node.contentDescription)?.takeIf { it == selector.value }
                SelectorBy.TEXT_REGEX -> regexGroup(regex, clean(node.text), selector)
                SelectorBy.DESC_REGEX -> regexGroup(regex, clean(node.contentDescription), selector)
            } ?: continue
            val nodeText = clean(node.text) ?: clean(node.contentDescription) ?: continue
            return RuleFieldHit(field, hit, nodeText, selector, node)
        }
        return null
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
