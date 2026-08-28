package com.pricelens.worker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pricelens.R
import com.pricelens.data.local.AppDatabase
import com.pricelens.data.local.entity.PriceTargetEntity
import com.pricelens.data.remote.JdApi
import com.pricelens.util.LogT
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * §8 后台盯价：每 30 分钟检查一次用户设定的目标价，
 * 达标 → 高优先级通知。网络可用 + 电量不低时才执行。
 *
 * 阶段5 通用化：按 [PriceTargetEntity.platform] 分发查价——
 *  - jd：p.3.cn 批量查价（既有路径）
 *  - 其他平台：暂无对应 repository 查价通道时记录日志并跳过；
 *    后续接入新平台只需在 [fetchPlatformPrices] 增加一个分支。
 * 查价抛异常 → 返回 [Result.retry]，WorkManager 按 schedule() 配置的
 * 退避策略（10 分钟线性）重新调度，不影响 30 分钟周期本身。
 */
@HiltWorker
class PriceCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val db: AppDatabase,
    private val jdApi: JdApi
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val targets = db.priceTargetDao().getAllActive()
        if (targets.isEmpty()) return Result.success()

        var needRetry = false
        for ((platform, group) in targets.groupBy { it.platform }) {
            val prices = fetchPlatformPrices(platform, group)
            if (prices == null) { // 该平台本轮失败 → 整轮走 WorkManager 退避重试
                needRetry = true
                continue
            }
            for (target in group) {
                val current = prices[target.productId.removePrefix("$platform:")] ?: continue
                if (current <= target.targetPrice) {
                    sendNotification(target.productId, target.title, current, target.targetPrice)
                }
            }
        }
        return if (needRetry) Result.retry() else Result.success()
    }

    /**
     * 按 platform 分发查价。
     * 返回 null = 本轮失败（应重试）；空 Map = 暂无查价能力或本轮无结果（直接跳过）。
     */
    private suspend fun fetchPlatformPrices(platform: String, targets: List<PriceTargetEntity>): Map<String, Double?>? = when (platform) {
        PLATFORM_JD -> runCatching {
            jdApi.getPrices(targets.map { it.productId.removePrefix("jd:") })
                .mapValues { (_, price) -> price.first }
        }.onFailure { e ->
            LogT.w("盯价：京东查价失败，交由 WorkManager 退避重试：${e.javaClass.simpleName}")
        }.getOrNull()
        else -> {
            LogT.i("盯价：平台 $platform 暂无查价通道，跳过 ${targets.size} 个目标")
            emptyMap()
        }
    }

    private fun sendNotification(productId: String, title: String, currentPrice: Double, targetPrice: Double) {
        if (ContextCompat.checkSelfPermission(
                applicationContext, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notification = NotificationCompat.Builder(applicationContext, "price_alert")
            .setSmallIcon(R.drawable.ic_notification)
            // 通知文案资源化（评审修复）：不再硬编码拼接
            .setContentTitle(applicationContext.getString(R.string.notification_price_drop_title, title))
            .setContentText(
                applicationContext.getString(
                    R.string.notification_price_drop_text,
                    com.pricelens.util.PriceFormatter.formatRaw(currentPrice),
                    com.pricelens.util.PriceFormatter.formatRaw(targetPrice)
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(applicationContext)
            .notify(productId.hashCode(), notification)
    }

    companion object {
        private const val PLATFORM_JD = "jd"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PriceCheckWorker>(30, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                // 失败重试退避：线性 10 分钟递增，避免网络抖动时密集重跑
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "price_check",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
