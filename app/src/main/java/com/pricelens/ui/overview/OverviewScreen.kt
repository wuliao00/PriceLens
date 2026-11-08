package com.pricelens.ui.overview

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pricelens.R
import com.pricelens.data.remote.CrawlerResult
import com.pricelens.data.remote.GwdangApi
import com.pricelens.data.remote.JdApi
import com.pricelens.data.remote.ManmanbuyApi
import com.pricelens.data.remote.ShihuoApi
import com.pricelens.data.remote.SmzdmApi
import com.pricelens.domain.EnterReplayGuard
import com.pricelens.ui.accuracy.AccuracyBlock
import com.pricelens.ui.accuracy.AccuracyIssueView
import com.pricelens.ui.accuracy.AccuracyView
import com.pricelens.ui.common.AsyncValue
import com.pricelens.ui.common.EmptyStateCause
import com.pricelens.ui.common.EmptyStateCauseOf
import com.pricelens.ui.common.valueOrDefault
import com.pricelens.ui.common.valueOrNull
import com.pricelens.ui.components.AppImage
import com.pricelens.ui.components.EmptyState
import com.pricelens.ui.components.HeroBanner
import com.pricelens.ui.components.PriceBadge
import com.pricelens.ui.components.PriceCard
import com.pricelens.ui.components.SectionHeader
import com.pricelens.ui.components.ShimmerList
import com.pricelens.ui.components.SourceStatusRow
import com.pricelens.ui.components.enterReveal
import com.pricelens.ui.layout.QuoteGroup
import com.pricelens.ui.layout.QuoteInputs
import com.pricelens.ui.layout.QuoteKind
import com.pricelens.ui.layout.QuoteLabels
import com.pricelens.ui.layout.QuoteState
import com.pricelens.ui.layout.SourceObservation
import com.pricelens.ui.layout.SourceQuotes
import com.pricelens.ui.theme.BadgeTone
import com.pricelens.ui.theme.Dims
import com.pricelens.ui.theme.MotionDurations
import com.pricelens.ui.theme.PriceLensEasing
import com.pricelens.ui.theme.PriceType
import com.pricelens.util.PriceFormatter
import com.pricelens.util.PriceJudgment
import com.pricelens.util.UrlOpener

