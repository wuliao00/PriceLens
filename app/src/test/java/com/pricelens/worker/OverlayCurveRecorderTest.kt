package com.pricelens.worker

import com.pricelens.accessibility.PriceBasis
import com.pricelens.accessibility.PriceEvents
import com.pricelens.accessibility.ShopPlatform
import com.pricelens.data.local.dao.PriceHistoryDao
import com.pricelens.data.local.dao.SourceDayCount
import com.pricelens.data.local.dao.WatchIdentityDao
import com.pricelens.data.local.entity.PriceHistoryEntity
import com.pricelens.data.local.entity.WatchIdentityEntity
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 浮窗自采记点的写库行为（假 DAO 复刻 (productId,date) 唯一索引 REPLACE 语义）。
 * 项目没有 room-testing/Robolectric，写路径全部钉在纯决策 + 薄 IO 这一层（设计 §4.1）。
 */
private class FakeHistoryDao : PriceHistoryDao {
    val rows = mutableListOf<PriceHistoryEntity>()
    var nextId = 1L
    var writes = 0

    private fun store(point: PriceHistoryEntity) {
        writes++
        val dup = rows.indexOfFirst { it.productId == point.productId && it.date == point.date }
        val stored = if (point.id == 0L) point.copy(id = nextId++) else point
        if (dup >= 0) rows[dup] = stored else rows.add(stored)
    }

    override suspend fun insertAll(points: List<PriceHistoryEntity>) {
        points.forEach { store(it) }
    }

    override suspend fun upsertDay(point: PriceHistoryEntity): Long {
        store(point)
        return point.id
    }

    override suspend fun getDayPoint(productId: String, date: String): PriceHistoryEntity? =
        rows.firstOrNull { it.productId == productId && it.date == date }

    override suspend fun getByProduct(productId: String): List<PriceHistoryEntity> =
        rows.filter { it.productId == productId }.sortedBy { it.date }

    override suspend fun countDaysBySource(productId: String): List<SourceDayCount> =
        rows.filter { it.productId == productId }.groupBy { it.source }
            .map { SourceDayCount(it.key, it.value.map { r -> r.date }.distinct().size) }

    override suspend fun countDays(productId: String): Int = rows.filter { it.productId == productId }.map { it.date }.distinct().size

    override suspend fun deleteOlderThan(dateCutoff: String) {
        rows.removeAll { it.date < dateCutoff }
    }

    override suspend fun deleteByProduct(productId: String) {
        rows.removeAll { it.productId == productId }
    }
}

private class FakeIdentityDao : WatchIdentityDao {
    val rows = mutableMapOf<String, WatchIdentityEntity>()

    /**
     * M5 竞态夹具：打开后第一次 [getByProduct] 照给行、其后都给 null ——
     * 把「已经拿到身份对象、还没来得及写点，取消在另一条协程里把行删了」这个窗口排出来。
     */
    var nullAfterFirstRead = false
    var productReads = 0

    override suspend fun upsert(identity: WatchIdentityEntity) {
        rows[identity.productId] = identity
    }

    override suspend fun getAllOnce(): List<WatchIdentityEntity> = rows.values.sortedBy { it.confirmedAt }

    override suspend fun getByProduct(productId: String): WatchIdentityEntity? {
        if (nullAfterFirstRead) {
            productReads++
            return if (productReads == 1) rows[productId] else null
        }
        return rows[productId]
    }

    override fun observeAll(): Flow<List<WatchIdentityEntity>> = flowOf(rows.values.toList())

    override suspend fun touchLastSeen(productId: String, now: Long, price: Double, basis: String) {
        rows[productId]?.let { rows[productId] = it.copy(lastSeenAt = now, lastPrice = price, lastBasis = basis) }
    }

    override suspend fun delete(productId: String) {
        rows.remove(productId)
    }
}

class OverlayCurveRecorderTest {

    private val title = "Apple iPhone 15 128G 黑色手机"
    private val history = FakeHistoryDao()
    private val identities = FakeIdentityDao()
    private val recorder = OverlayCurveRecorder(identities, history)

    /** m1：与 Recorder 同口径取今日（LocalDate，yyyy-MM-dd） */
    private val today = LocalDate.now().toString()

    private fun detection(price: Double, basis: PriceBasis = PriceBasis.PAGE, stamp: String = price.toString()) = PriceEvents.Detected(
        price = price,
        rawPriceText = stamp,
        title = title,
        packageName = "com.jingdong.app.mall",
        platform = ShopPlatform.JD,
        priceBasis = basis
    )

