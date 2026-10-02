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
import com.pricelens.data.local.entity.WatchIdentityEntity
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

    /**
     * 单条按日 upsert。
     *
     * v3 之前这条与 [insertAll] 一样是"假 REPLACE"：主键自增永不冲突。
     * 现在 `(productId, date)` 上有唯一索引，冲突才真的发生 —— 一天一行、后写的覆盖先写的。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDay(point: PriceHistoryEntity): Long

    /** 当日已存的那一点：写收盘点前要先读它，`dayLow` 必须与已存的至低比大小 */
    @Query("SELECT * FROM price_history WHERE productId = :productId AND date = :date LIMIT 1")
    suspend fun getDayPoint(productId: String, date: String): PriceHistoryEntity?

    @Query("SELECT * FROM price_history WHERE productId = :productId ORDER BY date")
    suspend fun getByProduct(productId: String): List<PriceHistoryEntity>

    /**
     * 曲线出处统计：每个来源各有几个「日」。
     *
     * `COUNT(DISTINCT date)` 而不是 `COUNT(*)`：既有脏库里同日多行时，
     * 脚注说的"自采天数"必须是天数，不是行数。
     */
    @Query(
        "SELECT source AS source, COUNT(DISTINCT date) AS days FROM price_history " +
            "WHERE productId = :productId GROUP BY source"
    )
    suspend fun countDaysBySource(productId: String): List<SourceDayCount>

    /** 该 productId 的已记日数（浮窗「已记 N 天」）：数的是天不是行数，与 countDaysBySource 同口径 */
    @Query("SELECT COUNT(DISTINCT date) FROM price_history WHERE productId = :productId")
    suspend fun countDays(productId: String): Int

    /** 取消确认时连带删掉这条身份的全部日点：只删身份不删点 = 库里的点无处显示，脚注还把它算成「本机自采」 */
    @Query("DELETE FROM price_history WHERE productId = :productId")
    suspend fun deleteByProduct(productId: String)

    @Query("DELETE FROM price_history WHERE date < :dateCutoff")
    suspend fun deleteOlderThan(dateCutoff: String)
}

/** [PriceHistoryDao.countDaysBySource] 的投影行（来源 → 该来源覆盖的天数） */
data class SourceDayCount(val source: String, val days: Int)

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

/** §免凭证曲线：浮窗确认的商品身份读写。小表，行数上限由 OverlayIdentityPolicy.MAX_IDENTITIES 钉住 */
@Dao
interface WatchIdentityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(identity: WatchIdentityEntity)

    @Query("SELECT * FROM watch_identity ORDER BY confirmedAt")
    suspend fun getAllOnce(): List<WatchIdentityEntity>

    @Query("SELECT * FROM watch_identity ORDER BY confirmedAt")
    fun observeAll(): Flow<List<WatchIdentityEntity>>

    @Query("SELECT * FROM watch_identity WHERE productId = :productId LIMIT 1")
    suspend fun getByProduct(productId: String): WatchIdentityEntity?

    @Query("UPDATE watch_identity SET lastSeenAt = :now, lastPrice = :price, lastBasis = :basis WHERE productId = :productId")
    suspend fun touchLastSeen(productId: String, now: Long, price: Double, basis: String)

    @Query("DELETE FROM watch_identity WHERE productId = :productId")
    suspend fun delete(productId: String)
}
