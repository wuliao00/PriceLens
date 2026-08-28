package com.pricelens.data.cache

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.launch

/**
 * §4.2 时间感知 LRU（TLRU）内存缓存 —— L1 层（默认 8MB 上限）。
 *
 * 淘汰顺序（借鉴 Grab 工程实践）：
 *  1. 已过期的条目优先删除（其中又按最久未访问的先删）
 *  2. 未过期但最久未访问的条目次优先（经典 LRU）
 *  3. 用户标记"收藏"（pin）的条目永不淘汰
 *
 * 过期读取采用 stale-while-revalidate：立即返回旧数据，同时异步触发刷新回调。
 *
 * 阶段3 修复：
 *  - Entry.sizeBytes 在 put 时一次性预计算（原先每次访问重算 UTF-8 字节）
 *  - evict 一次性排序取牺牲者（原先循环内反复全量过滤求值）
 *  - onStale 去重：同一 key 的后台重验证在途时不重复触发
 *
 * 评审修复：put/remove/evict/clear 均持 synchronized(map)，
 * currentSize 读-改-写原子化，防并发账目漂移/负值/淘汰失效。
 */
class TLRUCache<K : Any>(
    private val maxSizeBytes: Long = 8L * 1024 * 1024,
    // §4.3 实时价格 30min
    private val defaultTtlMs: Long = 30 * 60 * 1000L,
    // 过期后台刷新
    private val onStale: (suspend (key: K) -> Unit)? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val revalidateScope: kotlinx.coroutines.CoroutineScope? = null
) {
    class Entry(
        val key: Any,
        // JSON 字符串
        @Volatile var value: String,
        @Volatile var createdAt: Long,
        @Volatile var lastAccessedAt: Long,
        @Volatile var ttl: Long,
        @Volatile var pinned: Boolean = false
    ) {
        /** 阶段3：put 时预计算，evict/sizeBytes 不再反复编码 */
        val sizeBytes: Int = value.toByteArray(Charsets.UTF_8).size
        fun isExpired(nowMs: Long) = nowMs - createdAt > ttl
    }

    private val map = ConcurrentHashMap<Any, Entry>()
    private val revalidating = ConcurrentHashMap.newKeySet<Any>()

    @Volatile private var currentSize = 0L

    fun get(key: K): String? {
        val entry = map[key] ?: return null
        if (entry.isExpired(now())) {
            // stale-while-revalidate：先返回旧值，后台刷新（同 key 在途去重）
            if (revalidating.add(key)) {
                revalidateScope?.launch {
                    try {
                        onStale?.invoke(key)
                    } finally {
                        revalidating.remove(key)
                    }
                } ?: revalidating.remove(key) // 无 scope 时立即释放名额
            }
        }
        entry.lastAccessedAt = now()
        return entry.value
    }

    /** 返回原始条目，不更新访问时间、不触发重验证（供调用方自行判定过期/陈旧语义） */
    fun peek(key: K): Entry? = map[key]

    fun put(key: K, value: String, ttlMs: Long = defaultTtlMs, pinned: Boolean = false) {
        synchronized(map) {
            val existing = map[key]
            if (existing != null) currentSize -= existing.sizeBytes
            val entry = Entry(key, value, now(), now(), ttlMs, pinned)
            map[key] = entry
            currentSize += entry.sizeBytes
            evict()
        }
    }

    fun pin(key: K) {
        map[key]?.pinned = true
    }
    fun unpin(key: K) {
        map[key]?.pinned = false
    }
    fun remove(key: K) {
        synchronized(map) {
            map.remove(key)?.let { currentSize -= it.sizeBytes }
        }
    }

    fun sizeBytes(): Long = currentSize

    /** 遍历删除全部过期条目（轻量清理用） */
    fun clearExpired() {
        val nowMs = now()
        synchronized(map) {
            map.values.filter { it.isExpired(nowMs) }.forEach { remove(it.key as K) }
        }
    }

    fun clear() {
        synchronized(map) {
            map.clear()
            currentSize = 0
        }
    }

    /**
     * §4.2 evict：超限后先删"过期且最久未访问"，再退化为纯 LRU；收藏条目豁免。
     * 阶段3：一次性排序出牺牲序列（过期优先，组内按最久未访问），顺序移除至达标。
     */
    private fun evict() {
        synchronized(map) {
            if (currentSize <= maxSizeBytes) return@synchronized
            val nowMs = now()
            val victims = map.values
                .filter { !it.pinned }
                .sortedWith(compareBy({ !it.isExpired(nowMs) }, { it.lastAccessedAt }))
            for (victim in victims) {
                if (currentSize <= maxSizeBytes) break
                remove(victim.key as K)
            }
        }
    }
}
