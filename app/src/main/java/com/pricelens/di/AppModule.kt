package com.pricelens.di

import android.content.Context
import androidx.room.Room
import coil.ImageLoader
import com.pricelens.data.cache.TLRUCache
import com.pricelens.data.local.AppDatabase
import com.pricelens.util.RateLimiter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "pricelens.db")
            .build()

    @Provides
    @Singleton
    fun provideRateLimiter(): RateLimiter = RateLimiter()

    /** L1 内存缓存（与 Repository 中实例一致，供清理 Worker 使用） */
    @Provides
    @Singleton
    fun provideMemoryCache(): TLRUCache<String> = TLRUCache(
        maxSizeBytes = 8L * 1024 * 1024,
        defaultTtlMs = 30L * 60 * 1000
    )

    /** OkHttp 磁盘缓存目录（§4.6：5MB 预算） */
    @Provides
    @Singleton
    fun provideHttpCacheDir(@ApplicationContext context: Context): File =
        File(context.cacheDir, "okhttp").apply { mkdirs() }

    /** §4.4 Coil 瘦身：内存 10% + 磁盘 15MB（降采样在请求侧 size(300) + RGB_565） */
    @Provides
    @Singleton
    fun provideImageLoader(@ApplicationContext context: Context): ImageLoader =
        ImageLoader.Builder(context)
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
