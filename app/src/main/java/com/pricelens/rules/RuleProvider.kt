package com.pricelens.rules

/**
 * 进程内当前生效规则（无障碍服务在事件热路径上同步读它，不做任何 IO）。
 *
 * 写入只发生在 [RuleSyncRepository]：App 启动时"本地装载"一次、每次同步成功后一次。
 * 装载前的空集 = 纯启发式路径，与改造前行为一致（安全性优先于规则可用性）。
 */
object RuleProvider {

    @Volatile
    private var current: RuleSet = RuleSet.EMPTY

    fun snapshot(): RuleSet = current

    fun install(rules: RuleSet) {
        current = rules
    }
}
