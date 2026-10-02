package com.pricelens.keepalive

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ROM 识别（文档 §12.1 的纯函数版）：只吃 Build.MANUFACTURER / BRAND 两个字符串，
 * 不碰 Context，也不反射 SystemProperties，所以可以在 JVM 上把各品牌逐一对表。
 *
 * 锁的红线：
 *  - 子品牌必须归到对应母品牌（Redmi/POCO → 小米、荣耀 → 华为、一加/realme → OPPO、iQOO → vivo）；
 *  - 认不出来的一律「其它」，不许猜成某一个国产品牌；
 *  - 输入为 null/空/空白都不崩。
 */
class RomDetectorTest {

    @Test
    fun `xiaomi family maps to xiaomi`() {
        assertEquals(Rom.XIAOMI, RomDetector.detect("Xiaomi", "Xiaomi"))
        assertEquals(Rom.XIAOMI, RomDetector.detect("Xiaomi", "Redmi"))
        assertEquals(Rom.XIAOMI, RomDetector.detect("Xiaomi", "POCO"))
    }

    @Test
    fun `huawei and honor both map to huawei`() {
        assertEquals(Rom.HUAWEI, RomDetector.detect("HUAWEI", "HUAWEI"))
        assertEquals(Rom.HUAWEI, RomDetector.detect("HONOR", "HONOR"))
        // 华为子系列由 manufacturer 兜住（brand 只写 nova 也不能漏）
        assertEquals(Rom.HUAWEI, RomDetector.detect("Huawei", "nova"))
    }

    @Test
    fun `oppo family includes oneplus and realme`() {
        assertEquals(Rom.OPPO, RomDetector.detect("OPPO", "OPPO"))
        assertEquals(Rom.OPPO, RomDetector.detect("OnePlus", "OnePlus"))
        assertEquals(Rom.OPPO, RomDetector.detect("realme", "realme"))
        assertEquals(Rom.OPPO, RomDetector.detect("oplus", "oplus"))
    }

    @Test
    fun `vivo family includes iqoo`() {
        assertEquals(Rom.VIVO, RomDetector.detect("vivo", "vivo"))
        assertEquals(Rom.VIVO, RomDetector.detect("vivo", "iQOO"))
        assertEquals(Rom.VIVO, RomDetector.detect("vivo", "Iqoo"))
    }

    @Test
    fun `unknown manufacturers fall to other instead of guessing`() {
        assertEquals(Rom.OTHER, RomDetector.detect("samsung", "SM-G9910"))
        assertEquals(Rom.OTHER, RomDetector.detect("Google", "Pixel 6"))
        assertEquals(Rom.OTHER, RomDetector.detect("Sony", "XQ-BC72"))
        assertEquals(Rom.OTHER, RomDetector.detect("Micromax", "Micromax A1"))
    }

    @Test
    fun `null blank and whitespace inputs never crash and fall to other`() {
        assertEquals(Rom.OTHER, RomDetector.detect(null, null))
        assertEquals(Rom.OTHER, RomDetector.detect("", ""))
        assertEquals(Rom.OTHER, RomDetector.detect(" ", "\t"))
    }

    @Test
    fun `detection is case insensitive`() {
        assertEquals(Rom.XIAOMI, RomDetector.detect("XIAOMI", "REDMI"))
        assertEquals(Rom.HUAWEI, RomDetector.detect("honor", null))
        assertEquals(Rom.OPPO, RomDetector.detect("ONEPLUS", null))
        assertEquals(Rom.VIVO, RomDetector.detect(null, "IQOO"))
    }

    @Test
    fun `labels are the chinese brand names and all distinct`() {
        assertEquals("小米", Rom.XIAOMI.label)
        assertEquals("华为（含荣耀）", Rom.HUAWEI.label)
        assertEquals("OPPO（含一加/realme）", Rom.OPPO.label)
        assertEquals("vivo（含 iQOO）", Rom.VIVO.label)
        assertEquals("其它品牌", Rom.OTHER.label)
        assertEquals(Rom.entries.size, Rom.entries.map { it.label }.toSet().size)
    }
}
