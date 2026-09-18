package com.pricelens.worker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.pricelens.R
import com.pricelens.data.local.AppDatabase
import com.pricelens.data.local.entity.PriceTargetEntity
import com.pricelens.data.remote.JdApi
import com.pricelens.util.LogT
import com.pricelens.util.PriceFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 盯价检查共享执行器：PriceCheckWorker（WorkManager 兜底）与
 * WatchForegroundService（常驻通知栏）共用同一套查价 + 降价通知逻辑，
 * 避免两处实现漂移。
 */
@Singleton
class WatchCheckRunner @Inject constructor(
    private val db: AppDatabase,
    private val jdApi: JdApi
) {

    /**
     * @param total 活跃目标数；checked 成功查价数；triggered 触发降价通知数；
     * failedPlatformCount > 0 表示有平台整轮失败（Worker 据此走退避重试）
     */
    data class Outcome(
        val total: Int,
        val checked: Int,
        val triggered: Int,
        val failedPlatformCount: Int
    )

    suspend fun runOnce(context: Context): Outcome {
        val targets = db.priceTargetDao().getAllActive()
        if (targets.isEmpty()) return Outcome(0, 0, 0, 0)

        var checked = 0
        var triggered = 0
        var failed = 0
        for ((platform, group) in targets.groupBy { it.platform }) {
            val prices = fetchPlatformPrices(context, platform, group)
            if (prices == null) {
                failed++
                continue
            }
            for (target in group) {
                val current = prices[target.productId.removePrefix("$platform:")] ?: continue
                checked++
                if (current <= target.targetPrice) {
                    sendNotification(context, target, current)
                    triggered++
                }
            }
        }
        return Outcome(targets.size, checked, triggered, failed)
    }

    /** 按 platform 分发查价；null = 该平台本轮失败（应重试），空 Map = 暂无查价能力 */
    private suspend fun fetchPlatformPrices(context: Context, platform: String, targets: List<PriceTargetEntity>): Map<String, Double?>? =
        when (platform) {
            PLATFORM_JD -> runCatching {
                jdApi.getPrices(targets.map { it.productId.removePrefix("jd:") })
                    .mapValues { (_, price) -> price.first }
            }.onFailure { e ->
                LogT.w("盯价：京东查价失败：${e.javaClass.simpleName}")
            }.getOrNull()
            else -> {
                LogT.i("盯价：平台 $platform 暂无查价通道，跳过 ${targets.size} 个目标")
                emptyMap()
            }
        }

    private fun sendNotification(context: Context, target: PriceTargetEntity, current: Double) {
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_PRICE_ALERT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_price_drop_title, target.title))
            .setContentText(
                context.getString(
                    R.string.notification_price_drop_text,
                    PriceFormatter.formatRaw(current),
                    PriceFormatter.formatRaw(target.targetPrice)
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context)
            .notify(target.productId.hashCode(), notification)
    }

    companion object {
        private const val PLATFORM_JD = "jd"
        const val CHANNEL_PRICE_ALERT = "price_alert"
        const val CHANNEL_WATCH_STATUS = "watch_status"
    }
}
