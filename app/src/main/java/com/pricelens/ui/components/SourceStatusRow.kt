package com.pricelens.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pricelens.R
import com.pricelens.data.remote.CrawlerBlockedException
import com.pricelens.data.remote.CrawlerResult
import com.pricelens.ui.common.AsyncValue
import com.pricelens.ui.overview.SearchViewModel

/**
 * 数据源状态板（清晰原则：一屏看清每个源的真实结局）。
 *
 * 2026-11 大改造：从「一排横向滚动的状态胶囊」升级成**来源健康板**
 * （[SourceHealthBoard]：可用计数 + 按源分段的健康横条 + 色点与不可达原因）。
 * 状态判定（[sourceChipStateOf]）与文案口径原样保留——那套判定有 JVM 单测钉着，
 * 这次只换渲染，不换语义。
 *
 * 每源（历史价/当当/B站/优惠券/爆料/识货）的结局：
 *  - AsyncValue.Loading → 加载中
 *  - Success(有数据) → 正常
 *  - Success(空表) → **无结果**：够着了该源、过滤后确实 0 条（仍算可达，健康板上算可用）
 *  - Error → 反爬（cause 是 [CrawlerBlockedException]，或 [SearchViewModel.lastOutcome] 为 Blocked）/ 失败
 *  - Idle → 未查询（不占健康板：还没请求过，没有"健康"可言）
 *
 * 已知合并：当当徽标共用 `posts` 的状态（当当是候选主源，成功即进入爆料/候选），
 * 所以"当当正常而值得买被拦"时当当会跟着显示反爬；域名结果只用于区分反爬与失败。
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

    val cells = listOf(
        cellOf(
            label = stringResource(R.string.src_label_history),
            sourceId = "history",
            value = history,
            outcome = viewModel.lastOutcome("apapia-history.manmanbuy.com")
        ),
        cellOf(
            label = stringResource(R.string.src_label_dangdang),
            // 当当是候选主源（成功即进入爆料/候选），按 posts 状态 + 域名结果展示
            sourceId = "dangdang",
            value = posts,
            outcome = viewModel.lastOutcome("search.dangdang.com")
        ),
        cellOf(
            label = stringResource(R.string.src_label_bili),
            sourceId = "bili",
            value = videos,
            outcome = viewModel.lastOutcome("api.bilibili.com")
        ),
        cellOf(
            label = stringResource(R.string.src_label_coupon),
            sourceId = "coupon",
            value = coupons,
            outcome = viewModel.lastOutcome("www.gwdang.com")
        ),
        cellOf(
            label = stringResource(R.string.src_label_posts),
            sourceId = "posts",
            value = posts,
            outcome = viewModel.lastOutcome("search.smzdm.com")
        ),
        cellOf(
            label = stringResource(R.string.src_label_shihuo),
            sourceId = "shihuo",
            value = shihuo,
            // 识货的请求走 m 站（`ShihuoApi.searchProducts` 的 `https://m.shihuo.cn/search?...`），
            // 域名诊断键必须是 m.shihuo.cn——旧值 www.shihuo.cn 永远查不到记录，反爬/失败无从区分
            outcome = viewModel.lastOutcome("m.shihuo.cn")
        )
    )
    SourceHealthBoard(cells = cells, modifier = modifier)
}

/**
 * 单源 → 健康板一格。
 * IDLE 只有在"别的源还活着"时才会出现在板上（全 Idle 时整块不渲染），原因写「本轮未查询」；
 * 反爬/失败把**归一化原因**写进 note（反爬拦截 / 失败），不再直接把异常类名漏到屏上。
 * 状态与原因分工：BLOCKED=被反爬拦住、DEGRADED=网络/解析失败，两者都不再写「不可达 · 失败」这种同义重复。
 */
@Composable
private fun cellOf(label: String, sourceId: String, value: AsyncValue<*>, outcome: CrawlerResult<String>?): SourceHealthCell {
    val state = sourceChipStateOf(value, outcome)
    val healthState = when (state) {
        SourceChipState.OK, SourceChipState.NO_RESULTS -> SourceHealthState.OK
        SourceChipState.BLOCKED -> SourceHealthState.BLOCKED
        SourceChipState.FAILED -> SourceHealthState.DEGRADED
        SourceChipState.LOADING, SourceChipState.IDLE -> SourceHealthState.UNKNOWN
    }
    val note = when (state) {
        SourceChipState.BLOCKED -> stringResource(R.string.rd_source_note_blocked)
        SourceChipState.FAILED -> stringResource(R.string.rd_source_note_failed)
        SourceChipState.LOADING -> stringResource(R.string.rd_source_note_loading)
        SourceChipState.IDLE -> stringResource(R.string.rd_source_note_idle)
        SourceChipState.OK, SourceChipState.NO_RESULTS -> null
    }
    return SourceHealthCell(sourceId = sourceId, label = label, state = healthState, note = note)
}

/** 徽标状态（F4 起对外可见，便于纯函数单测；渲染映射见 [cellOf]） */
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
