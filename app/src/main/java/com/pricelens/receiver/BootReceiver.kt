package com.pricelens.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pricelens.data.cache.CacheCleanupWorker
import com.pricelens.worker.PriceCheckWorker

/**
 * 后台自启动：开机/更新后重新登记后台任务
 *  - 每 30 分钟盯价
 *  - 每日凌晨缓存清理
 * （部分国产 ROM 需在系统设置里允许本应用"自启动"，此处已尽应用层最大努力）
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                PriceCheckWorker.schedule(context)
                CacheCleanupWorker.scheduleDaily(context)
            }
        }
    }
}
