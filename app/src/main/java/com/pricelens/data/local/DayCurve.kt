package com.pricelens.data.local

import com.pricelens.data.local.entity.PriceHistoryEntity
import com.pricelens.domain.PriceSample
import com.pricelens.domain.PriceSampling
import com.pricelens.domain.PriceSource

/**
 * 价格历史「每天一点」的折叠规则。全部纯函数：不碰 Room、不碰 Context，可直接 JVM 单测
 * （回归测试见 `app/src/test/java/com/pricelens/data/local/DayCurveTest.kt`）。
 *
 * 语义（用户已拍板，别改设计）：
 *  - 一天只有一个点，这个点是 **当日最后一个 live 样本**（日线收盘语义：后写的覆盖先写的）；
 *  - 另存 **当日至低**（[PriceHistoryEntity.dayLow]）：算「历史最低」用的是它，
 *    因为收盘点不能冒充盘中低点（拿收盘点当低点，就又回到 F1 那类"把历史低位当现价"的错）；
 *  - 读侧要**按日去重**：v2 的 `insertAll` 是自增主键 + 非唯一索引，REPLACE 形同虚设，
 *    老库里同一天有多行是既成事实，唯一索引只能保证 v3 之后不再新增。
 *
 * 这里刻意只吃 [PriceHistoryEntity] 和 [PriceSample]，不注入 DAO：
 * 写库的时机与失败处理归 [com.pricelens.worker.WatchCheckRunner] 与 PriceRepository。
 */
object DayCurve {

    /**
     * 一个交易日折叠后的曲线点。
     *
     * @param id 收盘点所在行的主键（覆盖同一行要用它，别让 SQLite 再插一条）
     * @param close 当日最后一个 live 样本（收盘点，UI 画线用的就是这个值）
     * @param dayLow 当日至低；未知时等于 [close]（绝不用 0 冒充最低价）
     */
    data class Point(
        val id: Long,
        val date: String,
        val close: Double,
        val dayLow: Double,
        val source: String,
        val recordedAt: Long
    )

    /**
     * 这一行的「当日至低」。
     *
     * [PriceHistoryEntity.dayLow] 是 v3 才加上的列，老行是 0 = 未知，
     * 此时只能回退到收盘点本身；直接拿 0 去比大小会让整条曲线的"历史最低"恒等于 0。
     */
    fun lowOf(row: PriceHistoryEntity): Double {
        val low = row.dayLow
        if (low <= 0.0) return row.price
        if (row.price <= 0.0) return low
        return minOf(low, row.price)
    }

    /** 同日多行里哪一行是「当日最后一个点」：[PriceHistoryEntity.recordedAt] 大的优先，全 0 时比 id */
    fun latest(rows: List<PriceHistoryEntity>): PriceHistoryEntity? = rows.maxWithOrNull(compareBy({ it.recordedAt }, { it.id }))

    /**
     * 按日去重 + 折叠。
     *
     * 收盘点取当日最后一个观测，至低取全日各行 `lowOf` 的最小值；
     * 价格 <= 0 的脏行直接丢掉（0 价能把整条曲线的"历史最低"打成 0）。
     */
    fun collapse(rows: List<PriceHistoryEntity>): List<Point> = rows
        .filter { it.price > 0.0 }
        .groupBy { it.date }
        .toSortedMap()
        .mapNotNull { (date, sameDay) ->
            val last = latest(sameDay) ?: return@mapNotNull null
            Point(
                id = last.id,
                date = date,
                close = last.price,
                dayLow = sameDay.minOf { lowOf(it) },
                source = last.source,
                recordedAt = last.recordedAt
            )
        }

    /**
     * 盯价轮次的写入口：本轮这个样本要不要成为「今日的曲线点」，要写成什么。
     *
     * 资格判定**只能**走 [PriceSampling.curveWorthy]（星罗券后历史低价这类 `referenceOnly`
     * 来源与 0/负价都在那里被挡住），这里不再自己另写一套 if。
     *
     * 存进库的出处是**写入通道**（[PriceSource.SELF_WATCH]），不是这个数字来自哪个接口：
     * 脚注要回答的是"这条线是本机一轮轮攒出来的，还是慢慢买给的"。
     * 若写样本自己的来源名（JD_P3CN 等），[com.pricelens.data.repository.CurveProvenance]
     * 认不出它，本机攒的点会被统计成「来源未记录」。
     * 价格来自哪个接口仍然有迹可循 —— 它决定了这条样本够不够格进来（[PriceSampling.curveWorthy]）。
     *
     * @param productId 曲线表的 key，直接用 `PriceTargetEntity.productId`（已是 `jd:<sku>` 形态）
     * @param existing 当日已存的点（null = 今天还没有点）
     * @return null = 本轮这个样本没有资格写曲线
     */
    fun upsertFor(
        productId: String,
        existing: PriceHistoryEntity?,
        sample: PriceSample,
        date: String,
        recordedAt: Long
    ): PriceHistoryEntity? {
        val worthy = PriceSampling.curveWorthy(sample) ?: return null
        val day = existing?.takeIf { it.date == date }
        val storedLow = day?.takeIf { it.price > 0.0 }?.let { lowOf(it) }
        val close = worthy.price
        return PriceHistoryEntity(
            // 沿用同一行的主键：让唯一索引上的 REPLACE 是"覆盖这一天"，不是"再插一天"
            id = day?.id ?: 0L,
            productId = productId,
            date = date,
            price = close,
            // 本轮判定不了"整条曲线的最低/最高"（那要读全量历史），不做这个断言：
            // 读侧的「历史最低」一律按 dayLow 现算，这两个标记只剩展示意义
            isLowest = false,
            isHighest = false,
            source = PriceSource.SELF_WATCH.name,
            // 当日至低只降不升：外部/上一轮的点不能被本轮的更高价"抬"上去
            dayLow = storedLow?.let { minOf(close, it) } ?: close,
            recordedAt = recordedAt
        )
    }
}
