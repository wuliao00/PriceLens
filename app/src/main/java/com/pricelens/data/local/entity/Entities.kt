package com.pricelens.data.local.entity

import androidx.room.ColumnInfo
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

/**
 * §4.5 价格历史：每天 1 个采样点，不存原始高频数据。
 *
 * 「每天 1 点」靠的是 `(productId, date)` **唯一索引**（v3 才真正有这个约束）。
 * 主键仍是自增 `id`：v2 的 `insertAll` 用 `@Insert(onConflict = REPLACE)`，而自增主键永不冲突，
 * 所以旧注释里"Room 侧按日 REPLACE"是假的 —— 每次 persistHistory 都把全部点重插一遍，
 * 同一天堆出多行。接入每 30 分钟一次的盯价自采后（每商品每天 ~48 行）这个缺陷会立刻变成坏曲线，
 * 因此 v3 一并补唯一索引 + 迁移里先删既有重复（见 AppDatabase.MIGRATION_2_3）。
 *
 * 两个价值的分工（用户已拍板的语义，别混）：
 *  - [price] = **当日最后一个 live 样本**（日线收盘语义，后写的覆盖先写的）；
 *  - [dayLow] = **当日已见最低价**，算「历史最低」用的是它 —— 收盘点不能冒充盘中低点。
 */
@Entity(
    tableName = "price_history",
    indices = [
        Index("productId"),
        Index("date"),
        // 真正的"每天一点"约束：自增 id 作主键迁移风险最小，唯一索引负责去重
        Index(value = ["productId", "date"], unique = true)
    ]
)
data class PriceHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: String,
    // yyyy-MM-dd
    val date: String,
    // 当日最后一个 live 样本（收盘语义）：取不到价的那一轮不写，绝不写 0 也不沿用上一轮的旧价
    val price: Double,
    val isLowest: Boolean = false,
    val isHighest: Boolean = false,
    // 出处：PriceSource.name。默认值是「来源未记录」哨兵（见 PriceSource.UNRECORDED_NAME）。
    // @ColumnInfo(defaultValue) 不是装饰：Room 2.8 的 KSP **不会**把 Kotlin 初始化器当成列默认值
    // （实测导出的 schema 里三列的 defaultValue 是空的），而迁移 SQL 必须带 DEFAULT
    // （SQLite 不允许给非空表加 NOT NULL 列且无默认值）。两边不写在一起，运行时 TableInfo 校验就会判"迁移没做对"。
    @ColumnInfo(defaultValue = "'UNRECORDED'")
    val source: String = "UNRECORDED",
    // 当日已见最低价（<= price）；0 = 该列问世前的老行没有这个信息，读侧要回退到 price
    @ColumnInfo(defaultValue = "0.0")
    val dayLow: Double = 0.0,
    // 这一行最后一次被观测到的时刻：同日万一还有脏行，据此认"当日最后一个点"
    @ColumnInfo(defaultValue = "0")
    val recordedAt: Long = 0
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

/**
 * §免凭证曲线：浮窗上被用户按下「就是这个商品」确认过的商品身份（一行 = 一件商品）。
 * productId 走 `ovl:<摘要>` 命名空间，与 `jd:<sku>` 分家；它的日点照样进
 * [PriceHistoryEntity]（(productId,date) 唯一索引天然按 productId 分空间）。
 *
 * 为什么不塞进 price_targets：无 SKU 的确认身份没有查价通道，
 * `WatchTargetPolicy.channelOf`→NONE → 会被 `PriceWatchViewModel.untrackableTargets`
 * 当`历史遗留的坏目标`报警，还白白拉起 30 分钟轮次（设计附录 B 反例一）。
 */

@Entity(
    tableName = "watch_identity",
    indices = [Index("platform"), Index("normalizedTitle")]
)
data class WatchIdentityEntity(
    // ovl:<base36(platform|规范化标题)>，键一经生成不可变（标题后续变化不改键）
    @PrimaryKey val productId: String,
    // 商城平台小写名（jd/taobao/pdd），与 ShopPlatform.name.lowercase() 对齐
    val platform: String,
    // 用户当场看到并确认的那串标题（浮窗①行的原文）
    val title: String,
    val normalizedTitle: String,
    val confirmedAt: Long,
    // 最近一次匹配上这行身份的 detection 时刻；0 = 确认后还没再遇到过
    @ColumnInfo(defaultValue = "0")
    val lastSeenAt: Long = 0,
    @ColumnInfo(defaultValue = "0.0")
    val lastPrice: Double = 0.0,
    // PriceBasis.name：最近读到的价格口径，UI 据此解释"为什么今天没记上"
    @ColumnInfo(defaultValue = "'PAGE'")
    val lastBasis: String = "PAGE"
)
