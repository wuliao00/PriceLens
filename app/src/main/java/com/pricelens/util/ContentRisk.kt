package com.pricelens.util

/**
 * 内容风险判定（参考"诚实豆沙包"恰饭鉴定思路的轻量移植版）。
 *
 * 诚实豆沙包完整方案 = 抓视频字幕 + AI 双审判案（需大模型 API Key，S/A/B/C 四档）；
 * 移动端离线场景下退化为规则判定：
 *  - 商单痕迹：标题/标签命中商单词库，或 B 站"联合投稿"标记（商单 campaign 的常见环境证据）
 *  - 夸大宣传：命中绝对化/煽动性话术词库
 *
 * 原则：只标记、沉底展示，不删除 —— 把判断权留给用户，避免误杀真实评测。
 */
data class ContentRisk(
    // 疑似商单/恰饭
    val sponsored: Boolean,
    // 含夸大宣传话术
    val hype: Boolean,
    // 命中的商单词（UI 展示用）
    val sponsorWord: String? = null,
    // 命中的夸大词（UI 展示用）
    val hypeWord: String? = null
) {
    val flagged: Boolean get() = sponsored || hype

    companion object {
        val NONE = ContentRisk(sponsored = false, hype = false)
    }
}

object ContentRiskRules {

    /** 商单痕迹词库：恰饭/商单自曝 + 推广性质表述 */
    private val SPONSOR_WORDS = listOf(
        "商单", "恰饭", "广告", "推广", "赞助", "含广",
        "商务合作", "品牌合作", "金主", "投放", "特邀体验"
    )

    /** 夸大宣传词库：绝对化表述与煽动性种草话术 */
    private val HYPE_WORDS = listOf(
        "史上最强", "天花板", "封神", "碾压", "吊打", "断货",
        "性价比之王", "闭眼入", "无脑冲", "必买", "yyds", "无敌",
        "王炸", "吹爆", "全网最低", "颠覆", "永远的神"
    )

    /**
     * @param text 标题+标签拼接文本
     * @param isUnionVideo B 站联合投稿标记（多人联合投稿与商单 campaign 强相关）
     */
    fun assess(text: String, isUnionVideo: Boolean = false): ContentRisk {
        val sponsorWord = SPONSOR_WORDS.firstOrNull { text.contains(it, ignoreCase = true) }
        val hypeWord = HYPE_WORDS.firstOrNull { text.contains(it, ignoreCase = true) }
        return ContentRisk(
            sponsored = sponsorWord != null || isUnionVideo,
            hype = hypeWord != null,
            sponsorWord = sponsorWord ?: if (isUnionVideo) "联合投稿" else null,
            hypeWord = hypeWord
        )
    }
}
