package com.pricelens.ui.common

import com.pricelens.data.remote.CrawlerBlockedException
import com.pricelens.data.remote.SourceUnreachableException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F4（2026-09-29）用例 (a′)/(c)：空态文案必须先选对**成因**。
 *
 * 真机复现的那句「未匹配到与「mate 80」直接相关的商品，试试更完整的型号名」是一句断言：
 * 它宣称"查过了、是关键词的问题"。全机断网时这句话把用户推去改关键词，而真正该做的是联网。
 *
 * 判定已抽成纯函数 [EmptyStateCauseOf.of]，所以这里不需要 Compose / Robolectric；
 * 页面只负责把成因映射成字符串资源（映射结果用真机 UI dump 验收，见交付报告 §3）。
 */
class EmptyStateCauseTest {

    // 两种类型化失败（与 data/remote 冒泡出来的异常同族）
    private val net: AsyncValue<List<String>> =
        AsyncValue.Error(SourceUnreachableException("网络不可达：IOException"))
    private val blocked: AsyncValue<List<String>> =
        AsyncValue.Error(CrawlerBlockedException("403/412 反爬拦截"))
    private val idle: AsyncValue<List<String>> = AsyncValue.Idle
    private val loading: AsyncValue<List<String>> = AsyncValue.Loading(null)
    private fun reached(vararg items: String) = AsyncValue.Success(items.toList())

    @Test
    fun `not searched says not searched yet`() {
        assertEquals(
            EmptyStateCause.NOT_SEARCHED,
            EmptyStateCauseOf.of(searched = false, sources = listOf(idle, idle))
        )
    }

    /** 复现场景：五个源全挂（断网/被拦），此时任何"关键词写错了"的表述都是假因果 */
    @Test
    fun `all sources unreachable must not be reported as keyword mismatch`() {
        val sources = listOf(net, blocked, net, net, blocked)
        assertEquals(
            "一个源都没够着 → 只能说取不到数据；判成 NO_MATCH 就会渲染「未匹配到…试试更完整的型号名」",
            EmptyStateCause.UNREACHABLE,
            EmptyStateCauseOf.of(searched = true, sources = sources)
        )
    }

    /** (c) 护栏：问到了某个源、它答"没有" → 「未匹配到」这句才成立 */
    @Test
    fun `reached but zero results still reports keyword mismatch`() {
        assertEquals(
            EmptyStateCause.NO_MATCH,
            // reached() = 够着了、过滤后 0 条；net = 另一个源挂了
            EmptyStateCauseOf.of(
                searched = true,
                sources = listOf(reached(), net)
            )
        )
    }

    @Test
    fun `reached with data but no candidate picked still reports keyword mismatch`() {
        assertEquals(
            EmptyStateCause.NO_MATCH,
            EmptyStateCauseOf.of(searched = true, sources = listOf(reached("a"), idle))
        )
    }

    @Test
    fun `searched but nothing dispatched does not invent a cause`() {
        // 全 Idle：既不能说"没匹配上"（没查过），也不能编造"不可达"（没失败过）
        assertEquals(
            EmptyStateCause.NOT_SEARCHED,
            EmptyStateCauseOf.of(searched = true, sources = listOf(idle, idle))
        )
    }

    @Test
    fun `loading is not treated as reachable`() {
        assertEquals(
            EmptyStateCause.UNREACHABLE,
            EmptyStateCauseOf.of(searched = true, sources = listOf(loading, net))
        )
    }

    /**
     * 为什么调用方只能传"本页真正依赖的源"：B站/券/历史价的 L1-L2 缓存在断网时照样命中，
     * 状态仍是 Success。把它混进商品候选的判定里，NO_MATCH 就会盖掉 UNREACHABLE，
     * 屏幕上又回到「未匹配到与「关键词」直接相关的商品」——而真正没够着的是当当/值得买。
     * 概览页因此收窄成 posts + shihuo（见 `OverviewScreen` 里的注释）。
     */
    @Test
    fun `a stale-but-successful unrelated channel must not be counted as evidence`() {
        val cachedUnrelated = AsyncValue.Success(listOf("上一轮留下的 B 站视频"))
        assertEquals(
            "候选源（当当→值得买→识货）全挂时只能说取不到数据",
            EmptyStateCause.UNREACHABLE,
            EmptyStateCauseOf.of(searched = true, sources = listOf(blocked, net, net))
        )
        assertEquals(
            "反面对照：同一个失败列表里多塞一个 Success，结论就被翻成 NO_MATCH（这正是不能混传的原因）",
            EmptyStateCause.NO_MATCH,
            EmptyStateCauseOf.of(searched = true, sources = listOf(blocked, cachedUnrelated))
        )
    }
}
