package com.pricelens.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 合并 / 回落逻辑：远端（已校验）> 磁盘缓存 > 内置 assets；
 * 某来源缺平台就由低优先级顶上，全缺 = 空集（管线退化为纯启发式 = 改造前行为）。
 */
class RuleMergeTest {

    private fun rule(id: String, version: Int, packages: List<String> = listOf("com.$id")) = PlatformRule(
        id = id,
        version = version,
        packages = packages,
        pages = listOf(
            PageRule(
                name = "page",
                activityRegex = null,
                extract = mapOf("title" to listOf(RuleSelector(SelectorBy.TEXT, "x"))),
                confirm = ConfirmRule(allOf = listOf("title"))
            )
        )
    )

    @Test
    fun `high priority source wins on the same id`() {
        val remote = mapOf("jd" to rule("jd", 9))
        val disk = mapOf("jd" to rule("jd", 5))
        val effective = RuleMerge.effective(remote, disk)
        assertEquals(1, effective.rules.size)
        assertEquals(9, effective.ruleFor("com.jd")!!.version)
    }

    @Test
    fun `each id falls back independently`() {
        val high = mapOf("jd" to rule("jd", 9))
        val low = mapOf("taobao" to rule("taobao", 3), "pdd" to rule("pdd", 1))
        val effective = RuleMerge.effective(high, low)
        assertEquals(listOf("jd", "pdd", "taobao"), effective.rules.map { it.id })
        assertEquals(9, effective.ruleFor("com.jd")!!.version)
        assertEquals(3, effective.ruleFor("com.taobao")!!.version)
        assertEquals(1, effective.ruleFor("com.pdd")!!.version)
    }

    @Test
    fun `disk beats builtin and builtin fills the gaps`() {
        val disk = mapOf("jd" to rule("jd", 6))
        val builtin = mapOf("jd" to rule("jd", 1), "pdd" to rule("pdd", 1))
        val effective = RuleMerge.effective(disk, builtin)
        assertEquals(6, effective.ruleFor("com.jd")!!.version)
        assertEquals(1, effective.ruleFor("com.pdd")!!.version)
    }

    @Test
    fun `empty sources produce the empty set so the pipeline stays on heuristics`() {
        assertTrue(RuleMerge.effective(emptyMap(), emptyMap()).isEmpty)
    }

    @Test
    fun `rules are ordered by id for deterministic dispatch`() {
        val a = mapOf("zz" to rule("zz", 1))
        val b = mapOf("aa" to rule("aa", 1), "mm" to rule("mm", 1))
        assertEquals(listOf("aa", "mm", "zz"), RuleMerge.effective(a, b).rules.map { it.id })
        assertFalse(RuleMerge.effective(a, b).isEmpty)
    }
}
