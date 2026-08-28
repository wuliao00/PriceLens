package com.pricelens.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pricelens.R
import com.pricelens.data.remote.CrawlerResult
import com.pricelens.ui.common.AsyncValue
import com.pricelens.ui.overview.SearchViewModel
import com.pricelens.ui.theme.BadgeTone
import com.pricelens.ui.theme.Dims
import com.pricelens.ui.theme.bg
import com.pricelens.ui.theme.fg

/**
 * 数据源状态行（清晰原则：一屏看清每个源的真实结局）。
 *
 * 每源（历史价/当当/B站/优惠券/爆料/识货）一枚状态徽标：
 *  - AsyncValue.Loading → 加载中；Success → 正常；Error → 失败
 *  - Error 且 [SearchViewModel.lastOutcome] 为 [CrawlerResult.Blocked] → 反爬
 *
 * 域名诊断结果随 outcomesVersion（每轮搜索结束递增）刷新，此处订阅它
 * 以便搜索结束后重组时读到最新的 lastOutcomeFor 结果。
 */
@Composable
fun SourceStatusRow(viewModel: SearchViewModel, modifier: Modifier = Modifier) {
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val videos by viewModel.videos.collectAsStateWithLifecycle()
    val coupons by viewModel.coupons.collectAsStateWithLifecycle()
    val posts by viewModel.posts.collectAsStateWithLifecycle()
    val shihuo by viewModel.shihuo.collectAsStateWithLifecycle()
    // 订阅诊断版本：驱动搜索结束后重组，读到最新反爬/失败结果
    viewModel.outcomesVersion.collectAsStateWithLifecycle()

    // 尚未搜索且不在加载中：不占用版面
    if (!loading &&
        history is AsyncValue.Idle && videos is AsyncValue.Idle &&
        coupons is AsyncValue.Idle && posts is AsyncValue.Idle && shihuo is AsyncValue.Idle
    ) {
        return
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Dims.SpacingS)
    ) {
        SourceChip(
            stringResource(R.string.src_label_history),
            history,
            viewModel.lastOutcome("apapia-history.manmanbuy.com")
        )
        SourceChip(
            stringResource(R.string.src_label_dangdang),
            // 当当是候选主源（成功即进入爆料/候选），按 posts 状态 + 域名结果展示
            posts,
            viewModel.lastOutcome("search.dangdang.com")
        )
        SourceChip(
            stringResource(R.string.src_label_bili),
            videos,
            viewModel.lastOutcome("api.bilibili.com")
        )
        SourceChip(
            stringResource(R.string.src_label_coupon),
            coupons,
            viewModel.lastOutcome("www.gwdang.com")
        )
        SourceChip(
            stringResource(R.string.src_label_posts),
            posts,
            viewModel.lastOutcome("search.smzdm.com")
        )
        SourceChip(
            stringResource(R.string.src_label_shihuo),
            shihuo,
            viewModel.lastOutcome("www.shihuo.cn")
        )
    }
}

/** 单源状态徽标：名称 · 状态（语义色背景胶囊） */
@Composable
private fun SourceChip(name: String, value: AsyncValue<*>, outcome: CrawlerResult<String>?) {
    val status = when (value) {
        is AsyncValue.Loading -> SourceUiState.LOADING
        is AsyncValue.Success -> SourceUiState.OK
        is AsyncValue.Error ->
            if (outcome is CrawlerResult.Blocked) SourceUiState.BLOCKED else SourceUiState.FAILED
        is AsyncValue.Idle -> SourceUiState.IDLE
    }
    val tone = when (status) {
        SourceUiState.OK -> BadgeTone.POSITIVE
        SourceUiState.BLOCKED, SourceUiState.FAILED -> BadgeTone.NEGATIVE
        SourceUiState.LOADING, SourceUiState.IDLE -> BadgeTone.NEUTRAL
    }
    val stateText = stringResource(
        when (status) {
            SourceUiState.LOADING -> R.string.src_state_loading
            SourceUiState.OK -> R.string.src_state_ok
            SourceUiState.BLOCKED -> R.string.src_state_blocked
            SourceUiState.FAILED -> R.string.src_state_failed
            SourceUiState.IDLE -> R.string.src_state_idle
        }
    )

    Surface(shape = MaterialTheme.shapes.small, color = tone.bg()) {
        Row(
            modifier = Modifier.padding(
                horizontal = Dims.SpacingS,
                vertical = Dims.SpacingXS
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dims.SpacingXS)
        ) {
            Text(name, style = MaterialTheme.typography.labelSmall, color = tone.fg())
            Text(
                "·",
                style = MaterialTheme.typography.labelSmall,
                color = tone.fg().copy(alpha = 0.6f)
            )
            Text(
                stateText,
                style = MaterialTheme.typography.labelSmall,
                color = tone.fg(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = Dims.SpacingXXXL * 2)
            )
        }
    }
}

private enum class SourceUiState { LOADING, OK, FAILED, BLOCKED, IDLE }