    @Test
    fun `unconfirmed detections never touch the database`() = runTest {
        recorder.onDetected(detection(1299.0))
        assertEquals("未确认身份的读价必须一次写库都没有", 0, history.writes)
    }

    @Test
    fun `confirm writes one identity and the first day point`() = runTest {
        val outcome = recorder.confirm(detection(1299.0))
        val pid = (outcome as ConfirmOutcome.Confirmed).productId
        assertEquals(1, identities.rows.size)
        assertEquals(1, history.rows.size)
        val row = history.rows[0]
        assertEquals(pid, row.productId)
        assertEquals(today, row.date)
        assertEquals(1299.0, row.price, 0.001)
        assertEquals("浮窗自采点恒记 SELF_WATCH 写入通道（脚注据此说真话）", "SELF_WATCH", row.source)
    }

    @Test
    fun `later page price of same day closes over and dayLow never rises`() = runTest {
        val pid = (recorder.confirm(detection(1299.0)) as ConfirmOutcome.Confirmed).productId
        recorder.onDetected(detection(1499.0))
        var row = history.getDayPoint(pid, today)!!
        assertEquals(1499.0, row.price, 0.001)
        assertEquals("当日至低只降不升", 1299.0, row.dayLow, 0.001)
        recorder.onDetected(detection(1199.0))
        row = history.getDayPoint(pid, today)!!
        assertEquals(1199.0, row.price, 0.001)
        assertEquals(1199.0, row.dayLow, 0.001)
        assertEquals("同日同身份只有一行（(productId,date) 唯一索引 REPLACE）", 1, history.rows.size)
    }

    @Test
    fun `repeated identical signature is deduplicated`() = runTest {
        recorder.confirm(detection(1299.0))
        val writes = history.writes
        repeat(3) { recorder.onDetected(detection(1299.0)) }
        assertEquals("同签名重复事件不该再写库", writes, history.writes)
    }

    @Test
    fun `dedup key carries the date so tomorrow the same price grows a point again`() {
        // M2：签名只包 平台|标题|价格文本，去重键不带日期时，隔天读到同一个价就永远不再涨点。
        // Recorder 里取不到可控时钟，这里直接钉键的生成（onDetected 用的就是它 + today）。
        val det = detection(1299.0)
        val yesterday = LocalDate.now().minusDays(1).toString()
        assertEquals("去重键 = 签名|日期", det.signature + "|" + today, recorder.dedupKey(det, today))
        assertNotEquals(
            "日期不一样就必须是不同的去重键，否则隔天同价不涨点",
            recorder.dedupKey(det, yesterday),
            recorder.dedupKey(det, today)
        )
    }

    @Test
    fun `after-coupon read never writes nor touches`() = runTest {
        val pid = (recorder.confirm(detection(1299.0)) as ConfirmOutcome.Confirmed).productId
        val seenBefore = identities.rows[pid]!!.lastSeenAt
        val writes = history.writes
        recorder.onDetected(detection(999.0, basis = PriceBasis.AFTER_COUPON, stamp = "999"))
        assertEquals("券后价不够格写日点", writes, history.writes)
        assertEquals(seenBefore, identities.rows[pid]!!.lastSeenAt)
    }

    @Test
    fun `cancel removes identity together with its day points`() = runTest {
        val pid = (recorder.confirm(detection(1299.0)) as ConfirmOutcome.Confirmed).productId
        recorder.cancel(pid)
        assertEquals(0, identities.rows.size)
        assertEquals("只删身份不删点 = 孤儿点，脚注会把出处虚报", 0, history.rows.size)
        recorder.onDetected(detection(1299.0, stamp = "again"))
        assertEquals("取消后读价不再涨点", 0, history.rows.size)
    }

    @Test
    fun `identity deleted after the match is never written as an orphan point`() = runTest {
        val pid = (recorder.confirm(detection(1299.0)) as ConfirmOutcome.Confirmed).productId
        val writes = history.writes
        // 第一次 getByProduct（matchedIdentity）给行，第二次（writeDayPoint 写前复核）给 null
        identities.nullAfterFirstRead = true
        identities.productReads = 0
        recorder.onDetected(detection(1499.0, stamp = "1499"))
        assertEquals("写前复核读到身份已删 → 一笔都不写，也不会复活成孤儿点", writes, history.writes)
        assertEquals("确认时那个点不受影响（曲线不会多出一行）", 1, history.rows.size)
        assertEquals(pid, history.rows[0].productId)
    }
}
