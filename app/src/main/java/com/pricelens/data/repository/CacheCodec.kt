package com.pricelens.data.repository

import com.pricelens.data.cache.TLRUCache
import com.pricelens.util.LogT
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * §阶段3 缓存编解码抽象：把「领域对象 ↔ JSON 文本」收敛为单一职责组件，
 * 替代 PriceRepository 里散落的手写 org.json 编解码。
 * 仍用系统 org.json（不引入新序列化框架，控制 APK 体积）。
 */
interface CacheCodec<T> {
    fun encode(value: T): String

    /** 解码失败返回 null（损坏快照静默丢弃，走网络重取） */
    fun decode(raw: String): T?
}

/**
 * §阶段3 stale 标记：网络/解析失败降级返回 L2 旧快照时记录 key，
 * UI 可据此展示"旧数据"提示。公开方法签名不变，陈旧状态走旁路观察。
 */
class StaleTracker {
    private val _stale = MutableStateFlow<Set<String>>(emptySet())
    val stale: StateFlow<Set<String>> = _stale.asStateFlow()

    fun markStale(key: String) {
        _stale.update { if (key in it) it else it + key }
    }

    fun markFresh(key: String) {
        _stale.update { if (key in it) it - key else it }
    }
}

/**
 * §阶段3 后台重验证中枢：TLRUCache.onStale 回调的统一落点。
 * CachedSource 在成功取数后按 key 注册刷新动作；
 * AppModule 用 CoroutineScope(SupervisorJob + IO) 接线异步重验证。
 *
 * 评审修复：actions 采用 LRU 语义的容量上限（[MAX_ACTIONS]），
 * 超限淘汰最久未注册的动作，防止映射无界增长。
 */
@Singleton
class RevalidateHub @Inject constructor() {
    private val actions = object : LinkedHashMap<String, suspend () -> Unit>(MAX_ACTIONS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, suspend () -> Unit>?): Boolean = size > MAX_ACTIONS
    }
    private val active = ConcurrentHashMap.newKeySet<String>()

    @Synchronized
    fun register(key: String, action: suspend () -> Unit) {
        actions[key] = action
    }

    @Synchronized
    fun unregister(key: String) {
        actions.remove(key)
    }

    /** 清缓存路径（[PriceRepository.clearCaches] / 清理 Worker）同步清空注册表 */
    @Synchronized
    fun clearAll() {
        actions.clear()
    }

    /** 过期读取触发：同 key 在途时直接跳过，失败静默（下次读取再试） */
    suspend fun revalidate(key: String) {
        val action = synchronized(this) { actions[key] } ?: return
        if (!active.add(key)) return
        try {
            runCatching { action() }
                .onFailure { LogT.w("CACHE 重验证失败 $key: ${it.javaClass.simpleName}") }
        } finally {
            active.remove(key)
        }
    }

    companion object {
        /** 注册表容量上限（LRU 语义，超限淘汰最久未注册的动作） */
        const val MAX_ACTIONS = 128
    }
}

/**
 * §阶段3 三级缓存通用模板：L1 TLRU → L2 快照 → L3 网络 → 写回。
 *
 * 取数流程（与旧手写逻辑语义对齐，另加健康度降级）：
 *  1. L1 命中（含过期值：stale-while-revalidate 由 TLRUCache 触发后台重验证）
 *  2. L2 快照仍在新鲜窗口 → 回填 L1 后返回
 *  3. 源健康则走网络（[fetch] 已含仓储层 singleflight）：
 *     成功 → 写回 L1+L2、记录健康、注册重验证；
 *     异常 → 记一次连续失败（取消异常直接透传，不记账不降级）；
 *     null = 合法空结果 → 不记失败（区分网络失败与空结果）
 *  4. 源被降级或网络失败：有 L2 快照则返回旧数据并标记 [StaleTracker]
 *     （站点改版/断网先展示旧数据而非报错），无快照返回 null
 *
 * @param T 领域类型；列表源传 List<X> 并用 [cacheable] 排除空列表
 */
