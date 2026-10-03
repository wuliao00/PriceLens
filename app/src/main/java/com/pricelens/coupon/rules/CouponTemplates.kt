package com.pricelens.coupon.rules

import com.pricelens.coupon.model.AmountRole
import com.pricelens.coupon.model.CouponState
import org.json.JSONArray
import org.json.JSONObject

/** 一个被模板捕获到的数字：[start] 是它在整句里的起始下标（角色判定与去重都靠它） */
data class AmountValue(val role: AmountRole, val value: Double, val start: Int)

/**
 * 一条券模板。
 *
 * @param roles **按顺序**对应 pattern 里那些"内容是数字"的捕获组。
 *   为什么不是"对应第 1、2、3 个捕获组"：任务书给的到手/券后/领后那一条例子里，
 *   第 1 组是关键词、第 2 组才是数字，按组号对齐会把关键词当金额解析。
 *   本类的做法是**跳过非数字捕获组**（`到手` 解析不出 double ⇒ 不占角色位），
 *   于是 roles 只需按"数字组"的顺序写，两种 pattern 形态（两个数字组的、以及关键词加一个数字组的）都能自然对齐。
 * @param state 状态词组模板用（roles 为空表 + 一个 state）；金额模板留 UNKNOWN。
 *   这是任务书四字段之外的**追加可选项**，带默认值 ⇒ 按四字段位置构造的写法照常编译。
 */
data class CouponTemplate(
    val id: String,
    val pattern: Regex,
    val roles: List<AmountRole>,
    val confidence: Double,
    val state: CouponState = CouponState.UNKNOWN
)

/** 一次模板命中：抽取层拿 [amounts] 填槽位，拿 [confidence] 算总置信 */
data class TemplateHit(
    val templateId: String,
    val version: Int,
    val text: String,
    val start: Int,
    val amounts: List<AmountValue>,
    val state: CouponState,
    val confidence: Double
)

/**
 * 带**版本号**和**命中计数**的模板库。
 *
 * 计数为什么重要：线上"找券不准"时，唯一能区分"模板没写对"和"模板从没被命中"的证据就是
 * 每条模板的命中次数（`rules/` 那套引擎靠同类数据定位过 3 次规则失效）。
 * 本类是**有状态**的（计数累加），所以每个宿主进程持有一份 [BUILTIN]，
 * 单测要一份干净的自己 `builtin()` 新建 —— 别把断言写在共享计数上，那会随测试顺序漂。
 *
 * 词表/模板必须能被远端规则包覆盖（与 `PageVocabulary` 同一经验）：留 [from] 入口，
 * 电商 App 改了券文案时推一条 JSON 就够，不必发 APK。
 */
class CouponTemplates(val version: Int, val templates: List<CouponTemplate>) {

    private val counters = LinkedHashMap<String, Int>()

    /** 整句里所有模板的所有命中（按模板声明顺序，同模板多命中按出现顺序） */
    fun match(clause: String): List<TemplateHit> {
        val out = ArrayList<TemplateHit>()
        for (template in templates) {
            for (found in template.pattern.findAll(clause)) {
                recordHit(template.id)
                val hit = TemplateHit(
                    templateId = template.id,
                    version = version,
                    text = found.value,
                    start = found.range.first,
                    amounts = amountsOf(template, found),
                    state = template.state,
                    confidence = template.confidence
                )
                out.add(hit)
            }
        }
        return out
    }

    /** 每条模板的累计命中次数（含从没命中的 ⇒ 值为 0，"零命中"本身就是排查信息） */
    fun hits(): Map<String, Int> = templates.map { Pair(it.id, counters[it.id] ?: 0) }.toMap()

    private fun recordHit(id: String) {
        counters[id] = (counters[id] ?: 0) + 1
    }

