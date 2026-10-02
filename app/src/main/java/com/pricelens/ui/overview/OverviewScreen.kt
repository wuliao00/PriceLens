package com.pricelens.ui.overview

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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pricelens.R
import com.pricelens.data.remote.CrawlerResult
import com.pricelens.data.remote.GwdangApi
import com.pricelens.data.remote.JdApi
import com.pricelens.data.remote.ManmanbuyApi
import com.pricelens.data.remote.ShihuoApi
import com.pricelens.data.remote.SmzdmApi
import com.pricelens.ui.common.AsyncValue
import com.pricelens.ui.common.EmptyStateCause
import com.pricelens.ui.common.EmptyStateCauseOf
import com.pricelens.ui.common.valueOrDefault
import com.pricelens.ui.common.valueOrNull
import com.pricelens.ui.components.AppImage
import com.pricelens.ui.components.EmptyState
import com.pricelens.ui.components.PriceBadge
import com.pricelens.ui.components.PriceCard
import com.pricelens.ui.components.ShimmerList
import com.pricelens.ui.components.SourceStatusRow
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
 * §3.5 概览页：一屏内看到 当前价 / 历史最低价 / 是否有券 / 是否建议购买。
 * §2.5 列表固定高度 + 稳定结构，避免无效重组。
 *
 * 阶段4：顶部接 [SourceStatusRow] 展示各数据源真实状态；引导卡统一 [EmptyState]；
 * 数据源失败展示友好错误提示（旧数据仍兜底展示）。
 */
