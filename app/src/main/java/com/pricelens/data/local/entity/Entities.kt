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
    // 平台:商品ID，如 jd:100012043978
    @PrimaryKey val id: String,
    val title: String,
    val currentPrice: Double,
    val originalPrice: Double?,
    // jd / tb / pdd
    val platform: String,
    // 只存 URL，Coil 负责缓存位图
    val imageUrl: String,
    val cachedAt: Long,
    val lastAccessedAt: Long,
    // §4.3 实时价格默认 30min
    val ttl: Long,
    // 收藏：TLRU 永不淘汰
    val pinned: Boolean = false
)

/** §4.5 价格历史：每天 1 个采样点，不存原始高频数据 */
@Entity(
    tableName = "price_history",
    indices = [Index("productId"), Index("date")]
)
data class PriceHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: String,
    // yyyy-MM-dd
    val date: String,
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

/**
 * §阶段3 通用 L2 缓存条目：历史价/券/搜索/值得买等非结构化结果的写回层。
 * 结构化商品仍走 [ProductEntity]（收藏/最近浏览依赖其字段）。
 */
@Entity(
    tableName = "cache_entries",
    indices = [Index("cachedAt")]
)
data class CacheEntryEntity(
    // 与 L1 TLRU 同 key（如 bili:search:xxx）
    @PrimaryKey val entryKey: String,
    // CacheCodec 编码后的 JSON 文本
    val value: String,
    // 写入时间（陈旧降级判断依据）
    val cachedAt: Long,
    // 新鲜窗口（毫秒）
    val ttl: Long,
    // 数据源名（bili/gwd/smz/dd/sh/mmb）
    val source: String
)

/** §阶段3 域名熔断持久化：403 解封时间戳，重启后恢复（避免重启即撞反爬） */
@Entity(tableName = "domain_penalties")
data class DomainPenaltyEntity(
    @PrimaryKey val domain: String,
    // 解封时间戳（epoch millis）
    val untilMs: Long
)
