package com.pricelens.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.pricelens.R
import com.pricelens.domain.WatchTargetPolicy
import com.pricelens.ui.theme.BadgeTone

/**
 * 平台徽标（2026-11 真机首轮修复）：盯价目标行/目标卡的平台归属必须一眼可见。
 *
 * 之前盯价页与「我的」页各自内联平台映射，真机截图里「我的」页目标行根本没有平台徽标，
 * 而盯价页有的行又把徽标挤掉了——收成一个组件 + 一个资源映射，两处版式与文案才能一致。
 */
@Composable
fun PlatformBadge(platform: String, modifier: Modifier = Modifier) {
    PriceBadge(stringResource(platformLabelRes(platform)), BadgeTone.NEUTRAL, modifier)
}

/** 平台键 → 展示资源（与设置目标价弹窗、不可盯原因共用同一批字符串，不另起说法） */
fun platformLabelRes(platform: String): Int = when (platform) {
    WatchTargetPolicy.PLATFORM_JD -> R.string.watch_platform_jd
    WatchTargetPolicy.PLATFORM_TAOBAO -> R.string.watch_platform_taobao
    WatchTargetPolicy.PLATFORM_PDD -> R.string.watch_platform_pdd
    WatchTargetPolicy.PLATFORM_DANGDANG -> R.string.watch_platform_dangdang
    WatchTargetPolicy.PLATFORM_SHIHUO -> R.string.watch_platform_shihuo
    WatchTargetPolicy.PLATFORM_SMZDM -> R.string.watch_platform_smzdm
    else -> R.string.watch_platform_unknown
}
