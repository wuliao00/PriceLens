package com.pricelens.rules

import com.pricelens.update.UpdateManifest
import org.json.JSONObject

/**
 * 规则 JSON 的严格解析（org.json 手写解析，不引序列化库 —— 与 update/ 同一条红线）。
 *
 * 原则：**任何可疑输入都拒绝，不猜**。远端内容是外部输入，坏规则宁可不要
 * （留在旧规则 / 内置规则上），也不能让一条半懂的规则去驱动浮窗。
 *
 * 拒绝项一览（全部有单测钉子）：
 *  - JSON 不可解析 / schemaVersion 未知 / 必需字段缺失或类型不对；
 *  - id、包名前缀、字段名的字符集与长度；包名前缀不允许空串（startsWith("") 会覆盖所有宿主）；
 *  - 正则编译失败、长度超限、捕获组号超过 groupCount；
 *  - confirm 引用了 extract 里不存在的字段，或 allOf/anyOf 都不存在（"任何页面都确认"）；
 *  - 数量上限（页面/字段/选择器/条目数）—— 防御远端塞超大规则拖垮无障碍主线程。
 *
 * 未知**字段**（不是未知取值）一律忽略：便于清单/规则向前兼容地加注释性字段。
 */
object RuleJson {

    /** 已知结构版本；不认识的版本一律拒绝（不猜语义） */
    const val SCHEMA_VERSION = 1

    private const val MAX_PAGES = 8
    private const val MAX_FIELDS_PER_PAGE = 16
    private const val MAX_SELECTORS_PER_FIELD = 8
    private const val MAX_PACKAGES = 8
    private const val MAX_RULE_ENTRIES = 16
    private const val MAX_ID_LENGTH = 32
    private const val MAX_VALUE_LENGTH = 300
    private const val MAX_REGEX_LENGTH = 200
    private const val MAX_ACTIVITY_REGEX_LENGTH = 120
    private const val MAX_FIELD_NAME_LENGTH = 24

    private val ID_RE = Regex("^[a-z0-9_-]{1,$MAX_ID_LENGTH}$")
    private val FILE_RE = Regex("^rules/[a-z0-9_-]{1,$MAX_ID_LENGTH}\\.json$")
    private val PACKAGE_RE = Regex("^[a-zA-Z0-9._]{3,100}$")
    private val FIELD_NAME_RE = Regex("^[a-zA-Z][a-zA-Z0-9_]{0,$MAX_FIELD_NAME_LENGTH}$")

    // ---------- 清单 ----------