    companion object {

        /** 句子里有券证据、但没有任何模板命中时的兜底置信（低于任何真实模板：宁低不高） */
        const val CONTEXT_ONLY_CONFIDENCE = 0.5

        /** 内置模板库版本；远端规则包替换时按它比对（同 `RuleJson.SCHEMA_VERSION` 的用法） */
        const val BUILTIN_VERSION = 1

        /** 进程内共享的一份（三入口的门面都用它，命中计数才聚得起来） */
        val BUILTIN = CouponTemplates(BUILTIN_VERSION, builtinTemplates())

        /** 干净的一份内置模板库：测试用，计数从 0 起 */
        fun builtin(): CouponTemplates = CouponTemplates(BUILTIN_VERSION, builtinTemplates())

        internal fun amountsOf(template: CouponTemplate, found: MatchResult): List<AmountValue> {
            val numbers = ArrayList<AmountValueRaw>(template.roles.size)
            for (index in 1..found.groupValues.lastIndex) {
                val group = found.groups[index] ?: continue
                if (group.range.first < 0) continue
                val raw = group.value ?: continue
                val value = raw.replace(",", "").toDoubleOrNull() ?: continue
                numbers.add(AmountValueRaw(value, group.range.first))
            }
            return numbers.mapIndexedNotNull { position, number ->
                val role = template.roles.getOrNull(position) ?: return@mapIndexedNotNull null
                AmountValue(role, number.value, number.start)
            }
        }

        /**
         * 远端规则包 → 模板库（org.json 手写解析，仓库红线：不引序列化库）。
         *
         * 口径与 [com.pricelens.rules.RuleJson] 一致：**任何可疑输入整体拒绝，不猜**。
         * 坏规则留在旧版本上只是"少抽一张券"，半懂的规则驱动浮窗则是"展示一张不存在的券"。
         * 格式：version 加一个 templates 数组，每条含 id、pattern、roles（金额角色名数组）、
         * confidence，状态词组模板再带一个 state（状态枚举名）。
         * ；非法 JSON / 未知角色名 / 正则编译失败 / 置信越界 / 超长 pattern / id 重复 一律 null。
         */
        fun from(json: String): CouponTemplates? = runCatching { parse(JSONObject(json)) }.getOrNull()

        private const val MAX_TEMPLATES = 64
        private const val MAX_PATTERN_LENGTH = 200
        private val ID_RE = Regex("^[a-z0-9_-]{1,32}$")

        private fun parse(root: JSONObject): CouponTemplates? {
            val version = root.optInt("version", -1)
            if (version <= 0) return null
            val array: JSONArray = root.optJSONArray("templates") ?: return null
            if (array.length() == 0 || array.length() > MAX_TEMPLATES) return null
            val seen = HashSet<String>()
            val parsed = ArrayList<CouponTemplate>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: return null
                val id = item.optString("id")
                if (!ID_RE.matches(id) || !seen.add(id)) return null
                val patternText = item.optString("pattern")
                if (patternText.isEmpty() || patternText.length > MAX_PATTERN_LENGTH) return null
                val pattern = runCatching { Regex(patternText) }.getOrNull() ?: return null
                val roles = item.optJSONArray("roles") ?: JSONArray()
                val rolesList = ArrayList<AmountRole>(roles.length())
                for (r in 0 until roles.length()) {
                    rolesList.add(amountRoleOf(roles.optString(r)) ?: return null)
                }
                val confidence = item.optDouble("confidence", Double.NaN)
                if (confidence.isNaN() || confidence <= 0.0 || confidence > 1.0) return null
                val state = item.optString("state").let { raw -> if (raw.isEmpty()) CouponState.UNKNOWN else stateOf(raw) ?: return null }
                parsed.add(CouponTemplate(id, pattern, rolesList, confidence, state))
            }
            return CouponTemplates(version, parsed)
        }

        private fun amountRoleOf(name: String): AmountRole? = runCatching { AmountRole.valueOf(name) }.getOrNull()

        private fun stateOf(name: String): CouponState? = runCatching { CouponState.valueOf(name) }.getOrNull()
    }
}

/** 捕获组的最小视图（只要文本与下标，便于单测直接喂数据而不必造 MatchResult） */
internal data class AmountValueRaw(val value: Double, val start: Int)

/** 出厂模板。声明顺序影响 [CouponTemplates.match] 的返回顺序，不影响判定结果。 */
private fun builtinTemplates(): List<CouponTemplate> = listOf(
    CouponTemplate("man-jian-pair", Regex("满\\s*([\\d.]+)\\s*减\\s*([\\d.]+)"), listOf(AmountRole.THRESHOLD, AmountRole.DISCOUNT), 0.95),
    CouponTemplate("ling-yuan-quan", Regex("领\\s*([\\d.]+)\\s*元?券"), listOf(AmountRole.DISCOUNT), 0.9),
    CouponTemplate("final-price", Regex("(到手|券后|领后)\\s*[¥￥]?\\s*([\\d.]+)"), listOf(AmountRole.FINAL), 0.9),
    CouponTemplate("list-price", Regex("(原价|划线价|日常价|标价)\\s*[¥￥]?\\s*([\\d.]+)"), listOf(AmountRole.LIST), 0.85),
    CouponTemplate("drop-amount", Regex("(降|跌|便宜|比原价)\\s*(?:了)?\\s*[¥￥]?\\s*([\\d.]+)"), listOf(AmountRole.DROP), 0.8),
    CouponTemplate("currency-quan", Regex("[¥￥]\\s*([\\d.]+)\\s*(?:元)?(?:无门槛)?(?:立减)?券"), listOf(AmountRole.DISCOUNT), 0.9),
    CouponTemplate("lijian-amount", Regex("立减\\s*[¥￥]?\\s*([\\d.]+)"), listOf(AmountRole.DISCOUNT), 0.9),
    CouponTemplate("no-threshold", Regex("无门槛"), emptyList(), 0.75),
    CouponTemplate("state-sold-out", Regex("已抢完|已领完|抢光|已加光"), emptyList(), 0.9, CouponState.SOLD_OUT),
    CouponTemplate("state-expired", Regex("已过期|过期|失效"), emptyList(), 0.9, CouponState.EXPIRED),
    CouponTemplate("state-claimed", Regex("已领|去使用"), emptyList(), 0.9, CouponState.CLAIMED),
    CouponTemplate("state-claimable", Regex("领取|立即领"), emptyList(), 0.85, CouponState.CLAIMABLE),
    CouponTemplate("state-region", Regex("限.{0,8}地区|部分地区|当前地区|本地区|该地区"), emptyList(), 0.85, CouponState.REGION_LIMITED),
    CouponTemplate("state-member", Regex("会员专享|PLUS|88VIP|叠plus"), emptyList(), 0.85, CouponState.MEMBER_ONLY)
)
