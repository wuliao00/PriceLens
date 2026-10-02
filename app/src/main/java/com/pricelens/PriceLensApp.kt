package com.pricelens

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
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

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // 崩溃日志（文档 §十三·本地版，2026-10-02 拍板不上传）：尽早安装，
        // 连 Application 初始化自己崩了也能留下现场。不联网、不写 Cookie/header。
        com.pricelens.util.CrashHandler.install(base)
    }

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var imageLoader: ImageLoader

    @Inject lateinit var watchServiceController: com.pricelens.service.WatchServiceController

    @Inject lateinit var ruleSyncRepository: com.pricelens.rules.RuleSyncRepository

    private val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main
    )

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // 凭证加密（2026-10-02）：把历史明文 Cookie/apikey 搬进 Keystore 加密存储并删掉明文。
        // 幂等，放在最前面 —— 任何读设置的代码之前都该是密文。
        com.pricelens.util.SecretStore.migrateFromPlainPrefs(this)
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
        // 选择器规则（v2.9.0）：先本地装载（磁盘已校验 > 内置 assets，只读几 KB），
        // 再让 Worker 做远端同步（规则没装载前判定管线自动走纯启发式，行为同改造前）
        runCatching { ruleSyncRepository.loadAndInstallLocal() }
            .onFailure { com.pricelens.util.LogT.e("RULES 本地规则装载失败（回退启发式）", it) }
        com.pricelens.worker.RuleSyncWorker.enqueueNow(this)
        com.pricelens.worker.RuleSyncWorker.schedule(this)
        // 有盯价目标时拉起前台服务（通知栏常驻 + 30 分钟循环检查）
        watchServiceController.bind(appScope)
    }

    private fun createNotificationChannel() {
        val alert = NotificationChannel(
            "price_alert",
            getString(R.string.price_alert_channel),
            NotificationManager.IMPORTANCE_HIGH
        )
        // 盯价常驻：低优先级、不发声、不可滑动移除（FGS 通知）
        val status = NotificationChannel(
            "watch_status",
            getString(R.string.watch_status_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(alert)
        manager.createNotificationChannel(status)
    }
}
