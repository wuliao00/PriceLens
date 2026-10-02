package com.pricelens.widget

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.pricelens.R
import com.pricelens.util.LogT
import com.pricelens.worker.WatchCheckRunner
import com.pricelens.worker.WidgetStateStore
import com.pricelens.worker.WidgetStats
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * QS 磁贴「立即检查」（用户优化文档 §七）：点击触发的就是盯价页「立即检查一次」同一条路径 ——
 * [com.pricelens.ui.price.PriceWatchViewModel.checkNow] 的底层入口即单例
 * [WatchCheckRunner.runOnce]，这里注入同一个单例从 Service 环境调用，不实例化 ViewModel。
 *
 * subtitle 与桌面小组件读同一份持久快照（SharedPreferences "watch_state"）：
 * 打开面板（onStartListening）与一轮检查完成后都刷新为「上次 HH:mm」（从未检查显示占位符）。
 */
@AndroidEntryPoint
class CheckTileService : TileService() {

    @Inject lateinit var runner: WatchCheckRunner

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 忙态闸门：与 ViewModel.checkNow 的 `_checking` 同语义，同一时刻只跑一轮 */
    private var checking = false

    override fun onStartListening() {
        super.onStartListening()
        if (!checking) render(active = false)
    }

    override fun onClick() {
        super.onClick()
        if (checking) return
        checking = true
        render(active = true)
        scope.launch {
            // 失败只记日志：磁贴如实回到空闲态并显示上一次成功的检查时间
            runCatching { runner.runOnce(this@CheckTileService) }
                .onFailure { LogT.w("磁贴立即检查失败：${it.javaClass.simpleName}") }
            // 状态回到主线程再改：onClick/onStartListening 都在主线程，checking 全程同线程读写
            withContext(Dispatchers.Main) {
                checking = false
                render(active = false)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** 忙态显示「检查中…」，空闲显示「上次 HH:mm」；label 固定「立即检查」 */
    private fun render(active: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.watch_tile_label)
        // subtitle 是 API 29 才有的字段：minSdk 26~28 上不设置，避免 NoSuchMethodError
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (active) {
                getString(R.string.watch_tile_checking)
            } else {
                getString(R.string.watch_tile_subtitle, WidgetStats.clockText(WidgetStateStore(this).snapshot().lastCheckAt))
            }
        }
        tile.updateTile()
    }
}
