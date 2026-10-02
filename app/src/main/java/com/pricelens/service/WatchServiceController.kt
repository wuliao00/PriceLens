package com.pricelens.service

import android.content.Context
import com.pricelens.data.local.AppDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
            // A4：Room 的表级失效意味着新增/停用/改名任何一个目标都会重发 emission，
            // 逐条 emission 调 start() 会让服务侧攒出多条并行检查循环。
            // 服务只需要知道「有没有目标」，所以先压成布尔再只跟跳变。
            db.priceTargetDao().observeActive()
                .map { it.isEmpty() }
                .distinctUntilChanged()
                .collect { empty ->
                    if (empty) {
                        WatchForegroundService.stop(context)
                    } else {
                        WatchForegroundService.start(context)
                    }
                }
        }
    }
}
