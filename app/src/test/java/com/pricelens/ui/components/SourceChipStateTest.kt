package com.pricelens.ui.components

import com.pricelens.data.remote.CrawlerBlockedException
import com.pricelens.data.remote.CrawlerResult
import com.pricelens.data.remote.SourceUnreachableException
import com.pricelens.ui.common.AsyncValue
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F4（2026-09-29）用例 (a) 的展示面：徽标不得把"没够着"或"零结果"都写成「正常」。
 *
 * [sourceChipStateOf] 是从 `SourceChip` Composable 里抽出来的纯判定（G4/G5 的写法：
 * 把谓词从 Composable 提出来就能直接单测，绕开"UI 测不了"这个借口）。
 */
class SourceChipStateTest {

    private val netOutcome: CrawlerResult<String> = CrawlerResult.Network(IOException("failed to connect"))
    private val blockedOutcome: CrawlerResult<String> = CrawlerResult.Blocked("疑似反爬挑战页")

    @Test
    fun `network failure renders 失败 not 正常`() {
        val value: AsyncValue<List<String>> = AsyncValue.Error(SourceUnreachableException("网络不可达：IOException"))
        assertEquals(SourceChipState.FAILED, sourceChipStateOf(value, netOutcome))
    }

    @Test
    fun `anti-bot failure renders 反爬`() {
        val value: AsyncValue<List<String>> = AsyncValue.Error(CrawlerBlockedException("403/412 反爬拦截"))
        assertEquals(SourceChipState.BLOCKED, sourceChipStateOf(value, blockedOutcome))
    }

    /** cause 已经指明反爬时，即使域名诊断结果还没刷上也得写「反爬」 */
    @Test
    fun `blocked cause wins even without domain outcome`() {
        val value: AsyncValue<List<String>> = AsyncValue.Error(CrawlerBlockedException("B站接口业务码 code=-412"))
        assertEquals(SourceChipState.BLOCKED, sourceChipStateOf(value, null))
    }

    /** 关键一条：修复前 `Success(任何值) → OK`，空表因此也能拿到绿色「正常」 */
    @Test
    fun `reached with zero results is 无结果 not 正常`() {
        val value: AsyncValue<List<String>> = AsyncValue.Success(emptyList())
        assertEquals(SourceChipState.NO_RESULTS, sourceChipStateOf(value, null))
    }

    @Test
    fun `reached with results is 正常`() {
        val value: AsyncValue<List<String>> = AsyncValue.Success(listOf("华为 Mate 80"))
        assertEquals(SourceChipState.OK, sourceChipStateOf(value, null))
    }

    @Test
    fun `idle and loading stay neutral`() {
        val idle: AsyncValue<List<String>> = AsyncValue.Idle
        val loading: AsyncValue<List<String>> = AsyncValue.Loading(null)
        assertEquals(SourceChipState.IDLE, sourceChipStateOf(idle, null))
        assertEquals(SourceChipState.LOADING, sourceChipStateOf(loading, null))
    }

    /** 非集合载荷（历史价那种结构化对象）不该被当成"空表" */
    @Test
    fun `non collection payload success is 正常 even if it carries no list`() {
        val value: AsyncValue<Any> = AsyncValue.Success(object { val n = 1 })
        assertEquals(SourceChipState.OK, sourceChipStateOf(value, null))
    }
}
