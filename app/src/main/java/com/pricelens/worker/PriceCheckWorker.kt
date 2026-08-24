package com.pricelens.worker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.Constraints
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pricelens.R
import com.pricelens.data.local.AppDatabase
import com.pricelens.data.remote.JdApi
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * §8 后台盯价：每 30 分钟检查一次用户设定的目标价，
 * 达标 → 高优先级通知。网络可用 + 电量不低时才执行。
 */
@HiltWorker
class PriceCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val db: AppDatabase,
    private val jdApi: JdApi,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val targets = db.priceTargetDao().getAllActive()
        if (targets.isEmpty()) return Result.success()

        val jdIds = targets.filter { it.platform == "jd" }.map { it.productId.removePrefix("jd:") }
        val prices = if (jdIds.isNotEmpty()) jdApi.getPrices(jdIds) else emptyMap()

        for (target in targets) {
            val current = prices[target.productId.removePrefix("jd:")]?.first ?: continue
            if (current <= target.targetPrice) {
                sendNotification(target.productId, target.title, current, target.targetPrice)
            }
        }
        return Result.success()
    }

    private fun sendNotification(
        productId: String,
        title: String,
        currentPrice: Double,
        targetPrice: Double
    ) {
        if (ContextCompat.checkSelfPermission(
                applicationContext, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val notification = NotificationCompat.Builder(applicationContext, "price_alert")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$title 降价了！")
            .setContentText(
                "当前 ¥${com.pricelens.util.PriceFormatter.formatRaw(currentPrice)} ≤ 目标 ¥" +
                    com.pricelens.util.PriceFormatter.formatRaw(targetPrice)
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(applicationContext)
            .notify(productId.hashCode(), notification)
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PriceCheckWorker>(30, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "price_check", ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }
}
