package com.pricelens.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 自动追价的轮次记录（`auto_track_runs`，DB v5 新增）。
 *
 * 为什么要落库而不是只在内存：用户要能回看「昨晚自动跑了没有、查到几个价、命中几个目标」，
 * 而 WorkManager 的每次执行都是一个新进程机会，内存里的 `WatchCheckRunner.lastRound` 会丢。
 * 行数由保留策略限制（见 DAO 的 `deleteOlderThan`，只留最近 [MAX_KEPT_RUNS] 条附近）。
 *
 * 字段口径与 [com.pricelens.worker.WatchCheckRunner.Outcome] 对齐：
 *  - [targets] = 本轮活跃盯价目标数；
 *  - [checked] = 真正拿到**真现价**（直查 live 或同款搜索价）的目标数；
 *  - [hits] = 现价 ≤ 目标价的目标数（"命中"，与是否被通知闸门抑制无关）；
 *  - [note] = 人读一句话总结（含失败源），UI 直接展示。
 */
@Entity(
    tableName = "auto_track_runs",
    indices = [Index("atMs")]
)
data class AutoTrackRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 本轮完成时刻（epoch millis） */
    val atMs: Long,
    /** 本轮活跃目标数 */
    val targets: Int,
    /** 本轮拿到真现价的目标数 */
    val checked: Int,
    /** 本轮现价 ≤ 目标价的目标数 */
    val hits: Int,
    /** 一句话总结（中文，UI 直接显示；失败源也写在这里） */
    val note: String
) {
    companion object {
        /** 轮次历史保留条数上限（约一周的 30~60 分钟一轮；再多也没人看，还占库） */
        const val MAX_KEPT_RUNS: Int = 500
    }
}

/**
 * 购买记录（`purchase_records`，DB v5 新增）：价保助手的唯一数据源。
 *
 * 用户买了东西后录入一条（平台/标题/实付价/购买时间），后台周期用自动搜索拿现价，
 * 按平台价保规则算"可申请金额/截止日"；买了的东西换机不能丢，所以也进了备份 payload
 * （见 `data/backup/BackupPayload.PurchaseDto`，可选字段、向后兼容）。
 *
 * 时间字段语义：
 *  - [purchasedAtMs] 购买时刻，价保窗口的起点；
 *  - [lastCheckedAtMs] 最近一次成功拿到现价的时刻（0 = 还没查过）；
 *  - [claimDeadlineMs] 价保截止时刻（0 = 该平台无价保/尚未计算）；
 *  - [claimedAtMs] 用户点了"已申请"的时刻（0 = 未申请）——**幂等**：>0 后不再提醒；
 *  - [notifiedAtMs] 最近一次**发出价保提醒**的时刻（0 = 没提醒过）——幂等防重复通知；
 *  - [windowDaysOverride] 用户覆盖的价保窗口天数（0 = 平台默认档，见 ProtectionPlatform）；
 *  - [notifiedYuan] 最近一次提醒金额，用来判"更低的降价档位"是否值得再提醒。
 *
 * 默认值同时写进 [ColumnInfo.defaultValue] 与 `AppDatabase.MIGRATION_4_5_SQL`：Room 的
 * TableInfo 校验比的是迁移后的真实列，两边不逐字对上就会在真机开库时崩（v2→v3 的教训）。
 */
@Entity(
    tableName = "purchase_records",
    indices = [Index("platform"), Index("purchasedAtMs")]
)
data class PurchaseRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 平台小写名（jd/taobao/pdd/dangdang/…，与 PriceTargetEntity.platform 同口径） */
    val platform: String,
    /** 平台内商品号（`<platform>:<id>` 或纯 id；可空：用户只填标题也能做关键词搜索） */
    val productId: String? = null,
    /** 商品标题（录入时的原文，也是关键词搜索的来源） */
    val title: String,
    /** 实付价（元，>0） */
    val price: Double,
    /** 购买时刻（epoch millis），价保窗口起点 */
    val purchasedAtMs: Long,
    /** 商品链接（可空，仅用于 UI 跳转） */
    val url: String? = null,
    /** 最近一次查到的现价；0 = 还没查到过（绝不拿它冒充当前价，见 PriceProtectionPolicy） */
    @ColumnInfo(defaultValue = "0.0")
    val lastSeenPrice: Double = 0.0,
    /** 最近一次成功查到现价的时刻；0 = 还没查过 */
    @ColumnInfo(defaultValue = "0")
    val lastCheckedAtMs: Long = 0,
    /** 最近一次评估出的可申请金额（元，已扣 1 元门槛）；0 = 当前不可申请 */
    @ColumnInfo(defaultValue = "0.0")
    val claimableYuan: Double = 0.0,
    /** 价保截止时刻；0 = 该平台无价保/尚未计算 */
    @ColumnInfo(defaultValue = "0")
    val claimDeadlineMs: Long = 0,
    /** 用户点「已申请」的时刻；0 = 未申请 */
    @ColumnInfo(defaultValue = "0")
    val claimedAtMs: Long = 0,
    /** 最近一次发出价保提醒的时刻；0 = 没提醒过（幂等键） */
    @ColumnInfo(defaultValue = "0")
    val notifiedAtMs: Long = 0,
    /**
     * 用户/商品页覆盖的价保窗口天数（2026-11 价保规则表：京东自营 7/15/30/90/180/365、
     * 天猫 15/30、拼多多 7/30/3=72h…）；0 = 用平台默认档（[com.pricelens.domain.ProtectionPlatform]）。
     */
    @ColumnInfo(defaultValue = "0")
    val windowDaysOverride: Int = 0,
    /** 最近一次价保提醒时的预计可申请金额（元）：同一「降价档位」只提醒一次，档位变化才允许再提醒 */
    @ColumnInfo(defaultValue = "0.0")
    val notifiedYuan: Double = 0.0
)
