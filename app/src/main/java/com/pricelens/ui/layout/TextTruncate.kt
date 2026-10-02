package com.pricelens.ui.layout

/**
 * 版式截断（纯函数，JVM 可测）。
 *
 * 为什么不用 `String.take(n)`：本仓库踩过 MUTF-8 / 代理对的坑。
 * take(n) 按 UTF-16 code unit 计数，而一个 emoji（😀 = D83D DE00）占 2 个 unit，
 * take(1) 留下半个代理对（lone surrogate）——显示是豆腐块，写进 Intent / MUTF-8 会丢字符；
 * 🇨🇳（两枚 Regional Indicator）、👨‍👩‍👧（ZWJ 序列）、❤️（变体选择符 FE0F）同理会被截成孤儿。
 * 这里按 **字簇（grapheme cluster）** 计数：代理对、组合附标、变体选择符、标签码点、
 * 肤色修饰符、ZWJ 及其后一个码点、RI 成对——都并进当前字簇，绝不切断。
 *
 * 单位口径：1 个汉字 / 1 个拉丁字符 / 1 个 emoji 字簇 = 1 单元。
 * 拉丁字符实际只占 ≈0.5em，所以按"单元"预算出来的宽度**偏保守**（宁早断也不溢出）。
 */
object TextTruncate {
    /** 省略号：占 1 个单元并**计入预算**（截断结果永远不会比预算宽） */
    const val ELLIPSIS = "…"

    private const val ZWJ = 0x200D
    private const val VAR_START = 0xFE00
    private const val VAR_END = 0xFE0F
    private const val VAR_SUPP_START = 0xE0100
    private const val VAR_SUPP_END = 0xE01EF
    private const val TAG_START = 0xE0020
    private const val TAG_END = 0xE007F
    private const val RI_START = 0x1F1E6
    private const val RI_END = 0x1F1FF
    private const val SKIN_START = 0x1F3FB
    private const val SKIN_END = 0x1F3FF

    /** 字簇数（可见单元数） */
    fun units(text: String): Int {
        var index = 0
        var count = 0
        while (index < text.length) {
            index = clusterEnd(text, index)
            count++
        }
        return count
    }

    /** 取前 [maxUnits] 个字簇，不含省略号；[maxUnits] ≤ 0 时返回空串 */
    fun head(text: String, maxUnits: Int): String {
        if (maxUnits <= 0) return ""
        var index = 0
        var taken = 0
        while (index < text.length && taken < maxUnits) {
            index = clusterEnd(text, index)
            taken++
        }
        return text.substring(0, index)
    }

    /**
     * 截到 [maxUnits] 个单元（省略号占一格，故结果单元数 ≤ maxUnits）。
     * 未超长时原样返回同一个实例——能显示原文就别显示省略号，调用方可用 `===` 判"没被截断"。
     * 预算小于省略号自己时只给省略号：宁可少说，也不摆一段超宽的假内容。
     */
    fun clamp(text: String, maxUnits: Int, ellipsis: String = ELLIPSIS): String {
        if (maxUnits <= 0) return ""
        if (units(text) <= maxUnits) return text
        val keep = maxUnits - units(ellipsis)
        if (keep <= 0) return ellipsis
        return head(text, keep) + ellipsis
    }

    /** 从 [from] 起第一个字簇的结束下标（end-exclusive） */
    private fun clusterEnd(text: String, from: Int): Int {
        val first = text.codePointAt(from)
        var index = from + Character.charCount(first)
        var codePoints = 1
        while (index < text.length) {
            val cp = text.codePointAt(index)
            val attach = when {
                cp == ZWJ -> true
                isAttaching(cp) -> true
                isRegional(first) && isRegional(cp) && codePoints == 1 -> true
                else -> false
            }
            if (!attach) return index
            index += Character.charCount(cp)
            if (cp == ZWJ && index < text.length) {
                // ZWJ 的意义就是"和下一个字簇连成同一个"：把 ZWJ 后面的码点一起并进来
                val joined = text.codePointAt(index)
                index += Character.charCount(joined)
                codePoints += 1
            }
            codePoints += 1
        }
        return index
    }

    /** 依附码点：组合附标 / 变体选择符 / 标签码点 / 肤色修饰符 */
    private fun isAttaching(cp: Int): Boolean = when {
        cp in VAR_START..VAR_END -> true
        cp in VAR_SUPP_START..VAR_SUPP_END -> true
        cp in TAG_START..TAG_END -> true
        cp in SKIN_START..SKIN_END -> true
        // Character.getType(...) 与那几个 MARK 常量在 Java 里**都是 byte**：
        // 只把主语转 Int 而常量仍是 Byte，Kotlin 依旧报 "Incompatible types 'kotlin.Int' and 'kotlin.Byte'"
        // （控制面接管时两轮编译实测到的，见 [MARK_TYPES]）
        else -> Character.getType(cp).toInt() in MARK_TYPES
    }

    private val MARK_TYPES = setOf(
        Character.NON_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt()
    )

    private fun isRegional(cp: Int): Boolean = cp in RI_START..RI_END
}
