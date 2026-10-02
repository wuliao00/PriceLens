package com.pricelens.data.repository

import com.pricelens.data.remote.CrawlerBlockedException
import com.pricelens.data.remote.SourceUnreachableException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A5（2026-10-01 审计）回归用例：singleflight 的落败方不得把胜者的失败塌缩成 `null`。
 *
 * 旧实现 `runCatching { existing.await() as T? }.getOrNull()` 只在"两个调用者撞同一个 key"时
 * 出现（浮窗展开与概览页搜索共享缓存键，正是并发概率最高的场景）：胜者被反爬 / 断网，落败方却
 * 拿到 `null` —— 而本项目的契约是 **`null` / 空表 = 够着了源且确实 0 条**（F4），
 * 于是徽标显示「正常但 0 条」、`staleKeys` 不亮，真实成因被抹掉。
 *
 * 测的是 [Singleflight] 本体（`PriceRepository` 只做一行委托），不是复制品：
 * 把实现改回 `runCatching{}.getOrNull()`，前三条立刻红。
 */
class SingleflightFailureTest {

    /** 与 DI 里的 `applicationScope` 同构：SupervisorJob + 测试调度器，胜者的失败不会连带掀翻测试作用域 */
    private fun flightsIn(testScope: TestScope): Pair<CoroutineScope, Singleflight> {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScope.testScheduler))
        return scope to Singleflight(scope)
    }

    /**
     * 让两次同 key 调用确定性地"撞成一条航班"：胜者先登记（`launch(UNDISPATCHED)` 里
     * putIfAbsent 在第一次挂起之前完成），落败方挂到同一个 deferred 上，最后才放开胜者收尾。
     *
     * @param finish 在落败方挂上之后、胜者结束之前执行（放闸或整个作用域取消）
     */
    private suspend fun CoroutineScope.race(
        flights: Singleflight,
        key: String,
        release: CompletableDeferred<Unit>,
        winnerBlock: suspend () -> String?,
        loserBlock: suspend () -> String?,
        finish: () -> Unit
    ): Pair<Result<String?>?, Result<String?>?> {
        var winner: Result<String?>? = null
        var loser: Result<String?>? = null
        val winnerJob = launch(start = CoroutineStart.UNDISPATCHED) {
            winner = runCatching {
                flights.call(key) {
                    release.await()
                    winnerBlock()
                }
            }
        }
        val loserJob = launch(start = CoroutineStart.UNDISPATCHED) {
            loser = runCatching { flights.call(key, loserBlock) }
        }
        finish()
        winnerJob.join()
        loserJob.join()
        return winner to loser
    }

    @Test
    fun `loser must rethrow the winner's anti-scraping failure instead of collapsing it into null`() = runTest {
        val (_, flights) = flightsIn(this)
        val release = CompletableDeferred<Unit>()
        var calls = 0

        val (winner, loser) = race(
            flights,
            "dd:search:mate 80",
            release,
            winnerBlock = {
                calls++
                throw CrawlerBlockedException("403 search.dangdang.com")
            },
            loserBlock = {
                calls++
                "不该被第二次问到"
            },
            finish = { release.complete(Unit) }
        )

        assertEquals("同 key 并发只许问一次数据源", 1, calls)
        assertSame(CrawlerBlockedException::class.java, winner?.exceptionOrNull()?.javaClass)
        assertFalse("落败方拿到成功的 null = 把失败伪装成合法空结果（A5 的原始缺陷）", loser?.isSuccess == true)
        assertSame(
            "胜者被反爬，落败方必须拿到同一个异常类型，徽标才会写「反爬」而不是「无结果」",
            CrawlerBlockedException::class.java,
            loser?.exceptionOrNull()?.javaClass
        )
    }

    @Test
    fun `loser propagates the winner's unreachable failure too`() = runTest {
        val (_, flights) = flightsIn(this)
        val release = CompletableDeferred<Unit>()

        val (winner, loser) = race(
            flights,
            "sh:search:耳机",
            release,
            winnerBlock = { throw SourceUnreachableException("connect timed out") },
            loserBlock = { "不该走到这里" },
            finish = { release.complete(Unit) }
        )

        assertSame(SourceUnreachableException::class.java, winner?.exceptionOrNull()?.javaClass)
        assertFalse("断网同样不得折成 null", loser?.isSuccess == true)
        assertSame(SourceUnreachableException::class.java, loser?.exceptionOrNull()?.javaClass)
    }

    /** 取消透传：胜者航班被撤时，落败方不得"安静地返回空"，且自己的 Job 要一起进入取消态 */
    @Test
    fun `loser propagates cancellation instead of returning a blank success`() = runTest {
        val (flightScope, flights) = flightsIn(this)
        val release = CompletableDeferred<Unit>() // 永不放开：只能被作用域取消

        val (winner, loser) = race(
            flights,
            "mmb:history:jd-item",
            release,
            winnerBlock = { "取到了" },
            loserBlock = { "取到了" },
            finish = { flightScope.cancel() }
        )

        assertTrue("胜者航班应随作用域取消", winner?.exceptionOrNull() is CancellationException)
        assertFalse("取消不能伪装成一次合法空结果", loser?.isSuccess == true)
        assertTrue(
            "落败方必须看到 CancellationException（吞掉它会破坏 searchJob?.cancel() 的契约），实际：${loser?.exceptionOrNull()}",
            loser?.exceptionOrNull() is CancellationException
        )
    }

    /** 反向护栏：合法的「够着了、确实没有」仍然共享，且不被改坏成抛异常 */
    @Test
    fun `legit null is shared by one call and stays a null`() = runTest {
        val (_, flights) = flightsIn(this)
        val release = CompletableDeferred<Unit>()
        var calls = 0

        val (winner, loser) = race(
            flights,
            "jd:product:123456",
            release,
            winnerBlock = {
                calls++
                null
            },
            loserBlock = {
                calls++
                null
            },
            finish = { release.complete(Unit) }
        )

        assertEquals(1, calls)
        assertTrue("胜者合法空结果", winner?.isSuccess == true && winner?.getOrNull() == null)
        assertTrue("落败方同样拿到合法空结果", loser?.isSuccess == true && loser?.getOrNull() == null)
    }

    @Test
    fun `successful value is shared by one call`() = runTest {
        val (_, flights) = flightsIn(this)
        val release = CompletableDeferred<Unit>()
        var calls = 0

        val (winner, loser) = race(
            flights,
            "jd:product:240001",
            release,
            winnerBlock = {
                calls++
                "¥4079.15"
            },
            loserBlock = {
                calls++
                "不该被执行的取数"
            },
            finish = { release.complete(Unit) }
        )

        assertEquals(1, calls)
        assertEquals("¥4079.15", winner?.getOrNull())
        assertEquals("落败方复用同一条航班的值", "¥4079.15", loser?.getOrNull())
    }
}
