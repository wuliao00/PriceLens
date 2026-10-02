package com.pricelens.rules

/**
 * 规则合并 / 回落（纯函数，JVM 单测）。
 *
 * 生效优先级：远端（本轮同步、已过 sha256）> 磁盘缓存（上次同步、写入时已校验）> 内置 assets。
 * 任一来源缺某个平台 → 用低优先级的顶上；全都没有 → [RuleSet.EMPTY]
 * （判定管线随即退化为纯硬编码启发式 —— 保证"规则不可用时行为与改造前完全一致"）。
 */
object RuleMerge {

    fun effective(high: Map<String, PlatformRule>, low: Map<String, PlatformRule>): RuleSet {
        if (high.isEmpty() && low.isEmpty()) return RuleSet.EMPTY
        val merged = LinkedHashMap<String, PlatformRule>(low)
        merged.putAll(high) // 同 id 时 high 覆盖 low
        // 按 id 排序：派发顺序（ruleFor 的首个命中）不依赖 Map 迭代顺序，可复现
        return RuleSet(merged.values.sortedBy { it.id })
    }
}
