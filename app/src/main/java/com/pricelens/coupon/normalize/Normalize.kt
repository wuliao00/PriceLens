package com.pricelens.coupon.normalize

/**
 * 确定性文本规整（**不碰网络、不猜语义**：同样输入永远同样输出，抽取层才谈得上可复现）。
 *
 * 三件事，顺序固定：
 *  1. 全角 → 半角（数字/字母/标点/空格）：真机与电商文案里 `ＦＵＮＮ`、`１９９`、`＋`、`，`
 *     混用很多，不规整时全角的「满１９９」匹配不上「满 + 半角数字」那条模板，
 *     症状是"词表明明收了却不命中"；
 *  2. 剥零宽字符与 emoji 噪声：京东用 U+200B 填充文案防爬（取证见
 *     `com.pricelens.accessibility.InvisibleTextSanitizingTest`），`\s+` 与 `String.trim()`
 *     都**认不出**零宽字符，不显式删除的话 `contains("立即领")` 会静默失配；
 *     emoji 走 Unicode 码点区间删（不用 `\p{So}`：①②③ 也是 So，用类别会把步骤标记一起删掉）；
 *  3. 压缩连续空白 + trim。
 *
 * **保留** `①②③`、`、`、`，`、`：` 这些结构/标点字符 —— 分句与"步骤"拆分要靠它们。
 */
object Normalize {

    /**
     * 不可见控制字符（与 `PriceNodeMatcher.INVISIBLE` 同一份字符集，那边是 private 拿不来，
     * 改动时**两处一起改**；差异只在这里不剥读屏角色后缀）。
     * 零宽空格/不连/连、词连接符、BOM、双向文本标记。
     */
    private val INVISIBLE = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060\\u2066-\\u2069\\uFEFF]")

    /**
     * emoji / 装饰符号的**码点区间**清单（刻意不用 `\p{So}`，理由见类注释）：
     * 杂项符号与箭头 `\u2600-\u27BF`、附加箭头 `\u2B00-\u2BFF`、变体选择符 `\uFE0E-\uFE0F`、
     * 补充平面图形 `\x{1F000}-\x{1FAFF}`（Java 正则里 `\x{...}` 才能写补充平面，Kotlin 字符串
     * 的 `\uXXXX` 只能到 BMP，所以这里必须写 `\\x{...}`）。
     */
    private val EMOJI = Regex("[\\u2600-\\u27BF\\u2B00-\\u2BFF\\uFE0E\\uFE0F\\x{1F000}-\\x{1FAFF}]")

    /** 连续空白（含 NBSP 与全角空格转来的空格）压成一个半角空格 */
    private val SPACES = Regex("\\s+")

    /**
     * 全角 → 半角：`ｦ-ﾟ` 之外的 FF01..FF5E 一律减 0xFEE0，另外单独处理全角空格 U+3000。
     *
     * 不做的事：不把 `壹佰` 之类中文数字转阿拉伯数字（那是语义猜测），
     * 不删 `，` `。` `；`（它们是 [Clauses] 的分句符）。
     */
    fun text(raw: String): String {
        val halfWidth = buildString(raw.length) {
            for (ch in raw) {
                when {
                    ch == '\u3000' -> append(' ')
                    ch.code in 0xFF01..0xFF5E -> append((ch.code - 0xFEE0).toChar())
                    else -> append(ch)
                }
            }
        }
        return halfWidth
            .replace(INVISIBLE, "")
            .replace(EMOJI, "")
            .replace('\u00A0', ' ')
            .let { SPACES.replace(it, " ").trim() }
    }
}
