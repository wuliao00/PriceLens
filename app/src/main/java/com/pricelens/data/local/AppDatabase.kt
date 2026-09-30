package com.pricelens.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.pricelens.data.local.dao.CacheEntryDao
import com.pricelens.data.local.dao.DomainPenaltyDao
import com.pricelens.data.local.dao.PriceHistoryDao
import com.pricelens.data.local.dao.PriceTargetDao
import com.pricelens.data.local.dao.ProductDao
import com.pricelens.data.local.dao.SearchRecordDao
import com.pricelens.data.local.entity.CacheEntryEntity
import com.pricelens.data.local.entity.DomainPenaltyEntity
import com.pricelens.data.local.entity.PriceHistoryEntity
import com.pricelens.data.local.entity.PriceTargetEntity
import com.pricelens.data.local.entity.ProductEntity
import com.pricelens.data.local.entity.SearchRecordEntity

/** §4.1 L2 层：Room 结构化缓存（预算 10MB，§4.6） */
@Database(
    entities = [
        ProductEntity::class,
        PriceHistoryEntity::class,
        PriceTargetEntity::class,
        SearchRecordEntity::class,
        CacheEntryEntity::class,
        DomainPenaltyEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun priceHistoryDao(): PriceHistoryDao
    abstract fun priceTargetDao(): PriceTargetDao
    abstract fun searchRecordDao(): SearchRecordDao
    abstract fun cacheEntryDao(): CacheEntryDao
    abstract fun domainPenaltyDao(): DomainPenaltyDao

    companion object {
        /**
         * §阶段3 1→2：新增通用缓存条目表与域名熔断表。
         * 显式 Migration；构建时不启用 fallbackToDestructiveMigration，
         * 版本不匹配会报错而非静默清库（用户收藏/盯价目标不可丢）。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `cache_entries` (" +
                        "`entryKey` TEXT NOT NULL, `value` TEXT NOT NULL, " +
                        "`cachedAt` INTEGER NOT NULL, `ttl` INTEGER NOT NULL, " +
                        "`source` TEXT NOT NULL, PRIMARY KEY(`entryKey`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_cache_entries_cachedAt` " +
                        "ON `cache_entries` (`cachedAt`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `domain_penalties` (" +
                        "`domain` TEXT NOT NULL, `untilMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`domain`))"
                )
            }
        }

        /**
         * 价格历史 v2→v3：`price_history` 补「出处 / 当日至低 / 观测时刻」三列，
         * 清掉既有同日重复行，最后建 `(productId, date)` 唯一索引。
         *
         * 抽成常量列表是为了让**验证脚本跑的就是这段迁移**：
         * `tools_verify/migration_2_3_check.py` 从本文件里把这些 SQL 抠出来，
         * 在 sqlite3 上按旧实体的真实 DDL 建一个 v2 脏库再执行一遍，
         * 避免"测试跑的 SQL 和代码里的 SQL 是两份"。
         *
         * 顺序**不可调换**：
         *  1. 先 `ALTER TABLE ADD COLUMN`：SQLite 给非空表加 NOT NULL 列必须带 DEFAULT；
         *     这三个默认值同时写进了实体的 `@ColumnInfo(defaultValue = ...)`，
         *     两边必须逐字对上（Room 的 KSP 不会从 Kotlin 初始化器推默认值）；
         *  2. 再删同日重复行 —— v2 的 `insertAll` 用自增主键，`@Insert(REPLACE)` 永不冲突，
         *     每次 persistHistory 都把全部点重插一遍，库里同日多行是既有事实；
         *  3. 最后建唯一索引（顺序反了会因既有重复直接失败）。
         *
         * `source` 对既有行的默认值是「来源未记录」哨兵（[com.pricelens.domain.PriceSource.UNRECORDED_NAME]），
         * **不是** MANMANBUY：老行是「慢慢买 + 星罗合并结果」缓存下来的，事后无法区分出处，编造出处就是造假。
         */
        val MIGRATION_2_3_SQL: List<String> = listOf(
            "ALTER TABLE `price_history` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'UNRECORDED'",
            "ALTER TABLE `price_history` ADD COLUMN `dayLow` REAL NOT NULL DEFAULT 0.0",
            "ALTER TABLE `price_history` ADD COLUMN `recordedAt` INTEGER NOT NULL DEFAULT 0",
            "DELETE FROM price_history WHERE id NOT IN " +
                "(SELECT MAX(id) FROM price_history GROUP BY productId, date)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_price_history_productId_date` " +
                "ON `price_history` (`productId`, `date`)"
        )

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_2_3_SQL.forEach { db.execSQL(it) }
            }
        }

        /**
         * 全部显式迁移的**唯一清单**：DI 里的 `Room.databaseBuilder` 只认这一个列表。
         *
         * 为什么要有这条纪律：v2.6.5 的 2→3 迁移最初只登记在 `AppDatabase.getInstance()` 里，
         * 而运行时真正建库的是 `di/AppModule.provideDatabase()`（Hilt），它只带了 1→2 ——
         * 真机一装 2.6.5 就崩在 `A migration from 2 to 3 was required but not found`。
         * 单测跑的是纯函数，摸不到这条线，只有真机开库才暴露。
         * 现在两处都从本清单取，`getInstance()` 那份重复的 builder 已删除（它没有任何调用方）。
         * 守卫见 `DatabaseMigrationWiringTest`。
         */
        val MIGRATIONS: List<Migration> = listOf(MIGRATION_1_2, MIGRATION_2_3)
    }
}

/** §4.3 分级 TTL 策略（毫秒） */
object CacheTTL {
    const val PRODUCT_INFO = 6L * 3600 * 1000 // 商品基础信息 6h
    const val LIVE_PRICE = 30L * 60 * 1000 // 实时价格 30min
    const val PRICE_HISTORY = 24L * 3600 * 1000 // 历史曲线 24h
    const val BILI_SEARCH = 2L * 3600 * 1000 // B站搜索 2h
    const val SMZDM_FEED = 1L * 3600 * 1000 // 值得买爆料 1h
    const val COUPON = 15L * 60 * 1000 // 优惠券 15min
    const val COMMENTS = 4L * 3600 * 1000 // 评论 4h
}
