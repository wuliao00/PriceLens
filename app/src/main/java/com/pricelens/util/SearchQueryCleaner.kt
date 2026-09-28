package com.pricelens.util

/**
 * 搜索关键词清洗（A2 新增，取代 `title.take(30)` 盲截断）。
 *
 * 旧缺陷：`"HUAWEI Mate 80 12GB+256GB 曜石黑"` 截到"…曜"、尾部更短时会截半型号，
 * 用截半的型号去搜会命中 X8/X8s/X8 Ultra 的第一条 → 拿到旧款价。
 * 规则：
 *  1. 去【…】促销前缀与首尾残留分隔符；
 *  2. 去 `12GB+256GB` 组合与 `256GB / 1TB / 120Hz / 6.7寸 / 5000mAh` 规格词；
 *  3. 去尾部独立颜色 token（"曜石黑"、"黑色"；不拆"老白茶"这类无空格词）；
 *  4. 仍超长时**按 token 边界截**：整词保留，绝不把字母数字型号 token 截成两半；
 *  5. [titleOverlap] 为概览页"候选与当前搜索词是否同一商品"的轻量重叠打分
 *     （CJK 二元组 + 拉丁 token 命中率），供 [SearchViewModel] 判定"上一次商品数据"。
 */
object SearchQueryCleaner {

    const val DEFAULT_MAX_LEN = 40

    /** 【…】/［…］促销前缀段 */
    private val BRACKET_SEGMENT = Regex("【[^】]*】|［[^］]*］|\\[[^]]*]")

    /** 组合容量："12GB+256GB"（先整段去掉，再处理散落的单位词） */
    private val COMBO_CAPACITY =
        Regex("\\d+(?:\\.\\d+)?\\s*(?:GB|TB|MB)\\s*[+＋]\\s*\\d+(?:\\.\\d+)?\\s*(?:GB|TB|MB)", RegexOption.IGNORE_CASE)

    /** 单位规格词：GB/TB/MB/MHz/Hz/mAh/英寸/寸 */
    private val SPEC_TOKEN =
        Regex("\\d+(?:\\.\\d+)?\\s*(?:GB|TB|MB|MHz|Hz|mAh|英寸|寸)(?![A-Za-z])", RegexOption.IGNORE_CASE)

    /** 尾部颜色 token：1-4 个汉字 + 颜色字（如"曜石黑"）或裸颜色词（"黑色"） */
    private val COLOR_WORD = Regex(
        "(?:[\\u4e00-\\u9fff]{1,4}(?:黑|白|蓝|绿|粉|紫|灰|银|金|橙|红|棕)|黑色|白色|银色|金色|粉色|蓝色|红色|绿色|紫色|橙色|灰色|棕色|彩色)"
    )

    private val TRAILING_SEPARATORS = Regex("[\\s\\-—_|/·,，。;；]+$")
    private val LEADING_SEPARATORS = Regex("^[\\s\\-—_|/·,，。;；]+")
    private val SPACES = Regex("\\s+")

    private val LATIN_TOKEN = Regex("[A-Za-z0-9][A-Za-z0-9+×xX._-]*")
    private val CJK_RUN = Regex("[\\u4e00-\\u9fff]+")

    /**
     * 清洗无障碍读到的商品标题 → 可搜索关键词。
     * 返回 null 表示清洗后无有效内容（调用方保留原标题或跳过搜索）。
     */
    fun clean(raw: String?, maxLen: Int = DEFAULT_MAX_LEN): String? {
        val trimmed = raw?.trim()?.replace('\u00A0', ' ') ?: return null
        if (trimmed.isEmpty()) return null
        var s = BRACKET_SEGMENT.replace(trimmed, " ")
        s = COMBO_CAPACITY.replace(s, " ")
        s = SPEC_TOKEN.replace(s, " ")
        s = SPACES.replace(s, " ").trim()

        // 反复剥离"独立空白分隔"的尾部颜色 token（不拆无空格词，避免误伤"福鼎老白茶"）
        var parts = s.split(" ")
        while (parts.size > 1 && COLOR_WORD.matches(parts.last())) {
            parts = parts.dropLast(1)
        }
        s = parts.joinToString(" ")
        s = SPACES.replace(s, " ").trim()
        s = TRAILING_SEPARATORS.replace(s, "")
        s = LEADING_SEPARATORS.replace(s, "")

        if (s.isEmpty()) return null
        return truncateAtTokenBoundary(s, maxLen)
    }

    /**
     * 按 token 边界截断：
     *  - 有空格分隔的词：逐个整词累加，超过 maxLen 则停止（至少保留首词）；
     *  - 无空格（纯中文/粘连）：切在 maxLen，但若切点落在拉丁数字 token 内部，
     *    回退到该 token 起点（宁可少几个字，绝不产生 "X8s" 截半型号）。
     */
    internal fun truncateAtTokenBoundary(s: String, maxLen: Int): String {
        if (s.length <= maxLen) return s
        val parts = s.split(" ")
        if (parts.size > 1) {
            val sb = StringBuilder()
            for (p in parts) {
                val candidate = if (sb.isEmpty()) p else "$sb $p"
                if (candidate.length > maxLen && sb.isNotEmpty()) break
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(p)
            }
            return sb.toString()
        }
        var cut = maxLen
        // 切点两侧都是拉丁数字 token 字符 → 回退到 token 起点
        while (cut > 0 && cut < s.length && isTokenChar(s[cut]) && isTokenChar(s[cut - 1])) cut--
        if (cut == 0) cut = maxLen // 整个串就是一个超长拉丁词：宁可原样截也不返回空串
        return s.substring(0, cut).trimEnd()
    }

    private fun isTokenChar(c: Char): Boolean = c.isLetterOrDigit() && c.code < 0x2E80 // 拉丁/数字才算 token；CJK 逐字独立

    /**
     * 轻量重叠分（0..1）：关键词拆成 CJK 二元组 + 拉丁 token，
     * 统计标题（归一化后）命中的比例。用于"上一次商品数据"判定；
     * 与 [QueryRelevance] 互补（后者是严格过滤，本函数是宽容打分）。
     */
    fun titleOverlap(keyword: String, title: String): Double {
        val units = matchUnits(keyword)
        if (units.isEmpty()) return 0.0
        val normTitle = normalize(title)
        val hits = units.count { normTitle.contains(it) }
        return hits.toDouble() / units.size
    }

    private fun matchUnits(keyword: String): List<String> {
        val norm = normalize(keyword)
        val out = ArrayList<String>()
        LATIN_TOKEN.findAll(norm).forEach { out.add(it.value) }
        CJK_RUN.findAll(norm).forEach { m ->
            val run = m.value
            if (run.length == 1) out.add(run) else run.windowed(2).forEach { out.add(it) }
        }
        return out.distinct()
    }

    /** 归一化：去空白与标点、全角转半角、小写（与 QueryRelevance.normalize 同思路，独立实现避免跨文件耦合） */
    private fun normalize(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            val c = when (ch) {
                in '！'..'～' -> (ch.code - 0xFEE0).toChar() // 全角 ASCII → 半角（用转义字面量，避免排版字符歧义）
                '　' -> ' ' // 全角空格
                else -> ch
            }
            if (c.isLetterOrDigit()) sb.append(c.lowercaseChar())
        }
        return sb.toString()
    }
}