/**
 * 概览页（「熔金行情」大改后的首屏）。
 *
 * 四个区块，按「用户此刻最想知道什么」排：
 *  ① 还没搜索时 —— **品牌英雄区**（金卡 + 一句话价值 + 两个主 CTA：粘贴链接比价 / 打开自动追价）
 *     + 最近搜索 chips + 页尾「更多入口」（原来占半屏的三条折叠引导降级到这里）；
 *  ② 搜索有结果时 —— 来源健康板 → **结果英雄卡**（超大价格 + 价格口径胶囊 + 准确性判定）
 *     → 各源报价 → 近期走势 → 详情入口 → 更多入口；
 *  ③ 搜索但无结果时 —— 来源健康板（说明谁没够着）→ 成因空态卡 → 更多入口；
 *  ④ 真实失败但持有旧数据时 —— 顶部友好提示，旧数据照常展示。
 *
 * 准确性判定（[AccuracyView]）由准确性引擎产出、MainActivity 映射后传入；
 * `null` = 本轮没有判定（还没搜 / 引擎未就绪），此时不摆「置信未知」这种空话。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OverviewScreen(
    searchViewModel: SearchViewModel,
    onGoBilibili: () -> Unit = {},
    onOpenProduct: (String) -> Unit = {},
    onPasteLink: () -> Unit = {},
    onOpenAutoTrack: () -> Unit = {},
    accuracy: AccuracyView? = null,
    onReportAccuracyIssue: (AccuracyIssueView) -> Unit = {},
    autoTrack: com.pricelens.ui.autotrack.AutoTrackCardState? = null
) {
    val loading by searchViewModel.loading.collectAsStateWithLifecycle()
    val keyword by searchViewModel.keyword.collectAsStateWithLifecycle()
    val productAsync by searchViewModel.product.collectAsStateWithLifecycle()
    val historyAsync by searchViewModel.history.collectAsStateWithLifecycle()
    val judgment by searchViewModel.judgment.collectAsStateWithLifecycle()
    val couponsAsync by searchViewModel.coupons.collectAsStateWithLifecycle()
    val postsAsync by searchViewModel.posts.collectAsStateWithLifecycle()
    val shihuoAsync by searchViewModel.shihuo.collectAsStateWithLifecycle()
    val livePrice by searchViewModel.livePrice.collectAsStateWithLifecycle()
    val realtimeSource by searchViewModel.realtimeSource.collectAsStateWithLifecycle()
    val netPrice by searchViewModel.netPrice.collectAsStateWithLifecycle()
    val recentSearches by searchViewModel.recentSearches.collectAsStateWithLifecycle()

    // 适配数据源：AsyncValue → 渲染所需的纯值（Error 自动回退旧数据）
    val product = productAsync.valueOrNull()?.toJdProduct()
    val history = historyAsync.valueOrNull()
    val coupons = couponsAsync.valueOrDefault(emptyList())
    val posts = postsAsync.valueOrDefault(emptyList())
    val shihuoItems = shihuoAsync.valueOrDefault(emptyList())

    if (loading && product == null) {
        ShimmerList()
        return
    }

    // §2.4 入场守卫：屏幕级 remember，键是与数据无关的稳定标识（见 enterReveal 注释）
    val enterGuard = remember { EnterReplayGuard() }
    val context = LocalContext.current
    val reportedText = stringResource(R.string.rd_accuracy_reported)
    // 买前防套信号（domain 纯函数）：口径全部在 AutoTrackPolicy，UI 只负责显示 label/detail。
    // 现价优先取本机页面价（最高信任），历史与近期序列来自本轮曲线；来源数/低置信取自准确性快照。
    val buySignal = remember(product?.price, history?.points, accuracy, livePrice) {
        if (product == null) {
            null
        } else {
            com.pricelens.domain.AutoTrackPolicy.buySignal(
                nowPrice = livePrice ?: product.price.takeIf { it > 0 },
                historyLow = history?.lowest,
                recentPrices = history?.points?.map { it.price }.orEmpty(),
                liveSourceCount = accuracy?.evidence?.count { !it.stale && it.price != null } ?: 0,
                lowConfidence = accuracy?.level == com.pricelens.ui.accuracy.ConfidenceLevelView.LOW ||
                    accuracy?.level == com.pricelens.ui.accuracy.ConfidenceLevelView.UNKNOWN
            )
        }
    }
    // 准确性反馈：先记本机（VM 调用），再给一句 Toast 回执——只在本机记录，不上传
    val reportWithToast: (AccuracyIssueView) -> Unit = { issue ->
        onReportAccuracyIssue(issue)
        Toast.makeText(context, reportedText, Toast.LENGTH_SHORT).show()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Dims.SpacingXL, vertical = Dims.SpacingM)
    ) {
        if (product == null) {
            // 空态有三种完全不同的成因（F4，2026-09-29）：
            //  ① 还没搜过；② 搜过、一个源都没够着（断网/被拦）；③ 搜过了但没匹配上关键词。
            // 判定抽成纯函数 EmptyStateCauseOf.of()（有 JVM 单测），这里只做字符串映射。
            val searched = keyword.isNotBlank()
            val emptyCause = EmptyStateCauseOf.of(searched, listOf(postsAsync, shihuoAsync))

            // 搜过但失败时，健康板就是"到底谁没够着"的答案；没搜过不摆（没有健康可言）
            if (searched) {
                item(key = "source_board") {
                    SourceStatusRow(
                        searchViewModel,
                        Modifier.enterReveal(0, enterGuard, OverviewEnterKeys.SourceBoard)
                    )
                }
            }

            if (emptyCause == EmptyStateCause.NOT_SEARCHED) {
                item(key = "hero") {
                    HeroBanner(
                        title = stringResource(R.string.overview_hero_title),
                        desc = stringResource(R.string.overview_hero_desc),
                        primaryLabel = stringResource(R.string.rd_hero_primary),
                        onPrimaryClick = onPasteLink,
                        secondaryLabel = stringResource(R.string.rd_hero_secondary),
                        onSecondaryClick = onOpenAutoTrack,
                        modifier = Modifier.enterReveal(1, enterGuard, OverviewEnterKeys.Hero)
                    )
                }
                if (recentSearches.isNotEmpty()) {
                    item(key = "recent") {
                        RecentSearchSection(
                            keywords = recentSearches,
                            onPick = { searchViewModel.research(it) },
                            modifier = Modifier.enterReveal(3, enterGuard, OverviewEnterKeys.Recent)
                        )
                    }
                }
                // 追价状态小卡：首屏就把"自动追价开着没/上次何时跑"交代清楚（点开进中心页）
                if (autoTrack != null) {
                    item(key = "autotrack_mini") {
                        Spacer(Modifier.height(Dims.SpacingM))
                        com.pricelens.ui.autotrack.AutoTrackMiniCard(
                            state = autoTrack,
                            onClick = onOpenAutoTrack,
                            modifier = Modifier.enterReveal(2, enterGuard, OverviewEnterKeys.AutoTrack)
                        )
                    }
                }
            } else {
                item(key = "empty_cause") {
                    val (titleRes, body) = when (emptyCause) {
                        EmptyStateCause.UNREACHABLE ->
                            R.string.search_empty_title_unreachable to
                                stringResource(R.string.search_unreachable_result, keyword)
                        else ->
                            R.string.search_empty_title_no_match to
                                stringResource(R.string.search_no_relevant_result, keyword)
                    }
                    Spacer(Modifier.height(Dims.SpacingL))
                    EmptyState(
                        icon = if (emptyCause == EmptyStateCause.UNREACHABLE) {
                            Icons.Filled.Warning
                        } else {
                            Icons.Filled.Link
                        },
                        title = stringResource(titleRes),
                        desc = body,
                        modifier = Modifier.enterReveal(1, enterGuard, OverviewEnterKeys.EmptyCause)
                    )
                }
            }

            // 三条折叠引导降级为页尾次要入口（保留全部能力与原文案语义）
            item(key = "guides") {
                GuideSection(
                    modifier = Modifier.enterReveal(3, enterGuard, OverviewEnterKeys.Guides),
                    keyword = keyword,
                    searched = searched,
                    onGoBilibili = onGoBilibili
                )
            }
        } else {
            item(key = "source_board") {
                SourceStatusRow(
                    searchViewModel,
                    Modifier.enterReveal(0, enterGuard, OverviewEnterKeys.SourceBoard)
                )
            }
            // 数据源失败：友好提示，旧数据仍按 valueOrNull/valueOrDefault 兜底展示
            if (historyAsync is AsyncValue.Error<*> || couponsAsync is AsyncValue.Error<*>) {
                item(key = "error_hint") {
                    Spacer(Modifier.height(Dims.SpacingM))
                    EmptyState(
                        icon = Icons.Filled.Warning,
                        title = stringResource(R.string.error_load_failed),
                        desc = stringResource(R.string.error_retry_hint)
                    )
                }
            }
            item(key = "product") {
                Spacer(Modifier.height(Dims.SpacingM))
                ProductHeroCard(
                    product = product,
                    judgment = judgment,
                    history = history,
                    coupons = coupons,
                    livePrice = livePrice,
                    realtimeSource = realtimeSource,
                    netPrice = netPrice,
                    accuracy = accuracy,
                    buySignal = buySignal,
                    onReportAccuracyIssue = reportWithToast,
                    modifier = Modifier.enterReveal(1, enterGuard, OverviewEnterKeys.Product)
                )
            }
            // 各源报价：单行紧凑列表（密度重排后的头卡/报价/走势三件事同屏）
            item(key = "quotes") {
                CompactQuoteList(
                    groups = overviewQuotes(
                        outcomeOf = { searchViewModel.lastOutcome(it) },
                        product = product,
                        historyAsync = historyAsync,
                        history = history,
                        couponsAsync = couponsAsync,
                        coupons = coupons,
                        postsAsync = postsAsync,
                        posts = posts,
                        shihuoAsync = shihuoAsync,
                        shihuo = shihuoItems,
                        livePrice = livePrice,
                        realtimeSource = realtimeSource,
                        netPrice = netPrice
                    ),
                    modifier = Modifier.enterReveal(2, enterGuard, OverviewEnterKeys.Quotes)
                )
            }
            // 曲线保留，但降到 Dims.CurveCompact：详情大曲线在盯价页，概览只回答"最近是涨是跌"
            item(key = "curve") {
                CurveStripCard(
                    history = history,
                    modifier = Modifier.enterReveal(3, enterGuard, OverviewEnterKeys.Curve)
                )
            }
            // §十一 详情页入口：整行 tonal 按钮（这是导航动作，不该长得像一枚状态标签）
            item(key = "detail_entry") {
                Spacer(Modifier.height(Dims.SpacingM))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenProduct(product.title) }
                        .enterReveal(4, enterGuard, OverviewEnterKeys.DetailCta),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .heightIn(min = Dims.TouchMin)
                            .padding(horizontal = Dims.SpacingL, vertical = Dims.SpacingS)
                    ) {
                        Text(
                            stringResource(R.string.rd_result_detail_cta),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(Dims.IconInline)
                        )
                    }
                }
            }
            // 引导降级到页尾：有结果时首屏不该被教程占据
            item(key = "guides") {
                GuideSection(
                    modifier = Modifier.enterReveal(5, enterGuard, OverviewEnterKeys.Guides),
                    keyword = keyword,
                    searched = keyword.isNotBlank(),
                    onGoBilibili = onGoBilibili
                )
            }
        }
    }
}

/** 最近搜索（空态首屏用）：最近搜过的一步直达，不逼用户重新打字 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecentSearchSection(keywords: List<String>, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        SectionHeader(stringResource(R.string.rd_recent_title))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dims.SpacingS)) {
            keywords.take(8).forEach { kw ->
                AssistChip(onClick = { onPick(kw) }, label = { Text(kw, maxLines = 1) })
            }
        }
    }
}

/**
 * 页尾「更多入口」：保留原三条折叠引导（无障碍设置 / 粘贴链接说明 / B站评测）。
 * 大改造把它们的顺序与位置降到页尾，是因为首屏的主动权该给搜索与自动追价；
 * 但能力一条没删——展开后仍是原来的说明与动作。
 */
