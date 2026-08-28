package com.pricelens.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pricelens.data.local.entity.CacheEntryEntity
import com.pricelens.data.local.entity.DomainPenaltyEntity
import com.pricelens.data.local.entity.PriceHistoryEntity
import com.pricelens.data.local.entity.PriceTargetEntity
import com.pricelens.data.local.entity.ProductEntity
import com.pricelens.data.local.entity.SearchRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(product: ProductEntity)

    @Query("SELECT * FROM products WHERE id = :id")
    suspend fun getById(id: String): ProductEntity?

    @Query("SELECT * FROM products ORDER BY lastAccessedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 20): Flow<List<ProductEntity>>

    /** 我的收藏（个人页） */
    @Query("SELECT * FROM products WHERE pinned = 1 ORDER BY lastAccessedAt DESC")
    fun observePinned(): Flow<List<ProductEntity>>

    @Query("UPDATE products SET lastAccessedAt = :now WHERE id = :id")
    suspend fun touch(id: String, now: Long)

    @Query("UPDATE products SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    /** 启动轻量清理：删掉已过期条目 */
    @Query("DELETE FROM products WHERE cachedAt + ttl < :now AND pinned = 0")
    suspend fun deleteExpired(now: Long)

    /** 紧急清理（存储压力）：删除所有非收藏且近 3 天未访问的缓存 */
    @Query("DELETE FROM products WHERE pinned = 0 AND lastAccessedAt < :threshold")
    suspend fun deleteNonPinnedOlderThan(threshold: Long)

    @Query("SELECT COUNT(*) FROM products")
    suspend fun count(): Int
}

@Dao
interface PriceHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(points: List<PriceHistoryEntity>)

    @Query("SELECT * FROM price_history WHERE productId = :productId ORDER BY date")
    suspend fun getByProduct(productId: String): List<PriceHistoryEntity>

    @Query("DELETE FROM price_history WHERE date < :dateCutoff")
    suspend fun deleteOlderThan(dateCutoff: String)
}

@Dao
interface PriceTargetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(target: PriceTargetEntity)

    @Query("SELECT * FROM price_targets WHERE active = 1")
    suspend fun getAllActive(): List<PriceTargetEntity>

    @Query("UPDATE price_targets SET active = 0 WHERE productId = :productId")
    suspend fun deactivate(productId: String)

    @Query("SELECT * FROM price_targets WHERE active = 1")
    fun observeActive(): Flow<List<PriceTargetEntity>>
}

@Dao
interface SearchRecordDao {
    @Insert
    suspend fun insert(record: SearchRecordEntity)

    @Query("SELECT DISTINCT keyword FROM search_records ORDER BY searchedAt DESC LIMIT :limit")
    fun observeRecentKeywords(limit: Int = 10): Flow<List<String>>

    @Query("DELETE FROM search_records WHERE searchedAt < :threshold")
    suspend fun deleteOlderThan(threshold: Long)
}

/** §阶段3 通用 L2 缓存条目读写 */
@Dao
interface CacheEntryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: CacheEntryEntity)

    @Query("SELECT * FROM cache_entries WHERE entryKey = :key")
    suspend fun get(key: String): CacheEntryEntity?

    /** 清理已超出新鲜窗口的条目（允许保留陈旧快照供降级用，故另提供硬删） */
    @Query("DELETE FROM cache_entries WHERE cachedAt + ttl < :now")
    suspend fun deleteExpired(now: Long)

    /** 硬删超过保留上限的条目（防止陈旧快照无限堆积） */
    @Query("DELETE FROM cache_entries WHERE cachedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM cache_entries")
    suspend fun deleteAll()
}

/** §阶段3 域名熔断持久化（域名 → 解封时间戳） */
@Dao
interface DomainPenaltyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DomainPenaltyEntity)

    /** 启动时恢复：仅读仍在熔断期内的域名 */
    @Query("SELECT * FROM domain_penalties WHERE untilMs > :now")
    suspend fun loadActive(now: Long): List<DomainPenaltyEntity>

    /** 访问时清过期条目，避免小表无界增长 */
    @Query("DELETE FROM domain_penalties WHERE untilMs <= :now")
    suspend fun deleteExpired(now: Long)
}
