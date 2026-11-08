package com.pricelens.ui.autotrack

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pricelens.R
import com.pricelens.ui.components.PriceCard
import com.pricelens.ui.components.SourceHealthBoard
import com.pricelens.ui.components.SourceHealthCell
import com.pricelens.ui.price.PriceWatchViewModel
import com.pricelens.ui.theme.Dims

/**
 * 自动化中心（全屏二级页，2026-11 新增）。
 *
 * 与「盯价」tab 顶部那张卡的分工：tab 上的卡是**入口与状态摘要**（一眼看到开没开、上次/下次、
 * 立即运行）；这一页是**完整控制台**——所有开关与频率、运行历史、来源健康、隐私说明都在这里，
 * 不需要在 tab 里滚动找。
 *
 * 数据全部来自 [AutoTrackViewModel]（Hilt，Activity 作用域）：与盯价 tab、价保页共享同一份
 * 仓库状态，任何一处改开关/频率，其它处立刻刷新（StateFlow 同源）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoTrackScreen(
    onBack: () -> Unit,
    onOpenPriceProtection: () -> Unit = {},
    sourceBoard: List<SourceHealthCell> = emptyList(),
    viewModel: AutoTrackViewModel = hiltViewModel(),
    watchViewModel: PriceWatchViewModel = hiltViewModel()
) {
    val autoTrack by viewModel.autoTrack.collectAsStateWithLifecycle()
    val protection by viewModel.protection.collectAsStateWithLifecycle()
    val runs by viewModel.runs.collectAsStateWithLifecycle()
    val wifiOnly by viewModel.wifiOnly.collectAsStateWithLifecycle()
    val quietPause by viewModel.quietPause.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    val targets by watchViewModel.watchTargets.collectAsStateWithLifecycle()

    // 补登记调度链：只在首次进入自动化中心时做一次（幂等，VM 内有开关）
    LaunchedEffect(Unit) { viewModel.ensureScheduled() }

    // 活跃目标数由盯价 VM 提供（引擎状态里没有这个字段，不编数字）
    val activeTargets = targets.count { it.active }
    val cardState = autoTrack.toCardState(wifiOnly = wifiOnly, quietPause = quietPause)
        .copy(targetCount = activeTargets, running = autoTrack.running || working)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rd_autotrack_center_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(Dims.SpacingXL),
            verticalArrangement = Arrangement.spacedBy(Dims.SpacingL)
        ) {
            item(key = "status") {
                AutoTrackCenterCard(
                    state = cardState,
                    onToggle = viewModel::setEnabled,
                    onRunNow = viewModel::runNow,
                    onOpenCenter = onBack,
                    onIntervalSelected = viewModel::setIntervalMinutes,
                    onWifiOnlyChange = viewModel::setWifiOnly,
                    onQuietPauseChange = viewModel::setQuietPause,
                    showOpenCenterButton = false
                )
            }
            item(key = "protection_entry") {
                ProtectionCenterCard(
                    state = protection.toCardState(),
                    onOpen = onOpenPriceProtection
                )
            }
            if (sourceBoard.isNotEmpty()) {
                item(key = "source_board") {
                    // 板卡自带「来源健康」标题，不再外加 SectionHeader（真机首轮出现两行重复标题）
                    SourceHealthBoard(cells = sourceBoard)
                }
            }
            item(key = "history") {
                PriceCard(modifier = Modifier.fillMaxWidth()) {
                    AutoTrackHistorySection(
                        runs = runs.map { it.toRunView() },
                        title = stringResource(R.string.rd_autotrack_history)
                    )
                }
            }
            item(key = "privacy") {
                PriceCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.rd_autotrack_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(Dims.SpacingS))
                    Text(
                        stringResource(R.string.rd_autotrack_privacy),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(Dims.SpacingXS))
                    Text(
                        stringResource(R.string.rd_autotrack_privacy_detail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (cardState.targetCount == 0) {
                item(key = "footer") {
                    Text(
                        stringResource(R.string.rd_autotrack_no_targets),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = Dims.SpacingS)
                    )
                }
            }
        }
    }
}
