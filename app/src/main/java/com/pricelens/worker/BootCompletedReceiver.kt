package com.pricelens.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pricelens.data.cache.CacheCleanupWorker

/** 开机自启动：恢复后台盯价与缓存清理任务（WorkManager 自身可跨重启，这里兜底） */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            PriceCheckWorker.schedule(context)
            CacheCleanupWorker.scheduleDaily(context)
        }
    }
}
