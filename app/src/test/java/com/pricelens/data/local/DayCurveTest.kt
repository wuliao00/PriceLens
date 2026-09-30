package com.pricelens.data.local

import com.pricelens.data.local.entity.PriceHistoryEntity
import com.pricelens.domain.PriceSample
import com.pricelens.domain.PriceSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 盯价自采曲线点的折叠语义（2026-09-30）。
 *
 * 要守住的三条口径：
 *  1. 一天一个点，这个点是**当日最后一个 live 样本**（收盘语义），不是第一个、也不是最大的；
 *  2. 「当日至低」单开一列：算历史最低看它，不看收盘点 —— 收盘点不能冒充盘中低点；
 *  3. 只有够格的样本才产点：`referenceOnly`（星罗券后历史低价）与 0/负价一律不写，
 *     闸门是 [com.pricelens.domain.PriceSampling.curveWorthy]，这里不许另起一套判定；
 *  4. 存进库的**出处是"谁写的这一行"**（本机盯价轮次 = SELF_WATCH），不是"这个数字来自哪个接口"
 *     —— 脚注要回答的是"这条线是谁攒出来的"（见 [the stored provenance is the write channel] 那条）。
 *
 * 全部纯 JVM 用例：本模块没有 Robolectric / room-testing，所以迁移后的语义（老行
 * `dayLow` = 0 表示"未知"）也必须是纯函数能覆盖的（见 [low fallback] 那条）。
 */
class DayCurveTest {

    private val sku = "100012043978"

    private fun row(
        id: Long,
        date: String,
        price: Double,
        source: String = PriceSource.SELF_WATCH.name,
        dayLow: Double = 0.0,
        recordedAt: Long = 0L
    ) = PriceHistoryEntity(
        id = id,
        productId = "jd:$sku",
        date = date,
        price = price,
        source = source,
        dayLow = dayLow,
        recordedAt = recordedAt
    )

    // ---------- ① 收盘点：当日最后一个观测 ----------

    @Test
    fun `collapse keeps the last observation of the day as the close point`() {
        val rows = listOf(
            row(1, "2026-09-30", 100.0, dayLow = 88.0, recordedAt = 10L),
            // 这一行是当日最后一个点（recordedAt 最大）
            row(2, "2026-09-30", 95.0, dayLow = 90.0, recordedAt = 20L),
            row(3, "2026-09-30", 99.0, dayLow = 97.0, recordedAt = 15L)
        )
        val days = DayCurve.collapse(rows)
        assertEquals("同日多行必须折成一天", 1, days.size)
        assertEquals("收盘点 = 当日最后一个观测（recordedAt 最大）", 95.0, days.first().close, 0.001)
        assertEquals("当日至低 = 全日各行最小值，不是收盘点", 88.0, days.first().dayLow, 0.001)
        assertEquals("出处跟着收盘点走", PriceSource.SELF_WATCH.name, days.first().source)
    }

    @Test
    fun `legacy duplicate rows without recordedAt fall back to the largest id`() {
        // 迁移前的老行 recordedAt 全是 0：那时"最后一个点"只能按插入顺序（自增 id）认
        val rows = listOf(
            row(7, "2026-09-28", 120.0, source = PriceSource.UNRECORDED_NAME),
            row(9, "2026-09-28", 110.0, source = PriceSource.UNRECORDED_NAME),
            row(8, "2026-09-28", 130.0, source = PriceSource.UNRECORDED_NAME)
        )
        val days = DayCurve.collapse(rows)
        assertEquals(1, days.size)
        assertEquals("老行没有 recordedAt 时取 id 最大的那条", 110.0, days.first().close, 0.001)
        assertEquals(9L, days.first().id)
        assertEquals("老行的观测时刻就是未知的，不许拿 id 冒充", 0L, days.first().recordedAt)
    }

    @Test
    fun `days stay one point each and keep ascending order`() {
        val days = DayCurve.collapse(
            listOf(
                row(1, "2026-10-02", 90.0, dayLow = 85.0, recordedAt = 30L),
                row(2, "2026-10-01", 100.0, dayLow = 80.0, recordedAt = 10L),
                row(3, "2026-10-01", 95.0, dayLow = 92.0, recordedAt = 20L)
            )
        )
        assertEquals(listOf("2026-10-01", "2026-10-02"), days.map { it.date })
        assertEquals(listOf(95.0, 90.0), days.map { it.close })
    }

