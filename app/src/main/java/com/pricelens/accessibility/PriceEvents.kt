package com.pricelens.accessibility

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * 无障碍服务 → 应用内 UI / 浮窗的事件总线。
 * 替代文档中已废弃的 LocalBroadcastManager：SharedFlow 单进程内更轻、无广播开销。
 */
object PriceEvents {

    data class Detected(
        val price: Double,
        val rawPriceText: String,
        val title: String?,
        val packageName: String
    )

    private val _detections = MutableSharedFlow<Detected>(extraBufferCapacity = 4)
    val detections: SharedFlow<Detected> = _detections

    fun emit(event: Detected) {
        _detections.tryEmit(event)
    }
}
