package com.pricelens.keepalive

import java.util.Locale

/**
 * 国产 ROM 识别（文档 §12.1 的纯函数版）。
 *
 * 只吃 Build.MANUFACTURER / Build.BRAND 两个字符串，不碰 Context，也不反射
 * SystemProperties —— 识别结果只用于决定「给用户看哪一段步骤表、跳转先试哪几个组件」，
 * 认错不会有破坏性后果，因此不值得引入读取系统属性那套脏手法；纯函数还能直接在 JVM 上对表。
 *
 * 子品牌归属（与需求口径一致）：
 *  - Redmi / POCO → 小米；
 *  - 荣耀（HONOR）→ 华为一栏；
 *  - 一加（OnePlus）/ realme → OPPO（ColorOS 系）；
 *  - iQOO → vivo；
 * 认不出的品牌一律 [Rom.OTHER]，不许猜成某一个国产品牌。
 */
enum class Rom(val label: String) {
    XIAOMI("小米"),
    HUAWEI("华为（含荣耀）"),
    OPPO("OPPO（含一加/realme）"),
    VIVO("vivo（含 iQOO）"),
    OTHER("其它品牌")
}

object RomDetector {

    private val XIAOMI_KEYS = listOf("xiaomi", "redmi", "poco")
    private val HUAWEI_KEYS = listOf("huawei", "honor")
    private val OPPO_KEYS = listOf("oppo", "oneplus", "realme", "oplus")
    private val VIVO_KEYS = listOf("vivo", "iqoo")

    /** manufacturer / brand 任一为 null、空串或空白都不会崩，按「认不出」处理 */
    fun detect(manufacturer: String?, brand: String?): Rom {
        val haystack = listOfNotNull(manufacturer, brand)
            .joinToString(" ")
            .lowercase(Locale.US)
        return when {
            XIAOMI_KEYS.any { haystack.contains(it) } -> Rom.XIAOMI
            HUAWEI_KEYS.any { haystack.contains(it) } -> Rom.HUAWEI
            OPPO_KEYS.any { haystack.contains(it) } -> Rom.OPPO
            VIVO_KEYS.any { haystack.contains(it) } -> Rom.VIVO
            else -> Rom.OTHER
        }
    }
}
