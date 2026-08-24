package com.pricelens.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** §4.5 商品缓存：只存结构化元数据，图片只存 URL（不存 Base64/二进制） */
@Entity(
    tableName = "products",
    indices = [Index("lastAccessedAt"), Index("pinned")]
)
data class ProductEntity(
    @PrimaryKey val id: String,          // 平台:商品ID，如 jd:100012043978
    val title: String,
    val currentPrice: Double,
    val originalPrice: Double?,
    val platform: String,                // jd / tb / pdd
    val imageUrl: String,                // 只存 URL，Coil 负责缓存位图
    val cachedAt: Long,
    val lastAccessedAt: Long,
    val ttl: Long,                       // §4.3 实时价格默认 30min
    val pinned: Boolean = false          // 收藏：TLRU 永不淘汰
)

/** §4.5 价格历史：每天 1 个采样点，不存原始高频数据 */
@Entity(
    tableName = "price_history",
    indices = [Index("productId"), Index("date")]
)
data class PriceHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: String,
    val date: String,                    // yyyy-MM-dd
    val price: Double,
    val isLowest: Boolean = false,
    val isHighest: Boolean = false
)

/** 盯价目标：用户设定目标价，后台 WorkManager 周期检查 */
@Entity(tableName = "price_targets")
data class PriceTargetEntity(
    @PrimaryKey val productId: String,
    val title: String,
    val platform: String,
    val targetPrice: Double,
    val active: Boolean = true,
    val createdAt: Long
)

/** 搜索记录：供搜索框联想，限制条数 */
@Entity(tableName = "search_records")
data class SearchRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val keyword: String,
    val searchedAt: Long
)
