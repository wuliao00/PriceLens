package com.pricelens.rules

import com.pricelens.accessibility.NodeSnapshot

/**
 * 选择器规则数据模型（GKD 式远程订阅 · 设计文档 §三 的客户端侧落地）。
 *
 * 事实基线（以本仓库现实为准，见 docs/API.md「浮窗为什么常常只能到 TITLE_ONLY」）：
 * 现版京东商详页的 resource-id 全是混淆短名，语义 ID 恒不命中 —— 所以规则引擎的
 * 价值主线是 `textRegex` / `descRegex` 这类**文本选择器 + 页面确认条件**；
 * [SelectorBy.VIEW_ID] 保留（旧版/其他宿主/未来改版可能重新可用），但允许永不命中。
 *
 * 本文件不 import 任何 Android 框架类（除纯数据类 [NodeSnapshot]），org.json 解析在
 * [RuleJson]，可在 JVM 单测里直接跑。
 */

/** 选择器类型（wire 名与设计文档 §3.2 一致；org.json 手写解析，不引序列化库） */
enum class SelectorBy(val wireName: String) {
    VIEW_ID("viewId"),
    TEXT("text"),
    TEXT_REGEX("textRegex"),
    DESC("desc"),
    DESC_REGEX("descRegex");

    companion object {
        fun fromWire(name: String): SelectorBy? = entries.firstOrNull { it.wireName == name }
    }
}

/**
 * 单个选择器：
 *  - [by] 五种之一；
 *  - [value] 匹配目标（viewId 可写全限定名，也可只写 `:id/` 后的名字段 —— 与
 *    `PriceNodeMatcher.isKnownPriceId` 的"名后缀"策略同源，跨包壳改版存活）；
 *  - [group] 仅正则类生效：0=整个匹配，>0=第 n 个捕获组（组为空视为不命中，
 *    继续扫下一个节点；解析期已校验不超过 [Regex.groupCount]）。
 */
data class RuleSelector(val by: SelectorBy, val value: String, val group: Int = 0) {

    /** 日志/诊断用的可读描述（"谁命中的"直接读它） */
    fun describe(): String = buildString {
        append(by.wireName).append(':').append(value)
        if (group > 0) append('#').append(group)
    }
}

/**
 * 页面确认条件（引用 extract 里的字段名，语义与设计文档 §3.2 一致并补一个 [noneOf]）：
 *  - [allOf] 全部字段必须命中；
 *  - [anyOf] 非空时至少命中一个；
 *  - [noneOf] 任一命中即否决（购物车/确认订单页面的"去结算/提交订单/合计"这类
 *    反向特征 —— 对齐 [com.pricelens.accessibility.isProductPage] 的一票否决）。
 *
 * 解析期强制 [allOf]/[anyOf] 至少有一个非空：[noneOf] 单独存在的规则会"任何页面都确认"，
 * 那等于把浮窗开给首页/搜索页，比设计文档更严是刻意的。
 */
data class ConfirmRule(
    val allOf: List<String> = emptyList(),
    val anyOf: List<String> = emptyList(),
    val noneOf: List<String> = emptyList()
) {

    fun holds(fields: Set<String>): Boolean {
        if (!allOf.all { it in fields }) return false
        if (anyOf.isNotEmpty() && anyOf.none { it in fields }) return false
        if (noneOf.any { it in fields }) return false
        return true
    }
}

/**
 * 页面规则：一个平台可挂多页（页面间为顺序 fallback，命中即返回）。
 *
 * [activityRegex] 为 null 时不过滤窗口；[matchesActivity] 在拿不到 Activity 名时
 * **放行**（内容变化事件的 className 是 View 类名而非 Activity 名，若按"不匹配"处理，
 * 规则会在绝大多数事件上被静默跳过）。
 */
data class PageRule(
    val name: String?,
    val activityRegex: String?,
    val extract: Map<String, List<RuleSelector>>,
    val confirm: ConfirmRule
) {

    fun matchesActivity(activityName: String?): Boolean {
        val pattern = activityRegex ?: return true
        if (activityName.isNullOrBlank()) return true
        return Regex(pattern).containsMatchIn(activityName)
    }
}

/** 单平台规则（对应 rules/<id>.json 一个文件） */
data class PlatformRule(
    val id: String,
    val version: Int,
    val packages: List<String>,
    val pages: List<PageRule>
) {

    /** `packages` 是包名前缀表（与 `PriceNodeMatcher.isKnownApp` 的 startsWith 口径一致） */
    fun covers(packageName: String): Boolean = packages.any { packageName.startsWith(it) }
}

/** 进程内生效的规则集合（多平台），按 id 排序保证派发顺序确定 */
class RuleSet(val rules: List<PlatformRule>) {

    /** 按包名派发：首个 covers 命中的规则生效（解析期保证 packages 非空且无空前缀） */
    fun ruleFor(packageName: String): PlatformRule? = rules.firstOrNull { it.covers(packageName) }

    val isEmpty: Boolean get() = rules.isEmpty()

    companion object {
        /** 空规则集：判定管线退化为"纯硬编码启发式"，即改造前的行为 */
        val EMPTY = RuleSet(emptyList())
    }
}

/** manifest.json 的单条：文件路径 + 版本 + sha256（与 update.json 同一套信任模型） */
data class RuleManifestEntry(val id: String, val file: String, val version: Int, val sha256: String)

/**
 * 规则清单（远端与本地的信任根）：
 *  - [manifestVersion] 单调递增（YYYYMMDDNN），仅当它大于本地已接受版本才尝试拉取；
 *  - 每个规则文件的 sha256 是完整性闸门，客户端校验失败一律丢弃、保留旧规则；
 *  - 清单本身没有 sha256 —— 这与 update.json 完全同一套信任模型：Gitee raw + HTTPS
 *    做传输信任，清单是信任根，叶子文件用 sha256 防"发布/同步过程被篡改"。
 */
data class RuleManifest(val manifestVersion: Long, val rules: List<RuleManifestEntry>) {

    fun entry(id: String): RuleManifestEntry? = rules.firstOrNull { it.id == id }
}

/**
 * 一个字段的命中：
 *  - [value] 输出值（正则含捕获组时是组文本）；
 *  - [nodeText] 命中节点经 `cleanTitle` 清洗后的完整文本/描述 —— 价格口径
 *    （券后/到手）必须从它判定，只看捕获组会丢上下文；
 *  - [node] 命中的节点本体：小数位分体拼接（"¥1838"+".9"）需要节点位置信息。
 */
data class RuleFieldHit(
    val field: String,
    val value: String,
    val nodeText: String,
    val selector: RuleSelector,
    val node: NodeSnapshot
)

/** 一次规则提取结果（标题/价格按字段名约定；引擎本身对字段名无先验） */
data class RuleExtractResult(
    val ruleId: String,
    val ruleVersion: Int,
    val page: PageRule,
    val fields: Map<String, RuleFieldHit>
) {

    val title: String? get() = fields["title"]?.value

    val priceText: String? get() = fields["price"]?.value

    /** "谁命中的"：`title=textRegex:…, price=textRegex:…`（进日志与诊断串） */
    fun describeFields(): String = fields.values.joinToString(", ") { "${it.field}=${it.selector.describe()}" }
}
