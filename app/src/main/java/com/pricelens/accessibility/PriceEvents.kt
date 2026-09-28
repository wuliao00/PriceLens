package com.pricelens.accessibility

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * 无障碍服务 → 应用内 UI / 浮窗的事件总线。
 * 替代文档中已废弃的 LocalBroadcastManager：SharedFlow 单进程内更轻、无广播开销。
 */
object PriceEvents {

    /**
     * 一次商详页识别结果（A2 扩容，供浮窗与概览对齐口径）：
     *  - [itemId]/[basis]：确定性商品 ID 与识别依据 —— 只有 basis=ITEM_ID 才允许显示历史价/多平台比价，
     *    TITLE_ONLY 一律降级为"识别到标题 · 点击在 App 内搜索"（防错配硬规则）；
     *  - [priceBasis]：价格口径（页面价/券后价/到手价），裸数字不再冒充到手价；
     *  - [sourceText]：人读来源（"本机京东账号"等），数据时间与来源是浮窗必须项。
     */
    data class Detected(
        val price: Double,
        val rawPriceText: String,
        val title: String?,
        val packageName: String,
        val platform: ShopPlatform = ShopPlatform.UNKNOWN,
        val priceBasis: PriceBasis = PriceBasis.PAGE,
        val itemId: String? = null,
        val sourceText: String? = null
    ) {
        /** 识别依据：拿到确定性 ID 才算 ITEM_ID，否则 TITLE_ONLY */
        val basis: DetectionBasis
            get() = if (itemId != null) DetectionBasis.ITEM_ID else DetectionBasis.TITLE_ONLY

        /** 同一次识别的去重签名（SearchViewModel 节流与浮窗 bundle 回填校验共用） */
        val signature: String
            get() = "$packageName|${itemId ?: title ?: ""}|$rawPriceText"
    }

    private val _detections = MutableSharedFlow<Detected>(extraBufferCapacity = 4)
    val detections: SharedFlow<Detected> = _detections

    fun emit(event: Detected) {
        _detections.tryEmit(event)
    }
}