@Composable
fun OverviewScreen(searchViewModel: SearchViewModel, onGoBilibili: () -> Unit = {}) {
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
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Dims.SpacingXL)
    ) {
        item(key = "source_status") {
            SourceStatusRow(searchViewModel)
        }
        if (product == null) {
            // 空态有三种完全不同的成因（F4，2026-09-29）：
            //  ① 还没搜过；② 搜过、一个源都没够着（断网/被拦）；③ 搜过、够着了但没匹配上关键词。
            // ②从前落到③的文案上，用户被告知"关键词写错了"，而真正的问题是没连上网。
            // 判定抽成纯函数 EmptyStateCauseOf.of()（ui/common/EmptyStateCause.kt，可 JVM 单测），
            // 概览/社区/B站 三页共用同一规则，这里只做字符串映射。
            val searched = keyword.isNotBlank()
            val emptyCause = EmptyStateCauseOf.of(
                searched,
                // 只传"这一屏的商品候选真正依赖的源"：当当 → 值得买（两者合并进 posts 状态）→ 识货。
                // 不能顺手把 videosAsync / couponsAsync / historyAsync 也塞进来：B站、券、历史价是
                // 各自独立的通道，它们的 L1/L2 缓存命中时依然报 Success，于是"B站上一轮的缓存还在"
                // 会把"当当/值得买本轮根本没够着"判成 NO_MATCH —— 又渲染回
                // 「未匹配到与「关键词」直接相关的商品」那句谎话（F4 的同一类塌缩，只是换了层）。
                listOf(postsAsync, shihuoAsync)
            )
            item(key = "empty_title") {
                Spacer(Modifier.height(Dims.SpacingL))
                Text(
                    when (emptyCause) {
                        EmptyStateCause.NOT_SEARCHED -> stringResource(R.string.search_start_hint)
                        EmptyStateCause.UNREACHABLE ->
                            stringResource(R.string.search_unreachable_result, keyword)
                        EmptyStateCause.NO_MATCH ->
                            stringResource(R.string.search_no_relevant_result, keyword)
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 三张引导卡默认折叠成紧凑标题行：首屏留给"搜索/粘贴"核心路径（文档 UX 卡片偏高）
            item(key = "guide_acc") {
                val context = LocalContext.current
                Spacer(Modifier.height(Dims.SpacingL))
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
            }
            item(key = "guide_link") {
                Spacer(Modifier.height(Dims.SpacingL))
                CollapsibleGuide(
                    icon = Icons.Filled.Link,
                    title = stringResource(R.string.overview_guide_link_title),
                    desc = stringResource(R.string.overview_guide_link_desc)
                )
            }
            item(key = "guide_bili") {
                Spacer(Modifier.height(Dims.SpacingL))
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
        } else {
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
                Spacer(Modifier.height(Dims.SpacingS))
                ProductHeader(product, judgment, history, coupons)
            }
            // 各源报价：单行紧凑列表（原先这里是"历史最低 / 券 / 建议"三张高卡片，一屏放不下几件事）
            item(key = "quotes") {
                CompactQuoteList(
                    overviewQuotes(
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
                    )
                )
            }
            // 曲线保留，但降到 Dims.CurveCompact：详情大曲线在盯价页，概览只回答"最近是涨是跌"
            item(key = "curve") {
                CurveStripCard(history)
            }
        }
    }
}

/**
 * 可折叠引导卡：默认折起（首屏密度），点标题行展开原样的 [EmptyState] 说明。
 *
 * 动效（上一轮只做到"能折",没做到"折得顺眼"）：
 *  - chevron：**绘制通道**旋转。animateFloatAsState + graphicsLayer(rotationZ)，
 *    不触发重测重排，完全符合 Motion.kt 的铁律「仅 animateFloatAsState + graphicsLayer/drawBehind」。
 *  - 展开/收起：**这里确实需要高度动画**，用的是 AnimatedVisibility + expandVertically/shrinkVertically
 *    （+ fadeIn/fadeOut）。它与铁律的关系要写明白：
 *    铁律禁的是"用布局通道做常态动效"（每帧重测、和列表回收打架、易掉帧）；
 *    而折叠卡的目的恰恰是**不保留占位高度**——纯 draw 通道的 scaleY 只能改视觉、
 *    改不了布局，收起后仍会留一段空白，密度目标就废了。
 *    所以这里让布局参与，但把代价压到最小：
 *     ① 只在用户显式点击的三张卡上发生，不是自动播放；
 *     ② 时长取令牌上限内（进 Standard=250ms、退 Fast=150ms，≤350ms 铁律），缓动统一 PriceLensEasing，无弹跳；
 *     ③ 顶部对齐展开（expandFrom = Alignment.Top），视觉上像"从标题行长出来"，
 *        同时 expandVertically 默认 clip=true，动画期间内容走裁剪而非重排整屏；
 *     ④ 同仓库的既有例外先例：ui/components/PriceOverlay.kt 的展开面板就是同一组 API。
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
                .padding(vertical = Dims.SpacingS)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(Dims.IconInline)
            )
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
            EmptyState(
                icon = icon,
                title = title,
                desc = desc,
                actionLabel = actionLabel,
                onAction = onAction
            )
        }
    }
}

/**
 * 结果头卡（紧凑化）：图 + 标题（≤2 行）+ **一行**收纳 现价 / 历史最低 / 建议徽章。
 *
 * 与改动前的三处区别：
 *  - 缩略图从 80dp 降到 [Dims.ThumbHeader]（64dp，= 两行标题 + 一行现价的文本列高，图不再把行拉高）；
 *  - 金额从 32sp 的 PriceHero 降到 [PriceType.PriceInline]（16sp），原来一行的金额现在和
 *    "历史最低""建议徽章"同处一行，首屏少滚一屏；
 *  - 本机账号实时价不再是卡里的绿胶囊——它本身就是"某个源的报价"，交给下面的紧凑报价列表，
 *    与慢慢买/券后/识货/爆料同排同列，读者能横向比。
 */
@Composable
private fun ProductHeader(
    product: JdApi.JdProduct,
    judgment: PriceJudgment,
    history: ManmanbuyApi.History?,
    coupons: List<GwdangApi.Coupon>
) {
    val context = LocalContext.current
    PriceCard(
        modifier = Modifier.fillMaxWidth(),
        // 启动关联应用：点击商品卡 → 优先唤起京东/淘宝 App，未安装回退浏览器
        onClick = { UrlOpener.open(context, product.url) }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppImage(
                url = product.image,
                contentDescription = product.title,
                modifier = Modifier.size(Dims.ThumbHeader)
            )
            Spacer(Modifier.size(Dims.SpacingM))
            Column(Modifier.weight(1f)) {
                Text(
                    product.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(Dims.SpacingXS))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dims.SpacingS)) {
                    if (product.price > 0) {
                        // 2026-09：京东公开查价通道不可达时不给 ¥0，宁可只说"拿不到"
                        Text(
                            PriceFormatter.format(product.price),
                            style = PriceType.PriceInline,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text(
                            stringResource(R.string.overview_price_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
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
                    history?.let {
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
            }
        }
    }
}

/**
 * 概览的「各源报价」取值层：把 6 路 AsyncValue + 域名诊断结果摊平成 [SourceObservation]，
 * 规则（0 价不算价、IDLE 不占行、排序、分组、文案收敛）全在纯函数层
 * [QuoteInputs.of] / [SourceQuotes.of] / [SourceQuotes.sections]，那里有 JVM 单测。
 *
 * 域名键与顶部那排 [com.pricelens.ui.components.SourceStatusRow] 保持一致：
 * 同一个源在两处说话不能一个说"反爬"一个说"失败"。
 */
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
