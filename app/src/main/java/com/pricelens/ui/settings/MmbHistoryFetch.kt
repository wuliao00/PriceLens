package com.pricelens.ui.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「用慢慢买网页取历史价」的结果桥：Activity 写、设置页读（同一进程内）。
 *
 * 为什么不走 `startActivityForResult`：取数是**异步且可能很长**的（页面自己要先验授权、
 * 再抓取），用 Activity 结果码表达不了"过程里发生了什么"；而设置页只需要知道
 * "上一次取数成不成、取到几个点"，一个进程内单例状态流最省事，也便于把结论直接显示在
 * 设置页（用户从网页回来就能看到）。
 */
object MmbHistoryFetch {

    data class Outcome(
        val productId: String,
        val ok: Boolean,
        val points: Int = 0,
        val lowest: Double = 0.0,
        val highest: Double = 0.0
    )

    private val _last = MutableStateFlow<Outcome?>(null)
    val last: StateFlow<Outcome?> = _last.asStateFlow()

    /** 当前正在取的商品（用于 UI 显示"正在取 xxx 的历史价"） */
    private val _running = MutableStateFlow<String?>(null)
    val running: StateFlow<String?> = _running.asStateFlow()

    fun begin(productId: String) {
        _running.value = productId
    }

    fun succeed(productId: String, points: Int, lowest: Double, highest: Double) {
        _running.value = null
        _last.value = Outcome(productId, ok = true, points = points, lowest = lowest, highest = highest)
    }

    fun fail(productId: String) {
        _running.value = null
        _last.value = Outcome(productId, ok = false)
    }
}