    fun parseManifest(raw: String?): ManifestResult {
        if (raw.isNullOrBlank()) return ManifestResult.Rejected(RejectReason.EMPTY_BODY)
        val root = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return ManifestResult.Rejected(RejectReason.JSON_UNPARSEABLE)
        }
        if (root.intOrNull("schemaVersion") != SCHEMA_VERSION) {
            return ManifestResult.Rejected(RejectReason.UNKNOWN_SCHEMA)
        }
        val manifestVersion = root.longOrNull("manifestVersion")
            ?: return ManifestResult.Rejected(RejectReason.MISSING_FIELDS + ":manifestVersion")
        if (manifestVersion <= 0L) return ManifestResult.Rejected(RejectReason.MISSING_FIELDS + ":manifestVersion")
        val array = root.optJSONArray("rules")
            ?: return ManifestResult.Rejected(RejectReason.MISSING_FIELDS + ":rules")
        if (array.length() == 0 || array.length() > MAX_RULE_ENTRIES) {
            return ManifestResult.Rejected(RejectReason.BAD_RULES + ":count=${array.length()}")
        }
        val out = ArrayList<RuleManifestEntry>(array.length())
        val seen = HashSet<String>()
        for (i in 0 until array.length()) {
            val node = array.optJSONObject(i)
                ?: return ManifestResult.Rejected(RejectReason.BAD_RULES + ":#$i")
            val id = node.stringOrNull("id") ?: return ManifestResult.Rejected(RejectReason.BAD_RULES + ":#$i.id")
            if (!ID_RE.matches(id)) return ManifestResult.Rejected(RejectReason.BAD_RULES + ":id=$id")
            if (!seen.add(id)) return ManifestResult.Rejected(RejectReason.BAD_RULES + ":dup=$id")
            val file = node.stringOrNull("file") ?: return ManifestResult.Rejected(RejectReason.BAD_RULES + ":$id.file")
            if (!FILE_RE.matches(file)) return ManifestResult.Rejected(RejectReason.BAD_RULES + ":file=$file")
            val version = node.intOrNull("version") ?: return ManifestResult.Rejected(RejectReason.BAD_RULES + ":$id.version")
            if (version <= 0) return ManifestResult.Rejected(RejectReason.BAD_RULES + ":$id.version")
            val sha = node.stringOrNull("sha256")?.lowercase()
                ?: return ManifestResult.Rejected(RejectReason.BAD_RULES + ":$id.sha256")
            // 复用 update.json 的同一把 hex 尺子（"同一套信任模型"在代码层面的体现）
            if (!UpdateManifest.isSha256Hex(sha)) {
                return ManifestResult.Rejected(RejectReason.BAD_SHA256 + ":$id")
            }
            out.add(RuleManifestEntry(id, file, version, sha))
        }
        return ManifestResult.Valid(RuleManifest(manifestVersion, out))
    }

    // ---------- 单平台规则 ----------

    /**
     * 解析 rules/<id>.json。[expectedId] 非空时要求与文件内 `id` 一致
     * （磁盘文件按 id 命名、内置 assets 也按 id 命名，防错放/错改）。
     */
    fun parsePlatformRule(raw: String?, expectedId: String? = null): PlatformRuleResult {
        if (raw.isNullOrBlank()) return PlatformRuleResult.Rejected(RejectReason.EMPTY_BODY)
        val root = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return PlatformRuleResult.Rejected(RejectReason.JSON_UNPARSEABLE)
        }
        if (root.intOrNull("schemaVersion") != SCHEMA_VERSION) {
            return PlatformRuleResult.Rejected(RejectReason.UNKNOWN_SCHEMA)
        }
        val id = root.stringOrNull("id") ?: return PlatformRuleResult.Rejected(RejectReason.MISSING_FIELDS + ":id")
        if (!ID_RE.matches(id)) return PlatformRuleResult.Rejected(RejectReason.MISSING_FIELDS + ":id")
        if (expectedId != null && expectedId != id) {
            return PlatformRuleResult.Rejected(RejectReason.ID_MISMATCH + ":$expectedId!=$id")
        }
        val version = root.intOrNull("version") ?: return PlatformRuleResult.Rejected(RejectReason.MISSING_FIELDS + ":version")
        if (version <= 0) return PlatformRuleResult.Rejected(RejectReason.MISSING_FIELDS + ":version")

        val packagesArray = root.optJSONArray("packages")
            ?: return PlatformRuleResult.Rejected(RejectReason.MISSING_FIELDS + ":packages")
        if (packagesArray.length() == 0 || packagesArray.length() > MAX_PACKAGES) {
            return PlatformRuleResult.Rejected(RejectReason.BAD_PACKAGES + ":count=${packagesArray.length()}")
        }
        val packages = ArrayList<String>(packagesArray.length())
        for (i in 0 until packagesArray.length()) {
            val pkg = packagesArray.optString(i, "").trim()
            // 空前缀 startsWith 会匹配一切宿主 —— 必须拒绝（比其它字段检查更关键）
            if (!PACKAGE_RE.matches(pkg)) return PlatformRuleResult.Rejected(RejectReason.BAD_PACKAGES + ":$pkg")
            packages.add(pkg)
        }

        val pagesArray = root.optJSONArray("pages")
            ?: return PlatformRuleResult.Rejected(RejectReason.MISSING_FIELDS + ":pages")
        if (pagesArray.length() == 0 || pagesArray.length() > MAX_PAGES) {
            return PlatformRuleResult.Rejected(RejectReason.BAD_PAGES + ":count=${pagesArray.length()}")
        }
        val pages = ArrayList<PageRule>(pagesArray.length())
        for (i in 0 until pagesArray.length()) {
            val node = pagesArray.optJSONObject(i)
                ?: return PlatformRuleResult.Rejected(RejectReason.BAD_PAGES + ":#$i")
            when (val parsed = parsePage(node)) {
                is PageParseResult.Ok -> pages.add(parsed.page)
                is PageParseResult.Rejected ->
                    return PlatformRuleResult.Rejected("${RejectReason.BAD_PAGES}:#$i:${parsed.reason}")
            }
        }
        return PlatformRuleResult.Valid(PlatformRule(id, version, packages, pages))
    }

    private sealed interface PageParseResult {
        data class Ok(val page: PageRule) : PageParseResult
        data class Rejected(val reason: String) : PageParseResult
    }

    private fun parsePage(node: JSONObject): PageParseResult {
        val name = node.stringOrNull("name")
        val activityRegex = node.stringOrNull("activityRegex")
        if (activityRegex != null) {
            if (activityRegex.length > MAX_ACTIVITY_REGEX_LENGTH) return PageParseResult.Rejected("activityRegex.tooLong")
            if (!compiles(activityRegex)) return PageParseResult.Rejected("activityRegex.invalid")
        }
        val extractNode = node.optJSONObject("extract")
            ?: return PageParseResult.Rejected("extract.missing")
        if (extractNode.length() == 0 || extractNode.length() > MAX_FIELDS_PER_PAGE) {
            return PageParseResult.Rejected("extract.count=${extractNode.length()}")
        }
        val extract = LinkedHashMap<String, List<RuleSelector>>()
        for (field in extractNode.keys()) {
            if (!FIELD_NAME_RE.matches(field)) return PageParseResult.Rejected("extract.field=$field")
            val selectorsArray = extractNode.optJSONArray(field)
                ?: return PageParseResult.Rejected("extract.$field.notArray")
            if (selectorsArray.length() == 0 || selectorsArray.length() > MAX_SELECTORS_PER_FIELD) {
                return PageParseResult.Rejected("extract.$field.count=${selectorsArray.length()}")
            }
            val selectors = ArrayList<RuleSelector>(selectorsArray.length())
            for (s in 0 until selectorsArray.length()) {
                val selectorNode = selectorsArray.optJSONObject(s)
                    ?: return PageParseResult.Rejected("extract.$field.#$s")
                when (val parsed = parseSelector(selectorNode)) {
                    is SelectorParseResult.Ok -> selectors.add(parsed.selector)
                    is SelectorParseResult.Rejected ->
                        return PageParseResult.Rejected("extract.$field.#$s:${parsed.reason}")
                }
            }
            extract[field] = selectors
        }

        val confirmNode = node.optJSONObject("confirm") ?: return PageParseResult.Rejected("confirm.missing")
        val allOf = names(confirmNode, "allOf") ?: return PageParseResult.Rejected("confirm.allOf")
        val anyOf = names(confirmNode, "anyOf") ?: return PageParseResult.Rejected("confirm.anyOf")
        val noneOf = names(confirmNode, "noneOf") ?: return PageParseResult.Rejected("confirm.noneOf")
        val referenced = (allOf + anyOf + noneOf).toSet()
        val unknown = referenced - extract.keys
        if (unknown.isNotEmpty()) return PageParseResult.Rejected("confirm.unknownField=${unknown.joinToString(",")}")
        if (allOf.isEmpty() && anyOf.isEmpty()) return PageParseResult.Rejected("confirm.noPositiveCondition")
        if (noneOf.any { it in allOf } || noneOf.any { it in anyOf }) {
            return PageParseResult.Rejected("confirm.noneOfConflicts")
        }
        return PageParseResult.Ok(PageRule(name, activityRegex, extract, ConfirmRule(allOf, anyOf, noneOf)))
    }

    private sealed interface SelectorParseResult {
        data class Ok(val selector: RuleSelector) : SelectorParseResult
        data class Rejected(val reason: String) : SelectorParseResult
    }

    private fun parseSelector(node: JSONObject): SelectorParseResult {
        val by = node.stringOrNull("by")?.let { SelectorBy.fromWire(it) }
            ?: return SelectorParseResult.Rejected("unknownBy")
        val value = node.stringOrNull("value") ?: return SelectorParseResult.Rejected("value.missing")
        if (value.length > MAX_VALUE_LENGTH) return SelectorParseResult.Rejected("value.tooLong")
        val group = node.intOrNull("group") ?: 0
        if (group < 0) return SelectorParseResult.Rejected("group.negative")
        if (by == SelectorBy.TEXT_REGEX || by == SelectorBy.DESC_REGEX) {
            if (value.length > MAX_REGEX_LENGTH) return SelectorParseResult.Rejected("regex.tooLong")
            val groupCount = try {
                // Pattern 自身没有 groupCount（那是 Matcher 的方法）；用空串建一个 Matcher 取捕获组总数
                Regex(value).toPattern().matcher("").groupCount()
            } catch (_: Exception) {
                return SelectorParseResult.Rejected("regex.invalid")
            }
            if (group > groupCount) return SelectorParseResult.Rejected("group.outOfRange")
        } else if (group != 0) {
            return SelectorParseResult.Rejected("group.onlyForRegex")
        }
        return SelectorParseResult.Ok(RuleSelector(by, value, group))
    }

    /** confirm 的字符串数组字段：缺失=空表；类型不对=null（拒绝） */
    private fun names(node: JSONObject, key: String): List<String>? {
        val array = node.optJSONArray(key) ?: return if (node.has(key)) null else emptyList()
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val item = array.opt(i)
            if (item !is String || item.isBlank()) return null
            out.add(item)
        }
        return out
    }

    private fun compiles(pattern: String): Boolean = try {
        Regex(pattern)
        true
    } catch (_: Exception) {
        false
    }

    private const val ABSENT_INT = Int.MIN_VALUE
    private const val ABSENT_LONG = Long.MIN_VALUE

    private fun JSONObject.intOrNull(name: String): Int? {
        if (!has(name)) return null
        val value = optInt(name, ABSENT_INT)
        return if (value == ABSENT_INT) null else value
    }

    private fun JSONObject.longOrNull(name: String): Long? {
        if (!has(name)) return null
        val value = optLong(name, ABSENT_LONG)
        return if (value == ABSENT_LONG) null else value
    }

    private fun JSONObject.stringOrNull(name: String): String? {
        // 只认字符串：JSONObject.NULL / 数字 / 布尔一律按缺失处理（不把 "null" 字符串当真值）
        val value = opt(name) as? String ?: return null
        return value.trim().takeIf { it.isNotEmpty() }
    }
}

/** 清单解析结果：有效，或被拒绝的原因（拒绝 = 保留现有规则，不猜） */
sealed interface ManifestResult {
    data class Valid(val manifest: RuleManifest) : ManifestResult
    data class Rejected(val reason: String) : ManifestResult
}

/** 单平台规则解析结果 */
sealed interface PlatformRuleResult {
    data class Valid(val rule: PlatformRule) : PlatformRuleResult
    data class Rejected(val reason: String) : PlatformRuleResult
}

/** 机器可读拒绝原因（入日志与单测断言，不直接展示给用户） */
object RejectReason {
    const val EMPTY_BODY = "empty_body"
    const val JSON_UNPARSEABLE = "json_unparseable"
    const val MISSING_FIELDS = "missing_fields"
    const val UNKNOWN_SCHEMA = "unknown_schema"
    const val BAD_RULES = "bad_rules"
    const val BAD_SHA256 = "bad_sha256"
    const val BAD_PACKAGES = "bad_packages"
    const val BAD_PAGES = "bad_pages"
    const val ID_MISMATCH = "id_mismatch"
}
