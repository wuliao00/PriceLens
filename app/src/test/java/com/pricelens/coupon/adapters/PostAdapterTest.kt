package com.pricelens.coupon.adapters

import com.pricelens.coupon.slots.CouponVocabulary
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 病根④「无核验直出」在社区帖入口的第一道：帖型判定。
 *
 * 求助帖里的"满199减50"是**引述**（"这个券怎么用"），抽出来就是替用户决定"这券我能用"。
 * 三态都钉：爆料进（正例）、求助不进、闲聊不进，并且每条都配"该进的确实进了"的对照，
 * 免得判型退化成"什么都不进"也能全绿。
 */
class PostAdapterTest {

    private val epoch20261001 = LocalDate.of(2026, 10, 1).toEpochDay()
    private val epoch20261002 = LocalDate.of(2026, 10, 2).toEpochDay()

    @Test
    fun `爆料进抽取，求助与闲聊都不进`() {
        assertEquals(PostKind.TIP, PostAdapter.classifyKind("百亿补贴到手19元 满199减50"))
        assertEquals(PostKind.ASK, PostAdapter.classifyKind("这个券怎么用"))
        assertEquals(PostKind.CHATTER, PostAdapter.classifyKind("今天天气不错"))
        // 判型的产出差必须落在 normalize 上：只有爆料帖拿到句子
        assertTrue(PostAdapter.normalize("百亿补贴到手19元 满199减50", epoch20261001).isNotEmpty())
        assertTrue(PostAdapter.normalize("这个券怎么用", epoch20261001).isEmpty())
        assertTrue(PostAdapter.normalize("今天天气不错", epoch20261001).isEmpty())
        assertTrue(PostAdapter.normalize("   ", epoch20261001).isEmpty())
    }

    @Test
    fun `有券词但没有数字的问句不许被当爆料`() {
        // "满199减50" 出现在问句里是引述；没有券动词 + 数字这对形态就不是爆料
        assertEquals(PostKind.ASK, PostAdapter.classifyKind("满199减50这个怎么用"))
        assertTrue(PostAdapter.normalize("满199减50这个怎么用", epoch20261001).isEmpty())
        // 正例对照：同样带数字，动词换成"到手"就是爆料（数字-动词这对闸没被写死）
        assertEquals(PostKind.TIP, PostAdapter.classifyKind("满199减50到手价怎么算"))
    }

    @Test
    fun `步骤与换行拆步并且序号保留`() {
        val clauses = PostAdapter.normalize("到手19元\n①满199减50\n②领50元券", epoch20261001)
        assertEquals(3, clauses.size)
        assertEquals(listOf("到手19元", "①满199减50", "②领50元券"), clauses.map { it.text })
        assertEquals(listOf(null, "①", "②"), clauses.map { it.marker })
    }

    @Test
    fun `叠券关系进 stackNote 而不是券槽位`() {
        val clauses = PostAdapter.normalize("PLUS会员 叠plus 200-30 到手170", epoch20261001)
        assertEquals(1, clauses.size)
        assertEquals("叠plus 200-30", clauses[0].stackNote)
        // 反例：发帖日期里的 `09-01` 不是叠券（两端都挡住日期形态才算治到位）
        assertTrue(PostAdapter.normalize("爆料 2026-09-01 到手19元", epoch20261002).all { it.stackNote == null })
    }

    @Test
    fun `三十天边界两侧都钉住`() {
        // 出厂阈值 30 天：差 30 天不算历史，差 31 天才算
        assertEquals(30L, epoch20261001 - LocalDate.of(2026, 9, 1).toEpochDay())
        assertFalse(PostAdapter.isStale("2026-09-01 到手19元", epoch20261001))
        assertTrue(PostAdapter.isStale("2026-09-01 到手19元", epoch20261002))
        assertEquals(30L, PostAdapter.STALE_AFTER_DAYS)
    }

    @Test
    fun `解析不出日期一律不衰减`() {
        // 没有日期证据
        assertFalse(PostAdapter.isStale("到手19元 无门槛", epoch20261002))
        // 有日期形态但根本不是日期（月份 13）：LocalDate.parse 失败 ⇒ 不猜
        assertFalse(PostAdapter.isStale("2026-13-45 到手19元", epoch20261002))
        assertTrue(PostAdapter.isStale("2025-01-01 到手19元", epoch20261002))
    }

    @Test
    fun `帖型词表在词表对象里而不是适配器里`() {
        // 出厂值判不出来的一条新说法（社区把"捡漏"用作爆料口吻）。
        // 注：这条原来用的是「百亿补贴」，2026-10-04 把「补贴/实付/售价/活动价」补进出厂词表之后
        // 它变成 TIP 了 —— 换词不是修测试，是因为**被测前提变了**：那句现在真能判出来。
        val post = "捡漏啦 19元"
        assertEquals(PostKind.CHATTER, PostAdapter.classifyKind(post))
        // 正例对照：换一份词表就能接住，不改一行判定代码、也不发版（增量登记，出厂值仍然有效）
        val extended = CouponVocabulary.DEFAULT.copy(
            communityTipWords = CouponVocabulary.DEFAULT.communityTipWords + "捡漏"
        )
        assertEquals(PostKind.TIP, PostAdapter.classifyKind(post, extended))
        assertEquals(1, PostAdapter.normalize(post, epoch20261001, extended).size)
        // 反例：加词不等于换词——原来的爆料形态不许因为推了新词就失效
        assertEquals(PostKind.TIP, PostAdapter.classifyKind("到手19元", extended))
    }
}
