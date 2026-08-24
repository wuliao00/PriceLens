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
 */
class TLRUCache<K : Any>(
    private val maxSizeBytes: Long = 8L * 1024 * 1024,
    private val defaultTtlMs: Long = 30 * 60 * 1000L,          // §4.3 实时价格 30min
    private val onStale: (suspend (key: K) -> Unit)? = null,   // 过期后台刷新
    private val now: () -> Long = System::currentTimeMillis,
    private val revalidateScope: kotlinx.coroutines.CoroutineScope? = null,
) {
    class Entry(
        val key: Any,
        @Volatile var value: String,                 // JSON 字符串
        @Volatile var createdAt: Long,
        @Volatile var lastAccessedAt: Long,
        @Volatile var ttl: Long,
        @Volatile var pinned: Boolean = false,
    ) {
        val sizeBytes: Int get() = value.toByteArray(Charsets.UTF_8).size
        fun isExpired(nowMs: Long) = nowMs - createdAt > ttl
    }

    private val map = ConcurrentHashMap<Any, Entry>()
    @Volatile private var currentSize = 0L

    fun get(key: K): String? {
        val entry = map[key] ?: return null
        if (entry.isExpired(now())) {
            // stale-while-revalidate：先返回旧值，后台刷新
            revalidateScope?.launch { onStale?.invoke(key) }
        }
        entry.lastAccessedAt = now()
        return entry.value
    }

    fun put(key: K, value: String, ttlMs: Long = defaultTtlMs, pinned: Boolean = false) {
        val existing = map[key]
        if (existing != null) currentSize -= existing.sizeBytes
        val entry = Entry(key, value, now(), now(), ttlMs, pinned)
        map[key] = entry
        currentSize += entry.sizeBytes
        evict()
    }

    fun pin(key: K) { map[key]?.pinned = true }
    fun unpin(key: K) { map[key]?.pinned = false }
    fun remove(key: K) {
        map.remove(key)?.let { currentSize -= it.sizeBytes }
    }

    fun sizeBytes(): Long = currentSize

    /** 遍历删除全部过期条目（轻量清理用） */
    fun clearExpired() {
        val nowMs = now()
        map.values.filter { it.isExpired(nowMs) }.forEach { remove(it.key as K) }
    }

    fun clear() {
        map.clear()
        currentSize = 0
    }

    /**
     * §4.2 evict：超限后先删"过期且最久未访问"，再退化为纯 LRU；收藏条目豁免。
     */
    private fun evict() {
        while (currentSize > maxSizeBytes) {
            val nowMs = now()
            val victim = map.values
                .filter { !it.pinned }
                .filter { it.isExpired(nowMs) }
                .minByOrNull { it.lastAccessedAt }
                ?: map.values
                    .filter { !it.pinned }
                    .minByOrNull { it.lastAccessedAt }
                ?: break // 全部 pinned，无法再淘汰
            remove(victim.key as K)
        }
    }
}