@Composable
private fun GuideSection(modifier: Modifier, keyword: String, searched: Boolean, onGoBilibili: () -> Unit) {
    val context = LocalContext.current
    Column(modifier.fillMaxWidth()) {
        SectionHeader(stringResource(R.string.rd_section_guides))
        CollapsibleGuide(
            icon = Icons.Filled.Accessibility,
            title = stringResource(R.string.overview_guide_acc_title),
            desc = stringResource(R.string.overview_guide_acc_desc),
            actionLabel = stringResource(R.string.overview_guide_acc_action),
            onAction = {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                )
            }
        )
        Spacer(Modifier.height(Dims.SpacingM))
        CollapsibleGuide(
            icon = Icons.Filled.Link,
            title = stringResource(R.string.overview_guide_link_title),
            desc = stringResource(R.string.overview_guide_link_desc)
        )
        Spacer(Modifier.height(Dims.SpacingM))
        CollapsibleGuide(
            icon = Icons.Filled.OndemandVideo,
            title = stringResource(R.string.overview_guide_bili_title),
            desc = if (searched) {
                stringResource(R.string.overview_guide_bili_desc, keyword)
            } else {
                stringResource(R.string.overview_guide_bili_desc_blank)
            },
            actionLabel = stringResource(R.string.overview_guide_bili_action),
            onAction = onGoBilibili
        )
    }
}

