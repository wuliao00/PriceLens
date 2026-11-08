package com.pricelens.ui.autotrack

import com.pricelens.data.local.entity.AutoTrackRunEntity
import com.pricelens.data.repository.AutoTrackState
import com.pricelens.data.repository.ProtectionState

/**
 * 自动追价 / 价保仓库状态 → UI 展示模型的映射点（唯一映射处）。
 *
 * 为什么不直接让卡片吃仓库类型：UI 模型里 null 有明确语义（"没有/未知"），
 * 而引擎契约用 0L / 0 表示"没有"；这一层把哨兵值翻成可空，卡片里就不必到处 `> 0` 判断，
 * 也不会出现"0 个目标"和"还没跑过"共用同一个 0 的歧义。
 */

/** 自动追价状态 → 卡片状态（wifiOnly/quietPause 由 VM 本地镜像提供） */
fun AutoTrackState.toCardState(wifiOnly: Boolean, quietPause: Boolean): AutoTrackCardState = AutoTrackCardState(
    enabled = enabled,
    intervalMinutes = intervalMinutes,
    lastRunAtMs = lastRunAtMs.takeIf { it > 0L },
    nextRunAtMs = nextRunAtMs.takeIf { it > 0L },
    // 活跃目标数不在这份契约里（AutoTrackState 只给"最近一轮查到价/命中"），
    // 屏幕侧能用盯价 VM 数出来时再覆盖；这里保持 0 = 未知，不编一个数。
    targetCount = 0,
    lastChecked = lastChecked,
    lastHits = lastHits,
    running = running,
    lastNote = lastNote?.takeIf { it.isNotBlank() },
    wifiOnly = wifiOnly,
    quietPause = quietPause,
    // 失败源的数量没有结构化字段（仓库把"失败源 xxx"写进 lastNote 一句话里），
    // 这里不猜数字，异常说明由 lastNote 原样展示。
    badSourceCount = 0
)

/** 轮次实体 → 历史行 */
fun AutoTrackRunEntity.toRunView(): AutoTrackRunView = AutoTrackRunView(
    atMs = atMs,
    targets = targets,
    checked = checked,
    hits = hits,
    note = note
)

/** 价保状态 → 卡片状态（0 时刻翻成 null：卡片据此显示"还没检查过"而不是 1970 年） */
fun ProtectionState.toCardState(): ProtectionCardState = ProtectionCardState(
    watchCount = watchCount,
    claimableCount = claimableCount,
    claimableYuan = claimableYuan,
    lastCheckAtMs = lastCheckAtMs.takeIf { it > 0L },
    nextCheckAtMs = nextCheckAtMs.takeIf { it > 0L }
)
