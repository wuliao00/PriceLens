package com.pricelens.ui.product

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本版**没有核验层**：找券的 UI 文案与字符串资源里不许出现"声称核过/确认过"的词，
 * 否则抽错了也长得像抽对了（这正是用户报"找券不准确"的另一半）。
 *
 * 扫描口径按文件文本硬断言（`app` 模块单测的工作目录就是模块根，读源码/资源的路径先例
 * 见 `rules/RuleFixtures` 与 `data/local/DatabaseMigrationWiringTest`）。
 * 注意：overlay 那边有一处既有文案"已确认身份"，说的是**商品身份**不是券核验，
 * 不在本刀范围也不归这条测试管；这里盯的是找券这条链路的资源与新代码。
 */
class CouponStringsGuardTest {

    private val forbidden = listOf("已核验", "已确认", "已核实", "verified", "confirmed", "checked")

    private fun read(path: String): String {
        val file = File(path)
        assertTrue("文件必须存在（路径漂了这条测试就白钉）：$path", file.isFile)
        return file.readText(Charsets.UTF_8)
    }

    private fun asciiOnly(word: String): Boolean = word.all { it in 'a'..'z' || it in 'A'..'Z' }

    private fun assertClean(label: String, text: String) {
        for (word in forbidden) {
            assertFalse("$label 出现禁用词「$word」（本版没有核验层）", text.contains(word, ignoreCase = asciiOnly(word)))
        }
    }

    @Test
    fun `strings_coupon 不含任何声称核过确认过的词`() {
        assertClean("strings_coupon.xml", read("src/main/res/values/strings_coupon.xml"))
    }

    @Test
    fun `找券新代码的界面字符串同样干净`() {
        assertClean("ProductCouponSection.kt", read("src/main/java/com/pricelens/ui/product/ProductCouponSection.kt"))
        assertClean("CouponLocalLogic.kt", read("src/main/java/com/pricelens/ui/product/CouponLocalLogic.kt"))
    }

    @Test
    fun `导出行文本里也没有核过确认过的字样`() {
        assertClean("MisreadExport.kt", read("src/main/java/com/pricelens/coupon/MisreadExport.kt"))
    }

    @Test
    fun `三档措辞必须逐档存在且各说各话`() {
        val xml = read("src/main/res/values/strings_coupon.xml")
        for (key in listOf("coupon_local_tier_high", "coupon_local_tier_medium", "coupon_local_tier_low", "coupon_local_unrecognized")) {
            assertTrue("缺少三档/未识别的措辞资源：$key", xml.contains("name=\"$key\""))
        }
    }
}