/**
 * 可折叠引导卡：默认折起（首屏密度），点标题行展开说明与动作。
 *
 * 动效（上一轮只做到"能折"、没做到"折得顺眼"）：
 *  - chevron：**绘制通道**旋转（animateFloatAsState + graphicsLayer(rotationZ)）；
 *  - 展开/收起：这里确实需要高度动画（纯 draw 通道的 scaleY 改不了布局，收起后会留空白），
 *    用 AnimatedVisibility + expandVertically/shrinkVertically，时长压在令牌上限内、缓动统一，无弹跳。
 *    与 Motion.kt 铁律的关系：铁律禁的是"用布局通道做常态动效"，这是用户显式点击的折叠，
 *    同仓库先例见 ui/components/PriceOverlay.kt 的展开面板。
 */
@Composable
private fun CollapsibleGuide(icon: ImageVector, title: String, desc: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    // chevron 角度只走 graphicsLayer：旋转不改 Icon 的测量尺寸，标题行高度恒定
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(MotionDurations.Fast, easing = PriceLensEasing),
        label = "guideChevron"
    )
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                // 触控下限：三张引导卡是页尾唯一的手动入口，按 Material 的 48dp 收口
                .heightIn(min = Dims.TouchMin)
                .padding(vertical = Dims.SpacingS)
        ) {
            // 引导卡图标收进 tonal 圆角方块（与 EmptyState 同一视觉语言）
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .padding(Dims.SpacingS)
                        .size(Dims.IconInline)
                )
            }
            Spacer(Modifier.size(Dims.SpacingS))
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = stringResource(
                    if (expanded) R.string.overview_guide_collapse else R.string.overview_guide_expand
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.graphicsLayer { rotationZ = chevron }
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween<Float>(MotionDurations.Standard, easing = PriceLensEasing)) +
                expandVertically(tween<IntSize>(MotionDurations.Standard, easing = PriceLensEasing), expandFrom = Alignment.Top),
            exit = fadeOut(tween<Float>(MotionDurations.Fast, easing = PriceLensEasing)) +
                shrinkVertically(tween<IntSize>(MotionDurations.Fast, easing = PriceLensEasing), shrinkTowards = Alignment.Top)
        ) {
            // F9（2026-10-07 真机截图复核）：展开区只留「说明 + 动作」，不再画一遍图标与标题——
            // 否则刚点的那句标题会在下面出现复制品，看着像两个东西、其实是一个。
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = Dims.IconInline + Dims.SpacingS * 3,
                        top = Dims.SpacingXS,
                        bottom = Dims.SpacingS
                    )
            ) {
                Text(
                    desc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (actionLabel != null && onAction != null) {
                    TextButton(
                        onClick = onAction,
                        modifier = Modifier.heightIn(min = Dims.TouchMin)
                    ) { Text(actionLabel) }
                }
            }
        }
    }
}

