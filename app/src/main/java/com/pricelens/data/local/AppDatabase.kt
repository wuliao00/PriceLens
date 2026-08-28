package com.pricelens.data.local

import androidx.room.Database
import androidx.room.Room
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
    version = 2,
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

        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: android.content.Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "pricelens.db"
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
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
