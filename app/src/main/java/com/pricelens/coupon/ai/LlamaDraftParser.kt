package com.pricelens.coupon.ai

import org.json.JSONObject

/**
 * 模型输出的一份草稿（解析后的中立结构）。
 *
 * 为什么先解析成结构再进复核：模型的输出是**文本**，而复核是**对着原文比对数字**。
 * 中间隔一个显式结构，才能让"模型说了什么"与"原文支持什么"这两件事在代码里分得开、也便于拿真机
 * 输出当夹具做回归（`LlamaDraftParserTest` 里的三条 `json=` 就是从 PLB110 上原样抄下来的）。
 */
data class LlamaDraft(val platform: String?, val discount: Double?, val threshold: Double?, val confidence: Double?)

/**
 * 解析模型按 GBNF 吐出来的 JSON。**宽容但不糊弄**：
 *  - 缺字段/字段类型不对 → 那一段就是 null（不猜、不默认 0）；
 *  - 整段不是 JSON → 返回 null（= "模型没参与"），由调用方沿用规则结果；
 *  - `coupons` 只取**第一张**：本版模型每次只处理一小段文案，多张券由分句之后逐句调用来覆盖，
 *    把它强行揉进一条草稿反而会丢掉"哪一句说了哪张券"。
 */
object LlamaDraftParser {

    fun parse(raw: String?): LlamaDraft? {
        if (raw.isNullOrBlank()) return null
        val obj = runCatching { JSONObject(raw.trim()) }.getOrNull() ?: return null
        val first = obj.optJSONArray("coupons")?.optJSONObject(0)
        return LlamaDraft(
            platform = obj.optString("platform").takeIf { it.isNotBlank() && it != "null" },
            discount = first?.optDoubleOrNull("discount"),
            threshold = first?.optDoubleOrNull("threshold"),
            confidence = obj.optDoubleOrNull("confidence")
        )
    }

    /** `optDouble` 会把缺失字段读成 NaN、把 `null` 读成 0 —— 两者都得当成"没给" */
    private fun JSONObject.optDoubleOrNull(name: String): Double? {
        if (!has(name) || isNull(name)) return null
        val value = optDouble(name, Double.NaN)
        return value.takeIf { !it.isNaN() }
    }
}
