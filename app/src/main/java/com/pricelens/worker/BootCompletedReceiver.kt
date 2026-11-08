package com.pricelens.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pricelens.data.cache.CacheCleanupWorker
import com.pricelens.data.repository.SettingsRepository

/**
 * 开机自启动：恢复后台盯价、自动追价链、价保检查与缓存清理任务
 * （WorkManager 自身可跨重启，这里兜底；全部是幂等登记：KEEP / 取消不存在的东西都安全）。
 *
 * 2026-11 自动追价：
 *  - [AutoTrackScheduler.ensureChain] 按用户开关与间隔档位补登记唯一链 `auto_track_chain`
 *    （关闭自动追价时它会取消链并清掉"下一轮预计时刻"）；
 *  - [PriceProtectionWorker.schedule] 补登记 6 小时一次的价保检查（KEEP，不重复排）。
 *
 * 这里不注入 Hilt（广播接收器在开机后被拉起，构造 [SettingsRepository] 读取的是同一份
 * SharedPreferences 与 Keystore 密文，行为与单例一致；也避免给 Manifest 登记带来额外要求）。
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            PriceCheckWorker.schedule(context)
            CacheCleanupWorker.scheduleDaily(context)
            val settings = SettingsRepository(context.applicationContext)
            AutoTrackScheduler.ensureChain(context, settings)
            PriceProtectionWorker.schedule(context)
        }
    }
}