class CachedSource<T : Any>(
    private val cache: TLRUCache<String>,
    private val health: SourceHealth,
    private val tracker: StaleTracker,
    private val hub: RevalidateHub,
    private val key: String,
    private val ttlMs: Long,
    private val codec: CacheCodec<T>,
    private val source: String,
    private val fetch: suspend () -> T?,
    // 返回 (快照, 写入时间)
    private val l2Load: (suspend () -> Pair<T, Long>?)? = null,
    private val l2Save: (suspend (T) -> Unit)? = null,
    private val cacheable: (T) -> Boolean = { true },
    /** L2 命中陈旧条目时是否发起网络重验证（false = L2 有行即返回，恢复 getJdProduct 旧语义） */
    private val revalidateOnStale: Boolean = true,
    private val now: () -> Long = System::currentTimeMillis
) {
    suspend fun get(): T? {
        // L1：命中即返回（过期值由 TLRU 先返回旧值并异步重验证）
        cache.peek(key)?.let { entry ->
            codec.decode(entry.value)?.let { value ->
                if (entry.isExpired(now())) {
                    // 过期命中（如降级写入的 1ms-TTL 旧快照）：保持/改为 stale 标记，
                    // 使 "旧数据" 提示可达；get() 复用 stale-while-revalidate 触发后台重验证
                    cache.get(key)
                    tracker.markStale(key)
                } else {
                    cache.get(key)
                    tracker.markFresh(key)
                }
                return value
            }
        }

        // L2：快照加载（新鲜窗口内直接复用）
        val snapshot = runCatching { l2Load?.invoke() }
            .onFailure { LogT.w("CACHE L2 读取失败 $key: ${it.javaClass.simpleName}") }
            .getOrNull()
        if (snapshot != null) {
            val (value, cachedAt) = snapshot
            if (now() - cachedAt <= ttlMs) {
                cache.put(key, codec.encode(value), ttlMs)
                hub.register(key) { refresh() }
                tracker.markFresh(key)
                return value
            }
            if (!revalidateOnStale) {
                // getJdProduct 旧语义：Room 有行即返回、不做陈旧重验证（避免放大 403 与熔断）
                return value
            }
        }

        // L3：网络取数（源降级时跳过，直接走陈旧兜底）
        if (!health.isDegraded(source)) {
            val fresh = try {
                fetch()
            } catch (e: CancellationException) {
                throw e // 取消透传：不计失败、不污染源健康度（3 次取消误降级问题）
            } catch (e: Exception) {
                LogT.w("NET 源[$source]取数异常 $key: ${e.javaClass.simpleName}")
                health.recordFailure(source) // 仅真实业务异常记账
                null
            }
            if (fresh != null) {
                health.recordSuccess(source)
                writeBack(fresh)
                tracker.markFresh(key)
                return fresh
            }
            // fetch() 返回 null 属合法空结果：不计失败（区分网络失败与 Empty）
        }

        // 降级兜底：返回陈旧快照并标记（短 TTL 入 L1，后续读取持续触发重验证）
        return snapshot?.first?.also { stale ->
            cache.put(key, codec.encode(stale), STALE_RETRY_TTL_MS)
            tracker.markStale(key)
            hub.register(key) { refresh() }
        }
    }

    /** 后台重验证：跳过 L1/L2 直取网络；真实异常记健康度，下次读取再试 */
    suspend fun refresh() {
        if (health.isDegraded(source)) return
        val fresh = try {
            fetch()
        } catch (e: CancellationException) {
            throw e // 取消透传：不记账、不降级，被取消的重验证不算失败
        } catch (e: Exception) {
            LogT.w("NET 源[$source]重验证异常 $key: ${e.javaClass.simpleName}")
            health.recordFailure(source)
            return
        }
        if (fresh == null) return // 合法空结果不计失败
        health.recordSuccess(source)
        writeBack(fresh)
        tracker.markFresh(key)
    }

    private suspend fun writeBack(value: T) {
        if (!cacheable(value)) return
        cache.put(key, codec.encode(value), ttlMs)
        runCatching { l2Save?.invoke(value) }
            .onFailure { LogT.w("CACHE L2 写回失败 $key: ${it.javaClass.simpleName}") }
        hub.register(key) { refresh() }
    }

    companion object {
        /** 陈旧兜底值入 L1 的 TTL：立即过期 → 每次读取都会触发后台重验证 */
        const val STALE_RETRY_TTL_MS = 1L
    }
}
