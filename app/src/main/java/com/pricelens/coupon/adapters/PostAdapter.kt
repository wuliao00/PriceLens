package com.pricelens.coupon.adapters

import com.pricelens.coupon.model.CouponScope
import com.pricelens.coupon.normalize.Clause
import com.pricelens.coupon.normalize.Clauses
import com.pricelens.coupon.normalize.Normalize
import com.pricelens.coupon.slots.CouponVocabulary
import java.time.LocalDate

/** 帖型：V1 只有爆料帖进抽取（求助/闲聊里的"满199减50"是在问别人，不是在报券） */
enum class PostKind {
    TIP,

    /** 求助："这个券怎么用？" */
    ASK,

    /** 闲聊：没有券形态也没有求助形态 */
    CHATTER
}

/**
 * 入口二：社区帖（值得买/慢慢买社区里的爆料）。
 *
 * 三条只有社区帖才有的纪律：
 *  1. **先判帖型**：只有 [PostKind.TIP] 进抽取。求助帖里的券文案是"引述"，
 *     抽出来就是替用户决定"这券我能用" —— 那正是"无核验直出"的一种；
 *  2. **按步骤拆**：`步骤1 / ①②③ / 换行` 拆开的每一步独立成句，[Clause.marker] 保留序号
 *     （核验层要按"第几步"回看，展示层要写"第②步领这张"）；
 *  3. **叠券关系不是券槽位**：`叠plus 200-30` 里 `plus` 决定 scope=MEMBER（由 `ScopeWords`
 *     按 [CouponVocabulary.scopes] 判，不在这里另判一遍），但"能和什么叠"这件事只能整句带出去
 *     ⇒ 放 [Clause.stackNote]。
 *
 * 词表纪律：爆料形态与求助形态两块词表都在 [CouponVocabulary]（communityTipWords /
 * communityAskWords），本对象只吃 vocabulary 参数。以前它们是这里的 private 常量，
 * 于是"社区把爆料改叫好价分享"仍然要发一次 APK。
 *
 * 时效：[normalize] 用 [todayEpochDay] 比对帖里写明的日期（`2026-09-01` 这类），
 * 距今 >30 天 ⇒ 该帖所有句标 [Clause.stale]，管线据此乘 0.5 并留"历史信息"文案。
 * 门面另有 `fromPost(post, ageDays)`：调用方直接知道帖子年龄时以它为准（两条都能触发衰减）。
 */
object PostAdapter {

    /** 超过这么多天的历史信息按 0.5 衰减置信（社区券的有效期通常以"天"计） */
    const val STALE_AFTER_DAYS = 30L

    private val DATE_IN_POST = Regex("\\d{4}-\\d{2}-\\d{2}")

    fun classifyKind(post: String, vocabulary: CouponVocabulary = CouponVocabulary.DEFAULT): PostKind {
        val text = Normalize.text(post)
        if (text.isEmpty()) return PostKind.CHATTER
        val hasAmount = text.any { it.isDigit() }
        val isTip = hasAmount && vocabulary.communityTipWords.any { text.contains(it) }
        if (isTip) return PostKind.TIP
        // 问句优先于闲聊：`这个券怎么用` 里有券词但没有数字，判成爆料会凭空造一张券
        if (vocabulary.communityAskWords.any { text.contains(it) }) return PostKind.ASK
        return PostKind.CHATTER
    }

    fun normalize(post: String, todayEpochDay: Long, vocabulary: CouponVocabulary = CouponVocabulary.DEFAULT): List<Clause> {
        if (classifyKind(post, vocabulary) != PostKind.TIP) return emptyList()
        val stale = isStale(post, todayEpochDay)
        val stackPattern = stackRegex(vocabulary)
        // 直接把**原文**交给分句：规整在 Clauses.split 里逐句做。整段先规整会把换行压成空格，
        // "步骤分行"这条拆步通道就在拆步之前失效（详见 TextAdapter 的同类注释）。
        val clauses = Clauses.split(post)
        return clauses.map { clause ->
            val stack = stackPattern.find(clause.text)?.value?.trim()?.takeIf { it.isNotEmpty() }
            clause.copy(stale = stale || clause.stale, stackNote = stack ?: clause.stackNote)
        }
    }

    /**
     * 叠写门槛-面额对（`200-30` 这种）。
     *
     * 会员段由词表派生（[CouponVocabulary.scopes] 里 MEMBER 那组的 token），**不在这里再抄一份**
     * `会员|PLUS|88VIP`：那个词在 [CouponVocabulary.scopes]、[CouponVocabulary.states] 里各有一份，
     * 这里再抄第三份就意味着"改一处、另两处不动"⇒ 叠券备注与券的范围/状态判定分叉。
     * 长 token 排在前面，免得 `会员券` 被 `会员` 抢走前半段。
     *
     * 日期形态（`2026-09-01`）靠**两端**都挡住：后瞻 `(?![\d.\-])` 挡尾巴，前瞻 `(?<![\d.\-])` 挡头。
     * 只留后瞻时 `2026-09-01` 会从中间的 `09-01` 起匹配成功（真机社区帖几乎每条都带发帖日期，
     * 症状是每条爆料的 stackNote 都写着"09-01"这种不存在的叠券）。
     */
    internal fun stackRegex(vocabulary: CouponVocabulary): Regex {
        val memberTokens = vocabulary.scopes[CouponScope.MEMBER].orEmpty()
            .map { it.token }
            .filter { it.isNotEmpty() }
            .sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) }
        val member = if (memberTokens.isEmpty()) "" else "(?:$memberTokens)?"
        return Regex("(?<![\\d.\\-])(?:叠|加|凑)?\\s*$member\\s*(\\d{1,4})\\s*-\\s*(\\d{1,3})(?![\\d.\\-])", RegexOption.IGNORE_CASE)
    }

    /**
     * 帖子里写明的日期离 [todayEpochDay] 超过 [STALE_AFTER_DAYS] 天。
     * 解析不出日期返回 false —— **没有证据就不打折**（把当天的爆料判成历史是更糟的假阳性）。
     */
    internal fun isStale(post: String, todayEpochDay: Long): Boolean {
        val raw = DATE_IN_POST.find(post)?.value ?: return false
        val posted = runCatching { LocalDate.parse(raw) }.getOrNull() ?: return false
        return todayEpochDay - posted.toEpochDay() > STALE_AFTER_DAYS
    }
}
