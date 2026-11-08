package com.pricelens.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.pricelens.data.local.entity.AutoTrackRunEntity
import com.pricelens.data.local.entity.PurchaseRecordEntity
import kotlinx.coroutines.flow.Flow

/**
 * 自动追价轮次记录读写（DB v5 新增表 `auto_track_runs`）。
 *
 * 只增不删的单向日志：UI 的「运行历史」直接按时间倒序读；保留上限由
 * [AutoTrackRunEntity.MAX_KEPT_RUNS] + [deleteOlderThan] 在每次写入后顺手清理。
 */
@Dao
interface AutoTrackRunDao {

    @Insert
    suspend fun insert(run: AutoTrackRunEntity): Long

    /** 最近 [limit] 轮（时间倒序）：自动追价中心的运行历史列表用 */
    @Query("SELECT * FROM auto_track_runs ORDER BY atMs DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AutoTrackRunEntity>>

    /** 最近一轮（旧版本没有 prefs 记录时用它回填状态；null = 从没跑过） */
    @Query("SELECT * FROM auto_track_runs ORDER BY atMs DESC, id DESC LIMIT 1")
    suspend fun latestOnce(): AutoTrackRunEntity?

    /** 清理过期轮次（`atMs < cutoff`）：每次写入后顺手清，防止小表无界增长 */
    @Query("DELETE FROM auto_track_runs WHERE atMs < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    /** 只保留最近 [keep] 条（按 atMs 倒序定位分界点，一次 DELETE 完成） */
    @Query(
        "DELETE FROM auto_track_runs WHERE atMs < " +
            "(SELECT atMs FROM auto_track_runs ORDER BY atMs DESC, id DESC LIMIT 1 OFFSET :keep)"
    )
    suspend fun trimToLatest(keep: Int)

    @Query("SELECT COUNT(*) FROM auto_track_runs")
    suspend fun count(): Int
}

/**
 * 购买记录（价保助手）读写。
 *
 * 幂等提醒靠 `notifiedAtMs`：先读实体 → 评估 → 需要提醒时写回 `notifiedAtMs = now` 再发通知，
 * 下一次评估 `alreadyNotified = true` 就不会重复发。
 */
@Dao
interface PurchaseRecordDao {

    @Insert
    suspend fun insert(record: PurchaseRecordEntity): Long

    @Update
    suspend fun update(record: PurchaseRecordEntity)

    /** 全部购买记录（新买的在前）：价保页列表与状态聚合用 */
    @Query("SELECT * FROM purchase_records ORDER BY purchasedAtMs DESC, id DESC")
    fun observeAll(): Flow<List<PurchaseRecordEntity>>

    /** 一次性快照（周期检查/备份导出用） */
    @Query("SELECT * FROM purchase_records ORDER BY purchasedAtMs DESC, id DESC")
    suspend fun getAllOnce(): List<PurchaseRecordEntity>

    @Query("SELECT * FROM purchase_records WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): PurchaseRecordEntity?

    /** 备份恢复的「同一笔购买」判重：平台 + 商品号 + 购买时刻 + 实付价全部相同才算同一条 */
    @Query(
        "SELECT * FROM purchase_records WHERE platform = :platform AND purchasedAtMs = :purchasedAtMs " +
            "AND price = :price AND (productId = :productId OR (productId IS NULL AND :productId IS NULL)) LIMIT 1"
    )
    suspend fun findByNaturalKey(platform: String, productId: String?, purchasedAtMs: Long, price: Double): PurchaseRecordEntity?

    /** 用户点「已申请」：只改标记，保留金额与截止日（历史事实） */
    @Query("UPDATE purchase_records SET claimedAtMs = :now WHERE id = :id")
    suspend fun markClaimed(id: Long, now: Long)

    @Query("DELETE FROM purchase_records WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM purchase_records")
    suspend fun count(): Int
}

/**
 * 调度决策用的投影行：一个活跃盯价目标的「目标价 + 最近一次曲线收盘价」。
 *
 * 为什么用一条 SQL 拿全部目标：自适应调度的输入只需要"最近目标距离"，逐个目标
 * `getByProduct()` 会把整条曲线读回内存（30 天 × N 目标），而这里只要每天一行的最新值。
 *
 * @param lastPrice 最近一天曲线的收盘价；null = 该目标还没有任何曲线点（**不是 0**）
 */
data class TargetPriceRow(
    val productId: String,
    val targetPrice: Double,
    val lastPrice: Double?
)

/** 自动追价调度输入投影（只读：不新增表，读既有 price_targets / price_history） */
@Dao
interface TargetPlanDao {

    @Query(
        "SELECT t.productId AS productId, t.targetPrice AS targetPrice, " +
            "(SELECT h.price FROM price_history h WHERE h.productId = t.productId " +
            "ORDER BY h.date DESC LIMIT 1) AS lastPrice " +
            "FROM price_targets t WHERE t.active = 1"
    )
    suspend fun activePriceRows(): List<TargetPriceRow>
}
