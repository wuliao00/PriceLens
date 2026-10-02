package com.pricelens.data.remote

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 慢慢买**桌面版历史页**（`tool.manmanbuy.com/HistoryLowest.aspx`）里那条价格序列的读与解析。
 *
 * 数据形状（2026-10-02 读站点自己的 `Scripts/echartsTrend.js` 得到，不是猜的）：
 * 页面把服务端给的序列交给 `flotChart.chart(strDate)`，站点脚本自己把它变成数组，
 * 逐条 `oldData[i][0]`（毫秒时间戳）/ `oldData[i][1]`（价格，`<=0` 的跳过）
 * 推成全局的 `flotChart.data = [[ts, price, extra], …]`。
 *
 * 所以 WebView 里只要读全局的 `flotChart.data` —— 这是页面自己渲染用的那份数据。
 * 我们不解析它一页一签的 `ticket`，也不碰它混淆过的签名脚本（那属于逆向站点反爬，不做）；
 * 本文件只做**结构化解析**（[org.json]），不对页面内容做任何求值或注入。
 */
object MmbPageSeries {

    /** 与网页 `new Date(parseInt(nS))` 对齐：毫秒时间戳 → 本地时区的 `yyyy-MM-dd` */
    private val DAY_FORMAT: ThreadLocal<SimpleDateFormat> =
        ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd", Locale.US) }

    /** WebView 里执行：返回 `{"s": <序列或 null>, "t": <可见文本前 4000 字>}` 的 JSON 字符串。纯读，不改页面。 */
    const val READ_PROBE_JS: String = """
(function () {
  try {
    var f = window.flotChart;
    var d = null;
    if (f) {
      if (f.data && f.data.length) d = f.data;
      else if (f.oldData && f.oldData.length) d = f.oldData;
    }
    var t = (document.body && document.body.innerText) ? document.body.innerText.slice(0, 4000) : '';
    return JSON.stringify({ s: d, t: t });
  } catch (e) {
    return JSON.stringify({ s: null, t: '' });
  }
})()
"""

    /** 页面探针的解析结果：序列 + 一小段可见文本（用于识别"正在抓取/要求授权"这类页面状态） */
    data class PageProbe(val series: List<ManmanbuyApi.PricePoint>, val visibleText: String)

    /** 探针 JSON → [PageProbe]。任何异常都退化成空探针，绝不抛。 */
    fun parsePageProbe(json: String?): PageProbe {
        if (json.isNullOrBlank()) return PageProbe(emptyList(), "")
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return PageProbe(emptyList(), "")
        val text = obj.optString("t")
        val seriesJson = obj.opt("s")
        val series = when (seriesJson) {
            is JSONArray -> parseFlotArray(seriesJson)
            // JS 侧 `JSON.stringify({s: d})` 若 d 是字符串化过的，这里再兜一层
            is String -> parseFlotData(seriesJson)
            else -> emptyList()
        }
        return PageProbe(series, text)
    }

    /**
     * `flotChart.data` 的 JSON（`[[ts, price, extra?], …]`）→ 价格点。
     *
     * 宽容三种形态：`[ts, price]` / `[ts, price, extra]` / `{"value":[ts, price]}`；
     * 其余一律跳过。价格 `<= 0` 跳过（页面上也是这么过滤的），时间戳解析不出也跳过。
     * 整体不是 JSON / 不是数组时返回空表——**宁可不给点，也不给错点**。
     */
    fun parseFlotData(json: String?): List<ManmanbuyApi.PricePoint> {
        if (json.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return parseFlotArray(array)
    }

    private fun parseFlotArray(array: JSONArray): List<ManmanbuyApi.PricePoint> {
        val result = ArrayList<ManmanbuyApi.PricePoint>(array.length())
        for (i in 0 until array.length()) {
            val row = array.optJSONArray(i) ?: array.optJSONObject(i)?.optJSONArray("value") ?: continue
            val ts = row.optLong(0, 0L)
            val price = row.optDouble(1, 0.0)
            if (ts <= 0L || price <= 0.0) continue
            result += ManmanbuyApi.PricePoint(DAY_FORMAT.get()!!.format(Date(ts)), price)
        }
        return result
    }

    /** 按天去重（同一天保留靠后的观测）——与 `ManmanbuyApi.finalize` 同一套语义 */
    fun collapseByDay(points: List<ManmanbuyApi.PricePoint>): List<ManmanbuyApi.PricePoint> {
        val out = mutableListOf<ManmanbuyApi.PricePoint>()
        for (p in points.sortedBy { it.date }) {
            if (out.isEmpty() || out.last().date != p.date) out += p else out[out.size - 1] = p
        }
        return out
    }

    /** 页面是否停在「正在抓取」态（站点自己的文案） */
    fun pageLooksCrawling(visibleText: String?): Boolean =
        visibleText?.contains("正在抓取") == true || visibleText?.contains("抓取中") == true

    /** 页面是否停在「京东授权」引导（站点自己的弹窗） */
    fun pageWantsJdAuth(visibleText: String?): Boolean =
        visibleText?.contains("京东授权") == true
}
