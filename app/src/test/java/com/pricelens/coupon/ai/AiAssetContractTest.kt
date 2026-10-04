package com.pricelens.coupon.ai

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * `app/src/main/assets/ai/` 两份文本资产的契约测试（只读字符串，无新依赖、不 import android.*）。
 *
 * 为什么本版要测"给未来模型用的资产"：资产一旦进了 APK 就会被当成真有人在用。
 * 语法里有未定义符号 = llama.cpp 加载即失败；prompt 与 grammar 的字段次序不一致
 * = few-shot 教的是 A 顺序、约束解码强制 B 顺序，模型在第 3 个 token 就被截死。
 * 这两类错误都只能在接线之前用文本级自检抓住，所以逐条钉住。
 */
class AiAssetContractTest {

    /** 语法必须声明的 JSON 字段，与 prompt 槽位定义表一一对应 */
    private val requiredSlots = "platform coupons discount threshold scope state expiry url final list drop confidence".split(" ")

    // ---------- prompt 资产 ----------

    @Test
    fun `prompt keeps the three fixed sections in order`() {
        val body = AiPromptAsset.body(asset("coupon_prompt_v1.txt"))
        val slots = body.indexOf("【第 1 段 · 槽位定义】")
        val shots = body.indexOf("【第 2 段 · 示例】")
        val target = body.indexOf("【第 3 段 · 待抽取文案】")
        assertTrue("三段标记必须齐备且有序，实际 $slots/$shots/$target", slots in 0..shots && shots < target)
        // 恰好 3 条：数不对就红，逼着每次增删示例都过一遍这条断言（示例是模型的行为契约，不是注释）。
        // 锚行首：正文里"看示例 3"这类引用不许被数进来（v3 加指针时就撞过这个，非锚定版数出 4 条）
        assertEquals("few-shot 必须恰好 3 条", 3, Regex("(?m)^示例 \\d").findAll(body).count())
    }

    @Test
    fun `prompt documents the version and the decode parameters`() {
        val raw = asset("coupon_prompt_v1.txt")
        val directives = raw.lines().filter { AiPromptAsset.isDirective(it) }
        assertTrue("缺版本注释", directives.any { it.contains("ai-prompt-version") })
        assertTrue("缺 temperature=0", directives.any { it.contains(AiPromptAsset.ExpectedTemperature) })
        assertTrue("缺 max_new_tokens=256", directives.any { it.contains(AiPromptAsset.ExpectedMaxNewTokens) })
        // 元数据不许漏进正文：把温度写进 prompt 里模型不会执行，只会白占 token
        assertTrue(AiPromptAsset.body(raw).startsWith("【第 1 段"))
        assertEquals(0, AiPromptAsset.body(raw).lines().count { AiPromptAsset.isDirective(it) })
    }

    @Test
    fun `prompt defines all five amount roles and the state vocabulary`() {
        val body = AiPromptAsset.body(asset("coupon_prompt_v1.txt"))
        listOf("discount", "threshold", "final", "list", "drop").forEach {
            assertTrue("槽位定义表缺金额角色 $it", Regex("(?m)^ {2}$it ").containsMatchIn(body))
        }
        listOf("CLAIMABLE", "CLAIMED", "EXPIRED", "SOLD_OUT", "MEMBER_ONLY", "REGION_LIMITED", "UNKNOWN").forEach {
            assertTrue("状态词表缺 $it", body.contains(it))
        }
    }

    @Test
    fun `render binds the text at the single placeholder`() {
        val raw = asset("coupon_prompt_v1.txt")
        assertEquals(1, AiPromptAsset.placeholderCount(raw))
        val rendered = AiPromptAsset.render(raw, "满199减50，明天过期")
        assertTrue(rendered.contains("满199减50，明天过期"))
        assertEquals(0, AiPromptAsset.placeholderCount(rendered))
    }