/**
 * 结果英雄卡（大改造）：超大价格 + 价格口径胶囊 + 判定徽章在一个视线三角里。
 *
 * 排版决策：
 *  - 价格从 16sp 的 PriceInline 抬回 [PriceType.PriceHero]（32sp）：概览的职责就是回答
 *    「这件东西现在多少钱」，它是全屏最大的数字；
 *  - 口径胶囊紧跟价格右侧：同一个数字，说清楚是页面价 / 券后价 / 到手价；
 *  - 标题与建议徽章一行：标题最多两行，徽章不参与省略；
 *  - 图 88dp（与详情页同一尺寸，两个页面看到的商品图一样大）；
 *  - 准确性判定作为一整块嵌在卡内（[AccuracyBlock]），默认收起成一行。
 */
@Composable
private fun ProductHeroCard(
    product: JdApi.JdProduct,
    judgment: PriceJudgment,
    history: ManmanbuyApi.History?,
    coupons: List<GwdangApi.Coupon>,
    livePrice: Double?,
    realtimeSource: String?,
    netPrice: Double?,
    accuracy: AccuracyView?,
    buySignal: com.pricelens.domain.BuySignal?,
    onReportAccuracyIssue: (AccuracyIssueView) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    PriceCard(
        modifier = modifier.fillMaxWidth(),
        // 点卡片 → 优先唤起京东/淘宝 App，未安装回退浏览器
        onClick = { UrlOpener.open(context, product.url) }
    ) {
        Row(verticalAlignment = Alignment.Top) {
            AppImage(
                url = product.image,
                contentDescription = product.title,
                modifier = Modifier.size(88.dp)
            )
            Spacer(Modifier.size(Dims.SpacingM))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        product.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (history != null) {
                        Spacer(Modifier.size(Dims.SpacingS))
                        PriceBadge(
                            judgment.label,
                            when (judgment) {
                                is PriceJudgment.LOW -> BadgeTone.POSITIVE
                                is PriceJudgment.SUSPICIOUS -> BadgeTone.NEGATIVE
                                else -> BadgeTone.NEUTRAL
                            }
                        )
                    }
                }
                Spacer(Modifier.height(Dims.SpacingS))
                // 超大价格 + 口径胶囊：价格是主角，胶囊只解释"这个价是什么口径"
                Row(verticalAlignment = Alignment.Bottom) {
                    if (product.price > 0) {
                        Text(
                            PriceFormatter.format(product.price),
                            style = PriceType.PriceHero,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    } else {
                        // 京东公开查价通道不可达时不给 ¥0，宁可只说"拿不到"
                        Text(
                            stringResource(R.string.overview_price_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.size(Dims.SpacingS))
                    PriceBasisPill(
                        accuracy = accuracy,
                        hasNetPrice = netPrice != null,
                        modifier = Modifier.padding(bottom = Dims.SpacingXS)
                    )
                }
                Spacer(Modifier.height(Dims.SpacingXS))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    history?.let {
                        Text(
                            stringResource(R.string.layout_overview_header_lowest, PriceFormatter.format(it.lowest)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                    coupons.maxByOrNull { it.amount }?.let {
                        Spacer(Modifier.size(Dims.SpacingS))
                        Text(
                            stringResource(
                                R.string.layout_overview_header_coupon,
                                PriceFormatter.formatRaw(it.amount)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
                // 本机账号实时价（含会员价）：与网络报价并列展示，不互相冒充
                livePrice?.let { live ->
                    Spacer(Modifier.height(Dims.SpacingS))
                    PriceBadge(
                        stringResource(
                            R.string.overview_live_price,
                            realtimeSource ?: stringResource(R.string.overview_live_default_source),
                            PriceFormatter.formatRaw(live)
                        ),
                        BadgeTone.POSITIVE
                    )
                }
                // 买前防套信号（domain AutoTrackPolicy.buySignal）：假降价/单源待核价在这里先拦一道。
                // 版式：徽标单独一行（绝不和正文并排抢宽），正文下一行整宽、默认 2 行省略，
                // 点一下展开看全文——真机 2026-11 首轮实测：并排时正文被挤成 3~4 字/行。
                buySignal?.let { signal ->
                    Spacer(Modifier.height(Dims.SpacingM))
                    val tone = when (signal.kind) {
                        com.pricelens.domain.BuySignalKind.NEAR_HISTORY_LOW -> BadgeTone.POSITIVE
                        com.pricelens.domain.BuySignalKind.FAKE_DROP -> BadgeTone.NEGATIVE
                        else -> BadgeTone.NEUTRAL
                    }
                    var signalExpanded by rememberSaveable { mutableStateOf(false) }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { signalExpanded = !signalExpanded }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PriceBadge(signal.label, tone)
                            Spacer(Modifier.size(Dims.SpacingS))
                            Text(
                                stringResource(
                                    if (signalExpanded) R.string.rd_collapse_detail else R.string.rd_expand_detail
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(Dims.SpacingXS))
                        Text(
                            signal.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (signalExpanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        // 准确性判定：引擎没给就不摆（不写"置信未知"这种空话）
        if (accuracy != null) {
            Spacer(Modifier.height(Dims.SpacingM))
            AccuracyBlock(view = accuracy, onReportIssue = onReportAccuracyIssue)
        }
    }
}

/**
 * 价格口径胶囊：优先用准确性引擎给的 `basisLabel`（统一口径的唯一出处）；
 * 引擎未就绪时按本页已有的数据保守降级——只有确实算出了到手价才说「到手价」，
 * 否则说「页面价」，绝不把页面价冒充券后价。
 */
@Composable
private fun PriceBasisPill(accuracy: AccuracyView?, hasNetPrice: Boolean, modifier: Modifier = Modifier) {
    val label = accuracy?.basisLabel ?: stringResource(
        if (hasNetPrice) R.string.rd_basis_net else R.string.rd_basis_page
    )
    PriceBadge(label, BadgeTone.INFO, modifier)
}

/** 概览的「各源报价」取值层：把 6 路 AsyncValue + 域名诊断结果摊平成 [SourceObservation] */
@Composable
private fun overviewQuotes(
    outcomeOf: (String) -> CrawlerResult<String>?,
    product: JdApi.JdProduct,
    historyAsync: AsyncValue<ManmanbuyApi.History>,
    history: ManmanbuyApi.History?,
    couponsAsync: AsyncValue<List<GwdangApi.Coupon>>,
    coupons: List<GwdangApi.Coupon>,
    postsAsync: AsyncValue<List<SmzdmApi.SmzdmPost>>,
    posts: List<SmzdmApi.SmzdmPost>,
    shihuoAsync: AsyncValue<List<ShihuoApi.ShihuoItem>>,
    shihuo: List<ShihuoApi.ShihuoItem>,
    livePrice: Double?,
    realtimeSource: String?,
    netPrice: Double?
): List<QuoteGroup> {
    val labels = QuoteLabels(
        account = stringResource(R.string.layout_quote_account),
        main = stringResource(R.string.layout_quote_main),
        history = stringResource(R.string.layout_quote_history),
        coupon = stringResource(R.string.layout_quote_coupon),
        shihuo = stringResource(R.string.layout_quote_shihuo),
        post = stringResource(R.string.layout_quote_post),
        countWord = stringResource(R.string.layout_quote_count_word)
    )
    val observations = listOf(
        // 本机登录账号所见：决策权重最高，来源名进"备注"而不是源名（源名要短）
        SourceObservation(
            kind = QuoteKind.ACCOUNT,
            price = livePrice,
            state = if (livePrice != null) QuoteState.PRICED else QuoteState.IDLE,
            attribution = realtimeSource
        ),
        SourceObservation(QuoteKind.MAIN, product.price, QuoteState.PRICED),
        SourceObservation(
            kind = QuoteKind.HISTORY,
            price = history?.current,
            state = SourceQuotes.stateOf(historyAsync, outcomeOf("apapia-history.manmanbuy.com")),
            // 历史价唯一诚实的时间戳：最后一个采样日（yyyy-MM-dd），不是"刚刚"
            stamp = history?.points?.lastOrNull()?.date
        ),
        SourceObservation(
            kind = QuoteKind.COUPON,
            price = netPrice,
            state = if (netPrice != null) QuoteState.PRICED else SourceQuotes.stateOf(couponsAsync, outcomeOf("www.gwdang.com")),
            itemCount = coupons.size
        ),
        SourceObservation(
            kind = QuoteKind.SHIHUO,
            price = shihuo.minByOrNull { it.price }?.price,
            state = SourceQuotes.stateOf(shihuoAsync, outcomeOf("m.shihuo.cn")),
            itemCount = shihuo.size
        ),
        SourceObservation(
            kind = QuoteKind.POST,
            price = posts.mapNotNull { it.price }.minOrNull(),
            state = SourceQuotes.stateOf(postsAsync, outcomeOf("search.smzdm.com")),
            itemCount = posts.size
        )
    )
    return SourceQuotes.sections(SourceQuotes.of(QuoteInputs.of(labels, observations)))
}

/**
 * 概览页的入场键：必须与数据无关且稳定（拿数据当 key = 每次刷新重播一次入场）。
 * 各区块共用 index 阶梯，key 由这里统一登记。
 */
private object OverviewEnterKeys {
    const val SourceBoard = "overview:sourceBoard"
    const val Hero = "overview:hero"
    const val AutoTrack = "overview:autoTrack"
    const val Recent = "overview:recent"
    const val EmptyCause = "overview:emptyCause"
    const val Guides = "overview:guides"
    const val Product = "overview:product"
    const val Quotes = "overview:quotes"
    const val Curve = "overview:curve"
    const val DetailCta = "overview:detailCta"
}
