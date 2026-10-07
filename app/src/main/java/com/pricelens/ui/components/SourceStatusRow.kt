package com.pricelens.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import com.pricelens.data.remote.CrawlerBlockedException
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
 *  - AsyncValue.Loading → 加载中
 *  - Success(有数据) → 正常
 *  - Success(空表) → **无结果**：够着了该源、过滤后确实 0 条（F4 前这一格写成「正常」）
 *  - Error → 反爬（cause 是 [CrawlerBlockedException]，或 [SearchViewModel.lastOutcome] 为 Blocked）/ 失败
 *  - Idle → 未查询
 *
 * 已知合并：当当徽标共用 `posts` 的状态（当当是候选主源，成功即进入爆料/候选），
 * 所以"当当正常而值得买被拦"时当当会跟着显示反爬；域名结果只用于区分反爬与失败。
 *
 * 域名诊断结果随 outcomesVersion（每轮搜索结束递增）刷新，此处订阅它
 * 以便搜索结束后重组时读到最新的 lastOutcomeFor 结果。
 */
@OptIn(ExperimentalLayoutApi::class)
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

    // F12（2026-10-08 真机截图复核）：这里原来是 `Row + horizontalScroll`，六枚徽标在
    // 1080px 宽的屏上只放得下 2.5 枚——而本组件的注释写的恰恰是"一屏看清每个源的真实结局"。
    // 横向滚动把"哪几个源被拦了"藏到屏外，等于把最需要看见的信息做成要主动 swipe 才看得到。
    // 改成 FlowRow：放不下就换行，六枚全在眼前（与 F5 统计行同一处理）。
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dims.SpacingS),
        verticalArrangement = Arrangement.spacedBy(Dims.SpacingXS)
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
            // 识货的请求走 m 站（`ShihuoApi.searchProducts` 的 `https://m.shihuo.cn/search?...`），
            // 域名诊断键必须是 m.shihuo.cn——旧值 www.shihuo.cn 永远查不到记录，反爬/失败无从区分
            viewModel.lastOutcome("m.shihuo.cn")
        )
    }
}

/** 单源状态徽标：名称 · 状态（语义色背景胶囊） */
@Composable
private fun SourceChip(name: String, value: AsyncValue<*>, outcome: CrawlerResult<String>?) {
    val status = sourceChipStateOf(value, outcome)
    val tone = when (status) {
        SourceChipState.OK -> BadgeTone.POSITIVE
        SourceChipState.BLOCKED, SourceChipState.FAILED -> BadgeTone.NEGATIVE
        SourceChipState.LOADING, SourceChipState.IDLE, SourceChipState.NO_RESULTS -> BadgeTone.NEUTRAL
    }
    val stateText = stringResource(
        when (status) {
            SourceChipState.LOADING -> R.string.src_state_loading
            SourceChipState.OK -> R.string.src_state_ok
            SourceChipState.NO_RESULTS -> R.string.src_state_empty
            SourceChipState.BLOCKED -> R.string.src_state_blocked
            SourceChipState.FAILED -> R.string.src_state_failed
            SourceChipState.IDLE -> R.string.src_state_idle
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

/** 徽标状态（F4 起对外可见，便于纯函数单测；渲染映射仍在本文件的 [SourceChip]） */
enum class SourceChipState { LOADING, OK, NO_RESULTS, FAILED, BLOCKED, IDLE }

/**
 * 「这一枚徽标该写什么」的唯一判定（F4，2026-09-29；纯函数，可 JVM 单测）。
 *
 *  - `Success(空表)` 从 `OK` 里拆出来：仓储层已保证"空表 = 够着了且确实 0 条"，
 *    它与"根本没够着"是两件事，共用「正常」就是把"没查到"说成"查到了但什么都没有"；
 *  - `Error` 的成因优先看 `cause`（解析类冒泡的类型化失败），域名诊断结果作参考：
 *    任一指向反爬就写「反爬」，否则「失败」。
 *
 * 修复前这里只有 `Success → OK`，于是断网整场也能全绿。
 */
fun sourceChipStateOf(value: AsyncValue<*>, outcome: CrawlerResult<String>?): SourceChipState = when (value) {
    is AsyncValue.Loading -> SourceChipState.LOADING
    is AsyncValue.Success ->
        if (value.data.isEmptyCollection()) SourceChipState.NO_RESULTS else SourceChipState.OK
    is AsyncValue.Error ->
        if (value.cause is CrawlerBlockedException || outcome is CrawlerResult.Blocked) {
            SourceChipState.BLOCKED
        } else {
            SourceChipState.FAILED
        }
    is AsyncValue.Idle -> SourceChipState.IDLE
}

/**
 * "成功但一个条目都没有"的判定。非集合载荷（如历史价 [com.pricelens.data.remote.ManmanbuyApi.History]）
 * 不算空：它能进到 Success 就说明该源给了可用的结构化结果。
 */
private fun Any?.isEmptyCollection(): Boolean = (this as? Collection<*>)?.isEmpty() == true
