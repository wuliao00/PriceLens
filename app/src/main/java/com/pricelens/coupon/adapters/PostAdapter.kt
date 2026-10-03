package com.pricelens.coupon.adapters

import com.pricelens.coupon.normalize.Clause
import com.pricelens.coupon.normalize.Clauses
import com.pricelens.coupon.normalize.Normalize
import java.time.LocalDate
import java.time.format.DateTimeParseException

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
 *  3. **叠券关系不是券槽位**：`叠plus 200-30` 里 `plus` 决定 scope=MEMBER，
 *     但"能和什么叠"这件事只能整句带出去 ⇒ 放 [Clause.stackNote]。
 *
 * 时效：[normalize] 用 [todayEpochDay] 比对帖里写明的日期（`2026-09-01` 这类），
 * 距今 >30 天 ⇒ 该帖所有句标 [Clause.stale]，管线据此乘 0.5 并留"历史信息"文案。
 * 门面另有 `fromPost(post, ageDays)`：调用方直接知道帖子年龄时以它为准（两条都能触发衰减）。
 */
object PostAdapter {

    /** 超过这么多天的历史信息按 0.5 衰减置信（社区券的有效期通常以"天"计） */
    const val STALE_AFTER_DAYS = 30L

    /** 爆料形态：券动词 + 数字，或"到手/券后"价，或步骤标记 —— 任一命中即进抽取 */
    private val TIP_WORDS = listOf("到手", "券后", "领", "满减", "立减", "无门槛", "叠", "凑单", "红包")

    /** 求助形态：问句词。命中且没有爆料形态时判 ASK */
    private val ASK_WORDS = listOf("怎么", "如何", "能不能", "可以吗", "求推荐", "有没有", "求助", "请问", "？", "?")

    /** `200-30` 这类叠写门槛-面额对（只在券上下文里认，日期形态靠前后瞻挡掉） */
    private val STACK = Regex("(?:叠|加|凑)?\\s*(?:会员|PLUS|plus|88VIP|88vip)?\\s*(\\d{1,4})\\s*-\\s*(\\d{1,3})(?![\\d.\\-])")

    private val DATE_IN_POST = Regex("\\d{4}-\\d{2}-\\d{2}")

    fun classifyKind(post: String): PostKind {
        val text = Normalize.text(post)
        if (text.isEmpty()) return PostKind.CHATTER
        val hasAmount = text.any { it.isDigit() }
        val isTip = hasAmount && TIP_WORDS.any { text.contains(it) }
        if (isTip) return PostKind.TIP
        // 问句优先于闲聊：`这个券怎么用` 里有券词但没有数字，判成爆料会凭空造一张券
        if (ASK_WORDS.any { text.contains(it) }) return PostKind.ASK
        return PostKind.CHATTER
    }

    fun normalize(post: String, todayEpochDay: Long): List<Clause> {
        if (classifyKind(post) != PostKind.TIP) return emptyList()
        val stale = isStale(post, todayEpochDay)
        val clauses = Clauses.split(Normalize.text(post))
        return clauses.map { clause ->
            val stack = STACK.find(clause.text)?.value?.trim()?.takeIf { it.isNotEmpty() }
            clause.copy(stale = stale || clause.stale, stackNote = stack ?: clause.stackNote)
        }
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
