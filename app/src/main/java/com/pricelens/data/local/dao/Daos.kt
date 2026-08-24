package com.pricelens.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