    @Test
    fun `history lowest reads the dayLow column not the close point`() {
        // 盘中最低 70、收盘 100：曲线的"历史最低"必须是 70，收盘点不能冒充盘中低点
        val days = DayCurve.collapse(
            listOf(
                row(1, "2026-10-01", 100.0, dayLow = 70.0, recordedAt = 10L),
                row(2, "2026-10-02", 105.0, dayLow = 105.0, recordedAt = 20L)
            )
        )
        assertEquals("「历史最低」用 dayLow 列", 70.0, days.minOf { it.dayLow }, 0.001)
        assertEquals("画线用收盘点，收盘点里没有那个盘中低点", 100.0, days.minOf { it.close }, 0.001)
    }

    // ---------- ② 迁移后的老行：dayLow=0 是"未知"，不是"最低 0 元" ----------

    @Test
    fun `a migrated row with unknown dayLow falls back to its close price`() {
        val legacy = row(1, "2026-09-20", 259.0, source = PriceSource.UNRECORDED_NAME)
        assertEquals("dayLow=0 是缺信息，绝不拿 0 去比大小", 259.0, DayCurve.lowOf(legacy), 0.001)
        assertEquals(
            "脏库里同日多行要折成一天，至低取两行的较小值，但绝不是 0",
            249.0,
            DayCurve.collapse(listOf(legacy, row(2, "2026-09-20", 249.0))).first().dayLow,
            0.001
        )
        assertEquals(
            "收盘点是最后一个观测，不是最小值",
            249.0,
            DayCurve.collapse(listOf(legacy, row(2, "2026-09-20", 249.0))).first().close,
            0.001
        )
        assertTrue("至低永远不高于收盘点", DayCurve.lowOf(legacy) <= legacy.price)
    }

    @Test
    fun `latest of an empty day is nothing`() {
        assertNull(DayCurve.latest(emptyList()))
        assertEquals(emptyList<DayCurve.Point>(), DayCurve.collapse(emptyList()))
    }

    @Test
    fun `a zero price dirty row never becomes part of the curve`() {
        // 0 价 = "这一轮没拿到价"，历史上被写进过库；让它进曲线就把"历史最低"打成 0
        val days = DayCurve.collapse(
            listOf(
                row(1, "2026-10-03", 88.0, dayLow = 88.0, recordedAt = 10L),
                row(2, "2026-10-03", 0.0, recordedAt = 20L)
            )
        )
        assertEquals(1, days.size)
        assertEquals("0 价脏行不能当收盘点", 88.0, days.first().close, 0.001)
        assertEquals("0 价脏行也不能把当日最低打成 0", 88.0, days.first().dayLow, 0.001)
    }

    // ---------- ③ 写入口：资格判定只认 curveWorthy ----------

    @Test
    fun `a live sample opens the day with close and dayLow both equal to itself`() {
        val point = DayCurve.upsertFor("jd:$sku", null, PriceSample(199.0, PriceSource.SELF_WATCH), "2026-10-05", 1_000L)
        val written = requireNotNull(point) { "本轮 live 样本必须产出一个曲线点" }
        assertEquals("jd:$sku", written.productId)
        assertEquals("2026-10-05", written.date)
        assertEquals(199.0, written.price, 0.001)
        assertEquals("今天第一个点：至低就是它自己", 199.0, written.dayLow, 0.001)
        assertEquals(PriceSource.SELF_WATCH.name, written.source)
        assertEquals(1_000L, written.recordedAt)
        assertEquals("新行没有主键，交给 SQLite 自增", 0L, written.id)
    }

    @Test
    fun `a later live sample overwrites the close point but never raises the day low`() {
        val existing = row(42, "2026-10-05", 199.0, dayLow = 180.0, recordedAt = 1_000L)
        val point = requireNotNull(
            DayCurve.upsertFor("jd:$sku", existing, PriceSample(205.0, PriceSource.SELF_WATCH), "2026-10-05", 2_000L)
        )
        assertEquals("收盘语义：后写的覆盖先写的", 205.0, point.price, 0.001)
        assertEquals("当日至低只降不升", 180.0, point.dayLow, 0.001)
        assertEquals("覆盖同一行而不是插一条新的", 42L, point.id)
        assertEquals(2_000L, point.recordedAt)
    }

