package com.pricelens

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.Coil
import coil.ImageLoader
import com.pricelens.data.cache.CacheCleanupWorker
import com.pricelens.worker.PriceCheckWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PriceLensApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var imageLoader: ImageLoader

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Shizuku 状态响应式监听（Binder 启动/停止/授权自动流转）
        com.pricelens.util.ShizukuHelper.init(this)
        // Coil 全局单例（§4.4：内存 10% / 磁盘 15MB）
        Coil.setImageLoader(imageLoader)

        createNotificationChannel()
        // §4.7 启动轻量清理 + 每日全面清理
        CacheCleanupWorker.enqueueLight(this)
        CacheCleanupWorker.scheduleDaily(this)
        // §8 后台盯价：每 30 分钟
        PriceCheckWorker.schedule(this)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "price_alert",
            getString(R.string.price_alert_channel),
            NotificationManager.IMPORTANCE_HIGH
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
