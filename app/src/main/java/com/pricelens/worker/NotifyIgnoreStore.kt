package com.pricelens.worker

import android.content.Context
import org.json.JSONObject

/**
 * 「忽略」降价通知的记忆（通知上的动作按钮）：
 * 同一商品在**价格没有更低之前**不再提醒；价格创新低时自动解除。
 *
 * 纯核心 [NotifyIgnoreCore]（Map + JSON，编解码失败一律按"没有忽略记录"处理，
 * 绝不因坏数据把通知永久掐死）+ SharedPreferences 薄胶水。
 */
object NotifyIgnoreCore {

    /** 命中记忆且价格未更低 → 抑制；价格更低 → 放行（由 [afterNotify] 清除记忆） */
    fun suppress(ignored: Map<String, Double>, productId: String, price: Double): Boolean {
        val stored = ignored[productId] ?: return false
        return price >= stored
    }

    /** 放行前的清理：价格比"忽略时的价"更低 → 记忆失效，删掉它 */
    fun afterNotify(ignored: Map<String, Double>, productId: String, price: Double): Map<String, Double> {
        val stored = ignored[productId] ?: return ignored
        return if (price < stored) ignored - productId else ignored
    }

    /** 用户点「忽略」：记下当前价 */
    fun afterIgnore(ignored: Map<String, Double>, productId: String, price: Double): Map<String, Double> = ignored + (productId to price)

    fun decode(raw: String?): Map<String, Double> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            buildMap {
                for (key in obj.keys()) {
                    val value = obj.optDouble(key, Double.NaN)
                    if (value.isFinite() && value > 0) put(key, value)
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun encode(ignored: Map<String, Double>): String {
        val obj = JSONObject()
        for ((key, value) in ignored) obj.put(key, value)
        return obj.toString()
    }
}

/** 落盘：单键 JSON，读写都是小地图，主线程外的调用方（通知路径）用 */
class NotifyIgnoreStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun suppress(productId: String, price: Double): Boolean = NotifyIgnoreCore.suppress(read(), productId, price)

    fun clearIfPriceLower(productId: String, price: Double) {
        val current = read()
        val updated = NotifyIgnoreCore.afterNotify(current, productId, price)
        if (updated != current) write(updated)
    }

    fun ignore(productId: String, price: Double) {
        write(NotifyIgnoreCore.afterIgnore(read(), productId, price))
    }

    private fun read(): Map<String, Double> = NotifyIgnoreCore.decode(prefs.getString(KEY_IGNORED, null))

    private fun write(map: Map<String, Double>) {
        prefs.edit().putString(KEY_IGNORED, NotifyIgnoreCore.encode(map)).apply()
    }

    companion object {
        private const val PREFS_NAME = "watch_notify"
        private const val KEY_IGNORED = "ignored_prices"
    }
}