    @Test
    fun `a cheaper live sample lowers the day low too`() {
        val existing = row(42, "2026-10-05", 199.0, dayLow = 180.0, recordedAt = 1_000L)
        val point = requireNotNull(
            DayCurve.upsertFor("jd:$sku", existing, PriceSample(150.0, PriceSource.LINKSTARS_LIST), "2026-10-05", 2_000L)
        )
        assertEquals(150.0, point.price, 0.001)
        assertEquals(150.0, point.dayLow, 0.001)
    }

    @Test
    fun `the stored provenance is the write channel not the price origin`() {
        // 盯价轮次从京东查价接口拿到的现价，这一行仍然是"本机自采"的：脚注要回答的是
        // "这条线谁攒出来的"，不是"这个数字来自哪个接口"。
        // 若写成 JD_P3CN，CurveProvenance 认不出这个出处，脚注会把手机自己攒的点说成「来源未记录」。
        val point = requireNotNull(
            DayCurve.upsertFor("jd:$sku", null, PriceSample(199.0, PriceSource.JD_P3CN), "2026-10-05", 1_000L)
        )
        assertEquals(PriceSource.SELF_WATCH.name, point.source)
        assertNotNull(
            "存进去的出处必须是 CurveProvenance 认得的枚举名，否则统计里只会剩「来源未记录」",
            PriceSource.fromName(point.source)
        )
        assertEquals(
            "同一轮无论价格来自哪个接口，写入通道都是盯价自采",
            point.source,
            requireNotNull(
                DayCurve.upsertFor("jd:$sku", null, PriceSample(199.0, PriceSource.LINKSTARS_LIST), "2026-10-05", 1_000L)
            ).source
        )
    }

    @Test
    fun `a reference only sample never writes a curve point`() {
        // F1 的那颗星罗券后历史低价：本轮可以展示，但绝不能变成"今天的点"写进历史表
        val reference = PriceSample(4999.0, PriceSource.LINKSTARS_HISTORY_LOW)
        assertTrue("前提：该来源确实被标成 referenceOnly", reference.source.referenceOnly)
        assertNull(
            "referenceOnly 的样本没有资格进曲线",
            DayCurve.upsertFor("jd:$sku", null, reference, "2026-10-05", 1_000L)
        )
        assertNull(
            "当日已有点也不能被参考值覆盖",
            DayCurve.upsertFor("jd:$sku", row(1, "2026-10-05", 5999.0, dayLow = 5999.0), reference, "2026-10-05", 2_000L)
        )
    }

    @Test
    fun `a missing price never writes a curve point`() {
        // 取不到价的目标不写：不写 0，也不能拿上一轮的旧价当今天
        assertNull(DayCurve.upsertFor("jd:$sku", null, PriceSample(0.0, PriceSource.JD_P3CN), "2026-10-05", 1_000L))
        assertNull(DayCurve.upsertFor("jd:$sku", null, PriceSample(-8.0, PriceSource.JD_P3CN), "2026-10-05", 1_000L))
        assertNull(
            "0 价也不能把当日已存的点冲掉",
            DayCurve.upsertFor(
                "jd:$sku",
                row(1, "2026-10-05", 199.0, dayLow = 180.0),
                PriceSample(0.0, PriceSource.JD_P3CN),
                "2026-10-05",
                2_000L
            )
        )
    }

    @Test
    fun `curve sources are live by definition while the ranked low is not`() {
        // 曲线用的来源必须 referenceOnly=false；这两个新成员一旦被人改成 true，写曲线就静默停摆
        assertTrue(PriceSource.fromName(PriceSource.SELF_WATCH.name)?.referenceOnly == false)
        assertTrue(PriceSource.fromName(PriceSource.MANMANBUY.name)?.referenceOnly == false)
        assertEquals("本机盯价自采", PriceSource.labelOf(PriceSource.SELF_WATCH.name))
        assertEquals("慢慢买", PriceSource.labelOf(PriceSource.MANMANBUY.name))
        // 认不出的出处（含迁移哨兵）照实说"来源未记录"，绝不猜成慢慢买
        assertEquals(PriceSource.UNRECORDED_LABEL, PriceSource.labelOf(PriceSource.UNRECORDED_NAME))
        assertNull(PriceSource.fromName(PriceSource.UNRECORDED_NAME))
        assertEquals(PriceSource.UNRECORDED_LABEL, PriceSource.labelOf(null))
    }
}