    @Test
    fun `render refuses a template whose placeholder count is not one`() {
        // positive control 是上面那条真资产渲染成功；这里两种坏模板都必须抛而不是静默替换第一处
        listOf("头 {text} 中 {text} 尾", "没有占位符的模板").forEach { broken ->
            try {
                AiPromptAsset.render(broken, "x")
                fail("占位符数量不为 1 时 render 必须抛：$broken")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message!!.contains(AiPromptAsset.TextPlaceholder))
            }
        }
    }

    // ---------- grammar 资产 ----------

    @Test
    fun `schema grammar is self consistent`() {
        assertEquals(emptyList<String>(), GbnfScan.problems(asset("coupon_schema.gbnf")))
    }

    @Test
    fun `grammar declares every slot the prompt asks for`() {
        val keys = GbnfScan.fieldKeys(asset("coupon_schema.gbnf"), includeAllFields = true)
        requiredSlots.forEach { assertTrue("语法里没有 \"$it\" 字段", keys.contains(it)) }
    }

    @Test
    fun `grammar field order matches the few-shot outputs`() {
        val text = asset("coupon_schema.gbnf")
        val topLevel = GbnfScan.fieldKeys(text, includeAllFields = false, rule = "obj")
        val perCoupon = GbnfScan.fieldKeys(text, includeAllFields = false, rule = "coupon-obj")
        val body = AiPromptAsset.body(asset("coupon_prompt_v1.txt"))
        val outputs = Regex("(?m)^输出：(\\{.*)$").findAll(body).map { it.groupValues[1] }.toList()
        assertEquals(3, outputs.size)
        outputs.forEach { json ->
            assertEquals("顶层字段次序：语法与 few-shot 不一致", topLevel, GbnfScan.jsonKeys(json, 1))
            // distinct 是因为示例 1 有两张券，同一套字段次序会出现两遍；这里断言的是次序不是张数
            assertEquals("券字段次序：语法与 few-shot 不一致", perCoupon, GbnfScan.jsonKeys(json, 3).distinct())
        }
        // 次序断言的前提是语法真的排出了这些字段，否则上面两条会因两边都空而假绿
        assertEquals(listOf("platform", "coupons", "price", "confidence"), topLevel)
        assertEquals(listOf("discount", "threshold", "scope", "state", "expiry", "url"), perCoupon)
    }

    @Test
    fun `grammar states are a subset of the prompt vocabulary`() {
        val states = GbnfScan.literalsOf(asset("coupon_schema.gbnf"), "state-value")
        val prompt = AiPromptAsset.body(asset("coupon_prompt_v1.txt"))
        states.forEach { assertTrue("语法里的状态 $it 在 prompt 状态词表里找不到", prompt.contains(it)) }
        assertTrue("state-value 至少要枚举 7 个状态，实际 ${states.size}", states.size >= 7)
    }

    // ---------- 自检器自身：positive control ----------

    @Test
    fun `scanner accepts a sound grammar`() {
        val ok = "root ::= \"{\" ws leaf \"}\"\nleaf ::= \"a\" | \"b\"\nws ::= [ ]*"
        assertEquals(emptyList<String>(), GbnfScan.problems(ok))
    }

    @Test
    fun `scanner flags an undefined symbol and nothing else`() {
        val bad = "root ::= \"{\" ws ghost \"}\"\nws ::= [ ]*"
        val problems = GbnfScan.problems(bad)
        assertEquals("应当只报未定义符号这一条：$problems", 1, problems.size)
        assertTrue(problems.first().contains("ghost"))
    }

    @Test
    fun `scanner does not mistake literal text for a symbol reference`() {
        // 自检器的陷阱探针：字面量里的 ghost 不是符号引用，误判会让真语法永远报假错
        val ok = "root ::= \"ghost\" | real\nreal ::= \"x\""
        assertEquals(emptyList<String>(), GbnfScan.problems(ok))
    }

    @Test
    fun `scanner flags duplicates unreachable rules and empty productions`() {
        val duplicate = "root ::= leaf\nleaf ::= \"x\"\nleaf ::= \"y\""
        assertTrue(GbnfScan.problems(duplicate).single().contains("重复"))
        val orphan = "root ::= leaf\nleaf ::= \"x\"\nunused-thing ::= \"y\""
        assertTrue(GbnfScan.problems(orphan).single().contains("不可达"))
        val empty = "root ::= leaf\nleaf ::="
        assertTrue(GbnfScan.problems(empty).single().contains("空产生式"))
    }

    @Test
    fun `scanner reports a grammar without a root rule`() {
        val problems = GbnfScan.problems("entry ::= \"a\"")
        assertTrue("缺 root 必须报错：$problems", problems.any { it.contains("root") })
    }

    @Test
    fun `missing asset fails loudly instead of returning an empty string`() {
        try {
            asset("no_such_asset.txt")
            fail("不存在的资产必须报错")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("no_such_asset.txt"))
        }
    }

    private fun assetDir(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val fromModule = File(dir, "src/main/assets/ai")
            if (fromModule.isDirectory) return fromModule
            val fromRoot = File(dir, "app/src/main/assets/ai")
            if (fromRoot.isDirectory) return fromRoot
            dir = dir.parentFile
        }
        error("没找到 assets/ai 目录（CWD=${File(".").absolutePath}）")
    }

    private fun asset(name: String): String {
        val file = File(assetDir(), name)
        if (!file.isFile) error("找不到 AI 资产 $name（应为 ${file.absolutePath}）")
        return file.readText()
    }

    /**
     * 只读字符串的 GBNF 自检器。逐行扫描：`#` 截断行注释，`"..."`、`[...]`、`/.../` 的内容
     * 整体替换成一个空格，剩下的标识符才是符号引用。
     *
     * 它只回答"这份语法的符号是否自洽（定义齐全、无孤儿、无重复、能到 root）"，
     * 不替代 llama.cpp 的 grammar-parser：语法能不能解码，要等有设备的版本用真模型跑。
     */
    private object GbnfScan {

        private val RULE = Regex("^([A-Za-z][A-Za-z0-9_-]*)[ \t]*::=[ \t]*(.*)$")
        private val SYMBOL = Regex("[A-Za-z][A-Za-z0-9_-]*")
        private const val ROOT = "root"
        private const val FIELD_SUFFIX = "-field"

        /** 行内终端扫描：字面量与字符类的内容不进符号集合 */
        fun scrub(line: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    c == '#' -> i = line.length
                    c == '"' || c == '[' || c == '/' -> {
                        out.append(' ')
                        i = skipTerminal(line, i + 1, closerFor(c))
                    }
                    else -> {
                        out.append(c)
                        i++
                    }
                }
            }
            return out.toString()
        }

        private fun closerFor(open: Char): Char = when (open) {
            '"' -> '"'
            '[' -> ']'
            else -> '/'
        }

        private fun skipTerminal(text: String, from: Int, closer: Char): Int {
            var i = from
            while (i < text.length) {
                if (text[i] == '\\') {
                    i += 2
                    continue
                }
                if (text[i] == closer) return i + 1
                i++
            }
            return text.length
        }

        /** 规则名 → 产生式右侧原文；重复定义时保留第一条并另行记账 */
        private fun bodies(text: String, defects: MutableList<String>): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            text.lines().forEachIndexed { index, raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith(AiPromptAsset.DirectivePrefix)) return@forEachIndexed
                val match = RULE.matchEntire(line)
                if (match == null) {
                    defects.add("第 ${index + 1} 行不是产生式：$raw")
                    return@forEachIndexed
                }
                val name = match.groupValues[1]
                val body = match.groupValues[2].trim()
                if (body.isEmpty()) defects.add("第 ${index + 1} 行是空产生式：$name")
                if (name in out) defects.add("符号 $name 重复定义（第 ${index + 1} 行）") else out[name] = body
            }
            return out
        }

        /** 产生式右侧的**符号引用**：先把字面量与字符类扫描掉，剩下的标识符才是引用 */
        private fun refsOf(body: String): List<String> = SYMBOL.findAll(scrub(body)).map { it.value }.toList()

        /** 某个规则的原始行文本 */
        private fun rawRule(text: String, rule: String): String? = text.lines().firstOrNull { nameOf(it) == rule }

        private fun nameOf(line: String): String? = RULE.matchEntire(line.trim())?.groupValues?.get(1)

        fun bodyOf(text: String, rule: String): String = rawRule(text, rule)?.substringAfter("::=").orEmpty().trim()

        /** 某条规则右侧的全部字面量（GBNF 转义已还原，外引号已剥掉） */
        fun literalsOf(text: String, rule: String): List<String> = literals(bodyOf(text, rule)).map(::unquote)

        fun refs(text: String, rule: String): List<String> = refsOf(bodyOf(text, rule))

        /**
         * 对象规则的 JSON 字段次序。
         * [includeAllFields] 为 true 时不找规则，而是列出全部 `*-field` 的字面量名（供"字段齐不齐"断言）。
         */
        fun fieldKeys(text: String, includeAllFields: Boolean, rule: String = ""): List<String> {
            val names = if (includeAllFields) {
                val defects = mutableListOf<String>()
                bodies(text, defects).keys.filter { it.endsWith(FIELD_SUFFIX) }
            } else {
                refs(text, rule).filter { it.endsWith(FIELD_SUFFIX) }
            }
            return names.mapNotNull { literals(rawRule(text, it)).firstOrNull()?.let(::unquote) }
        }

        /** GBNF 里字段名写成 `"\"platform\""`，还原后是带引号的 `"platform"`（JSON 里引号是要匹配的字符） */
        private fun unquote(literal: String): String = literal.removeSurrounding("\"")

        /** 一行里的全部双引号字面量，`\x` 序列还原成 x（所以 `"\"platform\""` 读出 "platform" 含引号） */
        fun literals(line: String?): List<String> {
            if (line == null) return emptyList()
            val out = mutableListOf<String>()
            var i = 0
            while (i < line.length) {
                if (line[i] != '"') {
                    i++
                    continue
                }
                val token = StringBuilder()
                var j = i + 1
                while (j < line.length && line[j] != '"') {
                    if (line[j] == '\\' && j + 1 < line.length) {
                        token.append(line[j + 1])
                        j += 2
                    } else {
                        token.append(line[j])
                        j++
                    }
                }
                out.add(token.toString())
                i = j + 1
            }
            return out
        }

        /** JSON 字符串在给定嵌套深度的键次序（1 = 顶层对象，3 = coupons 数组里的券对象） */
        fun jsonKeys(json: String, depth: Int): List<String> {
            val keys = mutableListOf<String>()
            var level = 0
            var i = 0
            while (i < json.length) {
                when (json[i]) {
                    '{', '[' -> {
                        level++
                        i++
                    }
                    '}', ']' -> {
                        level--
                        i++
                    }
                    '"' -> {
                        val end = json.indexOf('"', i + 1)
                        if (end < 0) return keys
                        var j = end + 1
                        while (j < json.length && json[j].isWhitespace()) j++
                        if (j < json.length && json[j] == ':' && level == depth) keys.add(json.substring(i + 1, end))
                        i = end + 1
                    }
                    else -> i++
                }
            }
            return keys
        }

        /** 自洽性检查结果；空表 = 通过 */
        fun problems(text: String): List<String> {
            val defects = mutableListOf<String>()
            val map = bodies(text, defects)
            map.forEach { (name, body) ->
                refsOf(body).distinct().forEach {
                    if (it !in map) defects.add("符号 $name 引用了未定义的 $it")
                }
            }
            if (ROOT !in map) {
                defects.add("语法缺少 $ROOT 规则")
                return defects.distinct()
            }
            val reached = linkedSetOf(ROOT)
            val pending = ArrayDeque(listOf(ROOT))
            while (pending.isNotEmpty()) {
                refsOf(map[pending.removeFirst()].orEmpty()).forEach { ref -> if (reached.add(ref)) pending.addLast(ref) }
            }
            map.keys.filter { it !in reached }.forEach { defects.add("符号 $it 从 $ROOT 不可达") }
            return defects.distinct()
        }
    }
}
