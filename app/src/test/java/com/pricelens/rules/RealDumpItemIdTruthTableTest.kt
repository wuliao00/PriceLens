package com.pricelens.rules

import com.pricelens.accessibility.ShopPlatform
import com.pricelens.accessibility.extractItemId
import com.pricelens.accessibility.loadRealDump
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #73 的**前提测量**：真机商详树上到底拿不拿得到商品身份句柄 `itemId`。
 *
 * 为什么要有这个文件：#72 之后页面树会活到新鲜度窗口过为止，于是留下一条污染路径
 * （看 A 商品 → 回 App 搜 B → 屏上可能挂着 A 的券）。最自然的修法是按 `itemId` 对齐身份，
 * 但真机日志里连着两次都是 `itemId=null` —— 那不能只凭两次观察就下结论，
 * 所以拿**全部真机商详夹具**量一遍。规则路径与启发式路径的 `itemId` 都出自
 * `extractItemId(root)`（见 `DetectionPipeline` 第 86 / 176 行），量这一个函数就够。
 *
 * 这条测试断言的是**前提**，不是愿望：数字一变（哪天 `extractItemId` 修好了），
 * 它会红，而那次红的意思是"#73 可以按身份句柄来做了"，不是"代码坏了"。
 */
class RealDumpItemIdTruthTableTest {

    private val detailDumps = listOf(
        "jd_detail_instock_20260929.xml" to ShopPlatform.JD,
        "jd_detail_presale_20260929.xml" to ShopPlatform.JD,
        "jd_detail_plb110_20261003.xml" to ShopPlatform.JD,
        "jd_detail_guobu_plb110_20261003.xml" to ShopPlatform.JD,
        "jd_detail_guobu_popup_plb110_20261003.xml" to ShopPlatform.JD,
        "jd_detail_mianfei_plb110_20261003.xml" to ShopPlatform.JD,
        "tb_detail_plb110_20261003.xml" to ShopPlatform.TAOBAO
    )

    @Test
    fun `真机商详夹具的 itemId 真值表`() {
        val rows = detailDumps.map { (name, platform) ->
            Triple(name, platform.name, extractItemId(loadRealDump(name).root))
        }
        rows.forEach { (name, platform, id) -> println("[itemId] $platform $name itemId=$id") }
        val got = rows.count { it.third != null }
        println("[itemId] 合计 ${rows.size} 棵真机商详树，itemId 非空 $got 棵")

        assertEquals(
            "真机商详夹具里 itemId 非空的棵数。0 就意味着 #73 不能用 itemId 当身份句柄，" +
                "必须先决定标题归一化口径（见 docs/ROADMAP 与 #73 的描述）",
            0,
            got
        )
    }
}
