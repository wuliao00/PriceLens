package com.pricelens.di

import android.content.Context
import androidx.room.Room
import coil.ImageLoader
import com.pricelens.data.cache.TLRUCache
import com.pricelens.data.local.AppDatabase
import com.pricelens.data.local.RoomPenaltyStore
import com.pricelens.data.repository.RevalidateHub
import com.pricelens.util.RateLimiter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /** 应用级后台作用域：L1 过期重验证 / 熔断持久化写入 / 仓储层 singleflight */
    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 阶段3：显式 Migration，禁用破坏性回退（用户收藏/盯价目标不可丢）。
     *
     * 迁移清单只认 [AppDatabase.MIGRATIONS] 这一处 —— 曾经这里手写 `MIGRATION_1_2` 单项，
     * 新加的 2→3 没登记进来，真机升级直接崩在"required but not found"。
     * 见 `DatabaseMigrationWiringTest` 的守卫。
     */
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "pricelens.db")
            .addMigrations(*AppDatabase.MIGRATIONS.toTypedArray())
            .build()

    /** 免凭证曲线：OverlayCurveRecorder 只吃 DAO（JVM 可单测），DAO 从唯一建库点暴露 */
    @Provides
    fun provideWatchIdentityDao(db: AppDatabase): com.pricelens.data.local.dao.WatchIdentityDao = db.watchIdentityDao()

    @Provides
    fun providePriceHistoryDao(db: AppDatabase): com.pricelens.data.local.dao.PriceHistoryDao = db.priceHistoryDao()

    /** 阶段3：熔断状态持久化到 Room 小表，启动后经 withLimit 惰性恢复 */
    @Provides
    @Singleton
    fun provideRateLimiter(db: AppDatabase, applicationScope: CoroutineScope): RateLimiter = RateLimiter(
        penaltyStore = RoomPenaltyStore(db.domainPenaltyDao()),
        persistScope = applicationScope
    )

    /**
     * L1 内存缓存（与 Repository / 清理 Worker 共享同一单例）。
     * 阶段3 接线：过期读取 → [RevalidateHub] 按 key 执行注册的网络重验证，
     * 在应用级 IO 作用域异步执行（SupervisorJob 隔离单次刷新失败）。
     */
    @Provides
    @Singleton
    fun provideMemoryCache(hub: RevalidateHub, applicationScope: CoroutineScope): TLRUCache<String> = TLRUCache(
        maxSizeBytes = 8L * 1024 * 1024,
        defaultTtlMs = 30L * 60 * 1000,
        onStale = { key -> hub.revalidate(key) },
        revalidateScope = applicationScope
    )

    /** OkHttp 磁盘缓存目录（§4.6：5MB 预算） */
    @Provides
    @Singleton
    fun provideHttpCacheDir(@ApplicationContext context: Context): File = File(context.cacheDir, "okhttp").apply { mkdirs() }

    /** §4.4 Coil 瘦身：内存 10% + 磁盘 15MB（降采样在请求侧 size(300) + RGB_565） */
    @Provides
    @Singleton
    fun provideImageLoader(@ApplicationContext context: Context): ImageLoader = ImageLoader.Builder(context)
        .memoryCache {
            coil.memory.MemoryCache.Builder(context)
                .maxSizePercent(0.10)
                .build()
        }
        .diskCache {
            coil.disk.DiskCache.Builder()
                .directory(File(context.cacheDir, "img"))
                .maxSizeBytes(15L * 1024 * 1024)
                .build()
        }
        .crossfade(true)
        .build()
}
