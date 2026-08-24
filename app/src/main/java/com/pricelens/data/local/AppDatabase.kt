package com.pricelens.data.local

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.pricelens.data.local.dao.PriceHistoryDao
import com.pricelens.data.local.dao.PriceTargetDao
import com.pricelens.data.local.dao.ProductDao
import com.pricelens.data.local.dao.SearchRecordDao
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
        SearchRecordEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun priceHistoryDao(): PriceHistoryDao
    abstract fun priceTargetDao(): PriceTargetDao
    abstract fun searchRecordDao(): SearchRecordDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: android.content.Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "pricelens.db"
                ).build().also { instance = it }
            }
    }
}

/** §4.3 分级 TTL 策略（毫秒） */
object CacheTTL {
    const val PRODUCT_INFO = 6L * 3600 * 1000        // 商品基础信息 6h
    const val LIVE_PRICE = 30L * 60 * 1000           // 实时价格 30min
    const val PRICE_HISTORY = 24L * 3600 * 1000      // 历史曲线 24h
    const val BILI_SEARCH = 2L * 3600 * 1000         // B站搜索 2h
    const val SMZDM_FEED = 1L * 3600 * 1000          // 值得买爆料 1h
    const val COUPON = 15L * 60 * 1000               // 优惠券 15min
    const val COMMENTS = 4L * 3600 * 1000            // 评论 4h
}
