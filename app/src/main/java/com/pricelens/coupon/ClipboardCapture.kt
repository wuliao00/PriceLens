package com.pricelens.coupon

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 剪贴板原文的单槽交接（#63）：一次读取，同时喂「链接解析」与「找券」两条链。
 *
 * 旧形状是 `ClipboardDetector.detect()` 里的一句 `if (parsed != null)` ——
 * 只有认出商品链接才把结果交出去，于是"京东这个好好用，领券满199减50"这种**没有链接的分享文本**
 * 永远进不了找券链；而找券段自己去读剪贴板又会造成第二次读取
 * （重复读剪贴板正是系统与厂商标记骚扰的行为，也是这个开关的合规前提）。
 * 所以：读一次 ⇒ 原文进这里，链接结果照常回给横幅。
 *
 * 只存内存、不落库、不进日志：剪贴板里可能是口令、Cookie 或私人聊天，
 * 与 [com.pricelens.coupon.MisreadExport] 那条"导出前逐字段脱敏"是同一条顾虑。
 * 新鲜度不在这里判（由 `LocalCouponInputPlanner` 统一判），这里只保证"最新那一条"。
 */
object ClipboardCapture {

    data class Reading(val raw: String, val capturedAtElapsedMs: Long)

    private val _latest = MutableStateFlow<Reading?>(null)
    val latest: StateFlow<Reading?> = _latest

    /** 空白文本不进槽：留着上一次的真实内容比塞一条空记录更有用（也更好解释） */
    fun publish(raw: String, atElapsedMs: Long) {
        if (raw.isBlank()) return
        _latest.value = Reading(raw = raw.trim(), capturedAtElapsedMs = atElapsedMs)
    }

    fun clear() {
        _latest.value = null
    }
}
