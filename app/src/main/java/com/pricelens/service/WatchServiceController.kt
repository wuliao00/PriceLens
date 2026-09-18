package com.pricelens.service

import android.content.Context
import com.pricelens.data.local.AppDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 盯价常驻服务开关：观察 price_targets 活跃集合，
 * 有目标 → 拉起 WatchForegroundService；清空 → 停止。
 * 在 Application.onCreate 绑定后，设置/删除目标无需任何 UI 侧手动接线。
 */
@Singleton
class WatchServiceController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase
) {
    fun bind(scope: CoroutineScope) {
        scope.launch {
            db.priceTargetDao().observeActive().collect { targets ->
                if (targets.isEmpty()) {
                    WatchForegroundService.stop(context)
                } else {
                    WatchForegroundService.start(context)
                }
            }
        }
    }
}
