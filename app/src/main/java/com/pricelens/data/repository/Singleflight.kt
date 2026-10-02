package com.pricelens.data.repository

import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async

/**
 * 同 key 并发取数合并：胜者执行取数、落败方 await 同一航班，结束即撤槽。
 *
 * 从 [PriceRepository] 的私有方法拆出来只有一个原因：A5 那条缺陷（落败方把胜者的异常
 * 折成 `null`）只有单测能打到**真实实现**才算守住了，留在私有方法里只能测一份复制品。
 *
 * A5 修正的行为差别（`PriceRepository.kt:433-448` 旧实现用
 * `runCatching { existing.await() }.getOrNull()`）：
 *  - [CancellationException] 透传，并取消调用方自己的 Job —— 旧实现吞掉它，
 *    使 `SearchViewModel.searchJob?.cancel()` 在"撞上别人在途航班"这条路径上失效；
 *  - 其它异常原样抛出 —— 本项目的契约是 **`null` / 空表 = 够着了源且确实 0 条**
 *    （见 [CachedSource] 与 `SourceStatusRow`），把网络失败/反爬塌成 `null`
 *    会伪装成一次合法的空白结果：徽标显示「正常」、`staleKeys` 不亮、用户只看到"没结果"。
 *
 * 一个失败会同时传给所有等待者，这是有意的：调用方本来就有 per-source 的
 * `AsyncValue.Error` 落点，宁可让它显示失败，也不要显示假的"无结果"。
 */
internal class Singleflight(private val scope: CoroutineScope) {
    private val inflight = ConcurrentHashMap<String, Deferred<Any?>>()

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> call(key: String, block: suspend () -> T?): T? {
        while (true) {
            inflight[key]?.let { existing ->
                return try {
                    existing.await() as T?
                } catch (e: CancellationException) {
                    // 取消不是"源失败"：先取消调用方，再原样透传，别让它变成一次空结果
                    coroutineContext[Job]?.cancel()
                    throw e
                } catch (e: Throwable) {
                    throw e // 胜者怎么摔的，落败方就怎么说：不折成 null
                }
            }
            val deferred = scope.async { block() }
            if (inflight.putIfAbsent(key, deferred) == null) {
                return try {
                    deferred.await()
                } finally {
                    inflight.remove(key, deferred)
                }
            }
            deferred.cancel() // 竞态落败：已有在途航班，取消自建重试循环
        }
    }
}
