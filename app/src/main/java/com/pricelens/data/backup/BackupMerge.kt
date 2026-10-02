package com.pricelens.data.backup

import com.pricelens.data.local.entity.PriceTargetEntity
import com.pricelens.data.local.entity.ProductEntity

/**
 * §五 恢复合并：「新者胜」纯函数（不碰 Room/文件，JVM 可单测）。
 *
 * 口径（推荐文案里写清楚的那一套）：
 *  - 远端条目的 updatedAt 比本机记录的 updatedAt 新 → 采用远端（返回要 upsert 的实体）；
 *  - 相等或本机更新 → 返回 null（保持本机，不写库）；
 *  - 本机没有这条 → 直接采用远端。
 *
 * 比较用的"本机 updatedAt"与编码侧 [FavoriteDto.from] / [TargetDto.from] 完全同口径，
 * 否则会出现"备份说自己更新、恢复却判自己旧"的自相矛盾。
 */
object BackupMerge {

    /** 收藏：远端新则返回要落库的实体（pinned=true），否则 null */
    fun favorite(local: ProductEntity?, remote: FavoriteDto): ProductEntity? {
        val localUpdatedAt = local?.let { maxOf(it.cachedAt, it.lastAccessedAt) } ?: return remote.toEntity()
        return if (remote.updatedAt > localUpdatedAt) remote.toEntity() else null
    }

    /** 盯价目标：远端新则返回要落库的实体，否则 null */
    fun target(local: PriceTargetEntity?, remote: TargetDto): PriceTargetEntity? {
        val localUpdatedAt = local?.createdAt ?: return remote.toEntity()
        return if (remote.updatedAt > localUpdatedAt) remote.toEntity() else null
    }
}

/**
 * §五 远端保留策略（纯函数）：
 * 只决定"该删哪些"，删除动作与失败处理归 [BackupRepository]。
 *
 * 排序：getlastmodified 降序；有些服务器 PROPFIND 不回 getlastmodified（解析为 0），
 * 此时按文件名降序兜底 —— 文件名内嵌 `yyyyMMdd-HHmmss`，字典序即时间序。
 */
object BackupRetention {

    fun newestFirst(items: List<DavItem>): List<DavItem> {
        val byTime = compareByDescending<DavItem> { it.modified }.thenByDescending { it.href }
        return items.sortedWith(byTime)
    }

    /** 保留最新 [keep] 份，返回其余（即应删除的，沿新→旧序，最旧的排在最后） */
    fun toDelete(items: List<DavItem>, keep: Int = BackupFormat.KEEP_LATEST): List<DavItem> = newestFirst(items).drop(keep)
}
