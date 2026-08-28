package com.pricelens.data.cache

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pricelens.data.local.AppDatabase
import com.pricelens.data.repository.RevalidateHub
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * §4.7 三级清理：
 *  - LIGHT     应用启动时：轻量清理（删除明确过期条目）
 *  - FULL      WorkManager 每日凌晨：全面清理（LRU 淘汰 + 数据库 VACUUM）
 *  - EMERGENCY 存储压力响应：紧急清理（删除所有非收藏、非近 3 天缓存）
 */
@HiltWorker
class CacheCleanupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val db: AppDatabase,
    private val memoryCache: TLRUCache<String>,
    private val hub: RevalidateHub
) : CoroutineWorker(context, params) {

    enum class Mode { LIGHT, FULL, EMERGENCY }

    override suspend fun doWork(): Result {
        val now = System.currentTimeMillis()
        when (inputData.getString(KEY_MODE)?.let { Mode.valueOf(it) } ?: Mode.LIGHT) {
            Mode.LIGHT -> {
                memoryCache.clearExpired()
                db.productDao().deleteExpired(now)
                db.cacheEntryDao().deleteExpired(now)
                db.searchRecordDao().deleteOlderThan(now - 30L * 24 * 3600 * 1000)
            }
            Mode.FULL -> {
                memoryCache.clearExpired()
                hub.clearAll() // 同步清理重验证注册表，防无界堆积
                db.productDao().deleteExpired(now)
                db.cacheEntryDao().deleteExpired(now)
                // 陈旧快照保留上限 7 天（降级兜底用，防无界堆积）
                db.cacheEntryDao().deleteOlderThan(now - 7L * 24 * 3600 * 1000)
                db.priceHistoryDao().deleteOlderThan(thirtyDaysAgoDate())
                db.searchRecordDao().deleteOlderThan(now - 30L * 24 * 3600 * 1000)
                db.openHelper.writableDatabase.execSQL("VACUUM") // 压缩数据库文件
            }
            Mode.EMERGENCY -> {
                memoryCache.clear()
                hub.clearAll() // 内存全清时注册表一并清空
                db.productDao().deleteNonPinnedOlderThan(now - 3L * 24 * 3600 * 1000)
                db.priceHistoryDao().deleteOlderThan(thirtyDaysAgoDate())
                db.openHelper.writableDatabase.execSQL("VACUUM")
            }
        }
        return Result.success()
    }

    private fun thirtyDaysAgoDate(): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        return fmt.format(java.util.Date(System.currentTimeMillis() - 30L * 24 * 3600 * 1000))
    }

    companion object {
        const val KEY_MODE = "mode"

        /** 启动时的一次性轻量清理 */
        fun enqueueLight(context: Context) {
            WorkManager.getInstance(context).enqueue(
                androidx.work.OneTimeWorkRequestBuilder<CacheCleanupWorker>()
                    .setInputData(workDataOf(KEY_MODE to Mode.LIGHT.name))
                    .build()
            )
        }

        /** 每日凌晨的全面清理 */
        fun scheduleDaily(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "cache_cleanup_daily",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CacheCleanupWorker>(1, TimeUnit.DAYS)
                    .setInitialDelay(hoursUntil(3), TimeUnit.HOURS) // 凌晨 3 点
                    .setInputData(workDataOf(KEY_MODE to Mode.FULL.name))
                    .build()
            )
        }

        /** 存储压力紧急清理 */
        fun enqueueEmergency(context: Context) {
            WorkManager.getInstance(context).enqueue(
                androidx.work.OneTimeWorkRequestBuilder<CacheCleanupWorker>()
                    .setInputData(workDataOf(KEY_MODE to Mode.EMERGENCY.name))
                    .build()
            )
        }

        private fun hoursUntil(hour: Int): Long {
            val cal = java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.DAY_OF_YEAR, 1)
                set(java.util.Calendar.HOUR_OF_DAY, hour)
                set(java.util.Calendar.MINUTE, 0)
            }
            return ((cal.timeInMillis - System.currentTimeMillis()) / 3_600_000).coerceAtLeast(0)
        }
    }
}
