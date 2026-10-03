package com.pricelens.ui.components

import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pricelens.R
import com.pricelens.accessibility.BALL_DIAMETER_DP
import com.pricelens.accessibility.BALL_H_PADDING_DP
import com.pricelens.accessibility.BALL_V_PADDING_DP
import com.pricelens.accessibility.CAPSULE_ICON_DP
import com.pricelens.accessibility.CAPSULE_MAX_HEIGHT_DP
import com.pricelens.accessibility.CHIP_GAP_DP
import com.pricelens.accessibility.CHIP_H_PADDING_DP
import com.pricelens.accessibility.CHIP_V_PADDING_DP
import com.pricelens.accessibility.COLLAPSE_BUTTON_CORNER_DP
import com.pricelens.accessibility.FORM_ENTER_OFFSET_DP
import com.pricelens.accessibility.OverlayMode
import com.pricelens.accessibility.PriceBasis
import com.pricelens.accessibility.PriceEvents
import com.pricelens.accessibility.ROW_GAP_DP
import com.pricelens.accessibility.ShopPlatform
import com.pricelens.accessibility.WINDOW_GUTTER_DP
import com.pricelens.accessibility.ballLabel
import com.pricelens.accessibility.capsuleBandDp
import com.pricelens.accessibility.capsuleCornerDp
import com.pricelens.accessibility.capsuleMaxWidthDp
import com.pricelens.accessibility.isDrag
import com.pricelens.accessibility.panelContentMaxWidthDp
import com.pricelens.data.local.entity.WatchIdentityEntity
import com.pricelens.data.remote.ManmanbuyApi
import com.pricelens.data.repository.OverlayBundle
import com.pricelens.domain.OverlayIdentityPolicy
import com.pricelens.ui.theme.Dims
import com.pricelens.ui.theme.Elevations
import com.pricelens.ui.theme.MotionDurations
import com.pricelens.ui.theme.PriceLensEasing
import com.pricelens.util.PriceFormatter
import com.pricelens.util.TimeAgo
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.launch

/** 球面透明度：只有"画"这一层半透明，`LayoutParams.alpha` 仍保持 1.0（见 OverlayManager 文件头第 4 条）。
 * 球窗口尺寸贴球，不存在"穿越到下层的触摸"，所以这层 alpha 不触发 Android 12+ Untrusted touch。 */
private const val BALL_SURFACE_ALPHA = 0.9f

/**
 * §1.5 比价浮窗（A2 重写）：默认**折叠胶囊条**（宽≤屏宽40%、高≤48dp、右上，不遮商品价与购买按钮），
 * 点按展开 5 行面板；缺什么隐什么、不留空占位：
 *  ① 当前价 + **口径标签**（到手价/券后价/页面价，裸数字不冒充到手价）；
 *  ② 历史位置一句（仅确定性商品 ID 命中时出现——防错配硬规则）；
 *  ③ 多平台同款胶囊（同上，仅确定性 ID；另起一行标"不同店铺/规格，仅供参考"）；
 *  ④ 券（门槛 0 显示"无门槛"；TITLE_ONLY 时带仅供参考标）；
 *  ⑤ 底部灰字"来源 慢慢买 · 3 小时前 · 非实时"——数据时间与来源是必须项。
 *
 * 两态切换/拖动全部是 Composable 内部 UI 态（不放全局 object）；
 * 拖动用 pointerInput(detectDragGestures) 回调 [onDrag]（旧版在根 ComposeView 上
 * setOnTouchListener，与内部 AndroidComposeView 抢 ACTION_DOWN，拖动与点击不可兼得）。
 * 面板/胶囊不透明：窗口 alpha 保持 1.0、不加 FLAG_NOT_TOUCHABLE，规避 Android 12+
 * Untrusted touch（半透明遮挡会丢弃穿越到下层的触摸）。
 *
 * PL-29 形态（面板 ↔ 小圆球）：
 *  - 胶囊条上**常驻**一个「收起」动作（不加设置开关）：面板以 scale + fade（250ms、
 *    [PriceLensEasing]，禁 bounce/overshoot）收进圆球；动画跑完才回调 [onCollapseWindow]
 *    让 OverlayManager 把窗口收缩成球径 —— 先缩窗口会把动画裁掉。
 *  - 球：直径 [BALL_DIAMETER_DP] dp、[Elevations.Overlay] 高度、只显极简价格
 *    （现价 + 能算出来时的 ↑/↓ 百分数，见 [ballLabel]）；点按展开回面板（同一转场反向），
 *    拖动松手吸附最近的左/右边（吸附与边界判定全在纯函数里，见 `BallGeometry.kt`）。
 *  - 点按还是拖动由 `ViewConfiguration.scaledTouchSlop` 判，见 [isDrag]（阈值不写死）。
 *  - 球上没有关闭入口：离开商详页由服务收窗；要关面板就展开后点 X。
 *
 * [identityLowest] = 已确认身份的 `ovl:` 本机曲线最低日点（null = 还没有可用点）；
 * M7 它是这条窄接口的唯一消费者——库里积了点却读不回 UI，等于把用户"白看了"。
 */
@Composable
fun PriceOverlay(
    detected: PriceEvents.Detected,
    bundle: OverlayBundle?,
    identity: WatchIdentityEntity?,
    identityDays: Int,
    identityLowest: Double?,
    mode: OverlayMode,
    onDrag: (Float, Float) -> Unit,
    onBallDragStart: () -> Unit,
    onBallDrag: (Float, Float) -> Unit,
    onBallDragEnd: () -> Unit,
    onCollapseWindow: () -> Unit,
    onExpandWindow: () -> Unit,
    onToggleExpanded: (Boolean) -> Unit,
    onCompare: () -> Unit,
    onConfirmIdentity: () -> Unit,
    onCancelIdentity: () -> Unit,
    onDismiss: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy * 0.8f,
            stiffness = Spring.StiffnessMedium
        ),
        label = "overlayEnter"
    )

    // 形态：`mode` 是 OverlayManager 的权威态（它决定窗口尺寸），`shown` 是屏幕上正在播的那一态。
    // 两者只在那 250ms 里不一致：旧形态先 scale+fade 收拢到看不见，才让 manager 换窗口尺寸，
    // 新形态再从同一条曲线里长出来 —— 反过来（先缩窗口）动画会被窗口边裁掉。
    var shown by remember(detected.signature) { mutableStateOf(mode) }
    val form = remember { Animatable(1f) }
    val switchScope = rememberCoroutineScope()
    LaunchedEffect(mode) {
        // 权威态被外部直接改写（hide()/下一次 show() 复位）而屏幕还停在旧形态：不等动画，立刻跟上
        if (shown != mode) {
            shown = mode
            form.snapTo(1f)
        }
    }

    val touchSlopPx = ViewConfiguration.get(LocalView.current.context).scaledTouchSlop

    // 一次转场：旧形态 scale+fade 收拢 → 让 manager 换窗口尺寸/坐标 → 新形态同一条曲线长回来。
    // 收拢与展开各 250ms（MotionDurations.Standard），缓动只有 PriceLensEasing（§2 铁律禁 bounce）。
    val switchForm: (OverlayMode, () -> Unit) -> Unit = { target, commit ->
        switchScope.launch {
            form.animateTo(0f, tween(MotionDurations.Standard, easing = PriceLensEasing))
            // 先换内容、再换窗口尺寸。反过来会留一帧"面板内容装在球大的窗口里"，
            // 面板首行文字被裁成一条露在球旁边（真机看到的就是这条残留文案）
            shown = target
            commit()
            form.animateTo(1f, tween(MotionDurations.Standard, easing = PriceLensEasing))
        }
    }

    val basisLabel = stringResource(
        when (detected.priceBasis) {
            PriceBasis.NET -> R.string.ovl_basis_net
            PriceBasis.AFTER_COUPON -> R.string.ovl_basis_coupon
            PriceBasis.PAGE -> R.string.ovl_basis_page
        }
    )
    val ownPlatform = stringResource(
        when (detected.platform) {
            ShopPlatform.JD -> R.string.ovl_platform_jd
            ShopPlatform.TAOBAO -> R.string.ovl_platform_taobao
            ShopPlatform.PDD -> R.string.ovl_platform_pdd
            ShopPlatform.UNKNOWN -> R.string.ovl_source_unknown
        }
    )

    // 展开态属于浮窗 UI，挂在 composable 内部；换商品（签名变化）或形态换了一轮都回到折叠胶囊
    var expanded by remember(detected.signature, shown) { mutableStateOf(false) }

    Box(
        modifier = Modifier.graphicsLayer {
            // 入场（progress）与形态转场（form）都只走绘制通道，不改布局尺寸
            val v = form.value
            alpha = progress * v
            val scale = 0.45f + 0.55f * v
            scaleX = scale
            scaleY = scale
            translationY = (1f - progress) * FORM_ENTER_OFFSET_DP.dp.toPx()
        }
    ) {
        when (shown) {
            OverlayMode.Panel -> PanelForm(
                detected = detected,
                bundle = bundle,
                identity = identity,
                identityDays = identityDays,
                identityLowest = identityLowest,
                basisLabel = basisLabel,
                ownPlatform = ownPlatform,
                expanded = expanded,
                onToggleExpanded = {
                    expanded = it
                    onToggleExpanded(it)
                },
                onCollapse = { switchForm(OverlayMode.Ball, onCollapseWindow) },
                onDrag = onDrag,
                onCompare = onCompare,
                onConfirmIdentity = onConfirmIdentity,
                onCancelIdentity = onCancelIdentity,
                onDismiss = onDismiss
            )

            OverlayMode.Ball -> BallForm(
                label = ballLabel(detected.price, ballReferenceLowest(detected, bundle, identity, identityLowest)),
                touchSlopPx = touchSlopPx,
                onDragStart = onBallDragStart,
                onDrag = onBallDrag,
                onDragEnd = onBallDragEnd,
                onTap = { switchForm(OverlayMode.Panel, onExpandWindow) }
            )
        }
    }
}

/**
 * 球的参考低价（↑/↓ 的分母）：与面板第②行/已确认行**同一取数口径**，不许在球上放宽 ——
 * 确定性商品 ID 命中才用慢慢买 90 天窗口内的最低，否则只用用户确认过身份的本机最低。
 */
private fun ballReferenceLowest(
    detected: PriceEvents.Detected,
    bundle: OverlayBundle?,
    identity: WatchIdentityEntity?,
    identityLowest: Double?
): Double? = if (detected.itemId != null) {
    lowestWithinDays(bundle?.history, 90)?.price
} else {
    if (identity != null) identityLowest else null
}

/** 折叠胶囊 + 可选的 5 行面板（PL-29 之前 PriceOverlay 的全部内容，原样搬进来） */
@Composable
private fun PanelForm(
    detected: PriceEvents.Detected,
    bundle: OverlayBundle?,
    identity: WatchIdentityEntity?,
    identityDays: Int,
    identityLowest: Double?,
    basisLabel: String,
    ownPlatform: String,
    expanded: Boolean,
    onToggleExpanded: (Boolean) -> Unit,
    onCollapse: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onCompare: () -> Unit,
    onConfirmIdentity: () -> Unit,
    onCancelIdentity: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            // 命中区必须铺满整块窗口：WindowManager 给本窗口的可触摸范围是整个 frame
            // （折叠态实测 mFrame=[544,284][1048,500]），而可见胶囊只有 Surface 那一圈
            // （[580,320][1012,464]）。手势只挂 Surface 时，外面这 12dp 阴影留白被窗口
            // 吃掉却没有任何处理者——真机表现就是「点胶囊没反应」（点 y=295 正落在这圈里）。
            // 手势必须在 padding 之前，节点尺寸才含这 12dp。
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    onDrag(drag.x, drag.y)
                }
            }
            .pointerInput(Unit) {
                val capsuleBandPx = capsuleBandDp().dp.toPx()
                detectTapGestures(onTap = { offset ->
                    // 只认胶囊那一条（含 12dp 外圈）；面板区域的点击仍归面板内控件
                    if (offset.y <= capsuleBandPx) onToggleExpanded(!expanded)
                })
            }
            .padding(WINDOW_GUTTER_DP.dp)
    ) {
        // ---------- 折叠胶囊（常驻，无 15s 自动消失；手动 X 关闭，「收起」变小球） ----------
        val viewportWidthDp = LocalConfiguration.current.screenWidthDp
        Surface(
            modifier = Modifier
                .widthIn(max = capsuleMaxWidthDp(viewportWidthDp).dp)
                .heightIn(max = CAPSULE_MAX_HEIGHT_DP.dp),
            shape = RoundedCornerShape(capsuleCornerDp().dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = Elevations.OverlayTonal,
            shadowElevation = Elevations.OverlayCapsule
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(
                    start = Dims.SpacingM,
                    end = Dims.SpacingXS,
                    top = ROW_GAP_DP.dp,
                    bottom = ROW_GAP_DP.dp
                )
            ) {
                Text(
                    text = "$basisLabel ${PriceFormatter.format(detected.price)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(CHIP_GAP_DP.dp))
                // PL-29 收起入口：始终在这一条里（展开/折叠都在），不新增设置开关
                TextButton(
                    onClick = onCollapse,
                    modifier = Modifier.minimumInteractiveComponentSize(),
                    contentPadding = PaddingValues(horizontal = CHIP_H_PADDING_DP.dp, vertical = 0.dp),
                    shape = RoundedCornerShape(COLLAPSE_BUTTON_CORNER_DP.dp)
                ) {
                    Text(stringResource(R.string.ovl_ball_collapse), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                IconButton(onClick = onDismiss, modifier = Modifier.minimumInteractiveComponentSize()) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.ovl_cd_close),
                        modifier = Modifier.size(CAPSULE_ICON_DP.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ---------- 展开 5 行面板：缺数据的行整行不出现 ----------
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(MotionDurations.Standard)) + expandVertically(tween(MotionDurations.Standard)),
            exit = fadeOut(tween(MotionDurations.Standard)) + shrinkVertically(tween(MotionDurations.Standard))
        ) {
            Surface(
                modifier = Modifier
                    .padding(top = ROW_GAP_DP.dp)
                    .widthIn(max = panelContentMaxWidthDp(viewportWidthDp).dp),
                shape = RoundedCornerShape(Dims.CardCorner),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = Elevations.OverlayTonal,
                shadowElevation = Elevations.OverlayPanel
            ) {
                Column(modifier = Modifier.padding(Dims.SpacingM)) {
                    // ① 口径 + 价格 + 标题
                    Text(
                        text = "$basisLabel ${PriceFormatter.format(detected.price)}",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = detected.title ?: stringResource(R.string.ovl_title_fallback),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(ROW_GAP_DP.dp))

                    // ② 历史位置（防错配硬规则：仅确定性 ID 才允许出现）
                    val low = if (detected.itemId != null) lowestWithinDays(bundle?.history, 90) else null
                    val line = historyLineFor(low, detected.price)
                    if (low != null && line != HistoryLine.NONE) {
                        val pct = ((detected.price - low.price) / low.price * 100).roundToInt()
                        Text(
                            text = if (line == HistoryLine.WINDOW_NEAR_LOW || line == HistoryLine.OLDER_NEAR_LOW) {
                                stringResource(historyLineStringRes(line), PriceFormatter.format(low.price))
                            } else {
                                stringResource(historyLineStringRes(line), PriceFormatter.format(low.price), pct)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(Dims.SpacingXS))
                    }

                    // ③ 多平台同款 3 枚胶囊（同上，仅确定性 ID）
                    val platforms = if (detected.itemId != null) bundle?.platforms.orEmpty() else emptyList()
                    if (platforms.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.ovl_platforms_note),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
                            modifier = Modifier.padding(top = CHIP_GAP_DP.dp)
                        ) {
                            OverlayChip("$ownPlatform ${PriceFormatter.format(detected.price)}", MaterialTheme.colorScheme.primary)
                            platforms.take(2).forEach {
                                OverlayChip("${it.platform} ${PriceFormatter.format(it.price)}", MaterialTheme.colorScheme.tertiary)
                            }
                        }
                        Spacer(Modifier.height(ROW_GAP_DP.dp))
                    }

                    // ④ 券（门槛 0 = "无门槛"；TITLE_ONLY 命中时同样标仅供参考）
                    val coupons = bundle?.coupons.orEmpty()
                    if (coupons.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp)
                        ) {
                            coupons.take(2).forEach { c ->
                                val cond = if (c.threshold <= 0) {
                                    stringResource(R.string.ovl_coupon_no_threshold)
                                } else {
                                    stringResource(R.string.ovl_coupon_threshold, c.threshold.toInt())
                                }
                                OverlayChip(
                                    stringResource(R.string.ovl_coupon_chip, c.amount.toInt(), cond),
                                    MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        if (detected.itemId == null) {
                            Text(
                                text = stringResource(R.string.ovl_platforms_note),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(top = CHIP_GAP_DP.dp)
                            )
                        }
                        Spacer(Modifier.height(ROW_GAP_DP.dp))
                    }

                    // ⑤ 底部灰字：来源 + 数据时间 + 非实时（必须项；stale 再补一行降级提示）
                    // 数据时间取自 bundle.fetchedAtMs（= 本次最老那份数据的抓取时刻）；
                    // 时刻不可知时明说"未知"，绝不拿打包时刻冒充"刚刚"（F3 缺陷二）。
                    val b = bundle
                    val fetchedAt = b?.fetchedAtMs
                    Text(
                        text = when {
                            b == null -> stringResource(
                                R.string.ovl_footer_live,
                                detected.sourceText ?: stringResource(R.string.ovl_source_unknown)
                            )

                            fetchedAt != null -> stringResource(
                                R.string.ovl_footer_bundle,
                                b.sourceLabel ?: "-",
                                TimeAgo.format(fetchedAt)
                            )

                            else -> stringResource(R.string.ovl_footer_bundle_no_time, b.sourceLabel ?: "-")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    if (b?.stale == true) {
                        Text(
                            text = stringResource(R.string.ovl_bundle_stale),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    if (b != null && detected.itemId == null) {
                        // TITLE_ONLY：明说不显示历史价/多平台比价（防错配硬规则）
                        Text(
                            text = stringResource(R.string.ovl_title_only_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Spacer(Modifier.height(Dims.SpacingS))

                    // CTA：确定性 ID → 查历史价；仅标题命中 → 降级为"在 App 内搜索"
                    when (overlayActionFor(detected, identity, OverlayIdentityPolicy.canConfirm(detected.title, detected.platform))) {
                        OverlayAction.VIEW_HISTORY -> PrimaryCta(stringResource(R.string.overlay_cta), onCompare)
                        OverlayAction.COMPARE_ONLY -> PrimaryCta(stringResource(R.string.ovl_title_only_cta), onCompare)
                        OverlayAction.CONFIRM_AND_COMPARE -> Row(horizontalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp)) {
                            OutlinedButton(
                                onClick = onCompare,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(Dims.ButtonCorner)
                            ) {
                                Text(stringResource(R.string.ovl_title_only_cta), style = MaterialTheme.typography.labelSmall, maxLines = 2)
                            }
                            Button(
                                onClick = onConfirmIdentity,
                                modifier = Modifier.weight(1.4f),
                                shape = RoundedCornerShape(Dims.ButtonCorner)
                            ) {
                                Text(stringResource(R.string.ovl_confirm_cta), style = MaterialTheme.typography.labelSmall, maxLines = 2)
                            }
                        }
                        OverlayAction.CONFIRMED -> Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    // 有可用日点才说"本机最低"，没有就退化到只报天数（绝不把 0 或空白当价格）
                                    text = if (identityLowest != null && identityLowest > 0.0) {
                                        stringResource(
                                            R.string.ovl_confirmed_line,
                                            identityDays,
                                            PriceFormatter.format(identityLowest)
                                        )
                                    } else {
                                        stringResource(R.string.ovl_confirmed_line_days, identityDays)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = onCancelIdentity) {
                                    Text(stringResource(R.string.ovl_confirmed_cancel), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            OutlinedButton(
                                onClick = onCompare,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(Dims.ButtonCorner)
                            ) {
                                Text(stringResource(R.string.ovl_title_only_cta), style = MaterialTheme.typography.labelSmall, maxLines = 2)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 小圆球：直径 [BALL_DIAMETER_DP] dp，只显 [ballLabel] 那两行（现价 + 可选差值标记）。
 *
 * 手势只有**一个** DOWN→MOVE→UP 状态机（[pointerInteropFilter] 里那一个 when）：
 * 按下累计屏幕位移，**超过** touch slop 才认定是拖动并开始搬窗口（[onDragStart] + [onDrag]），
 * 松手吸附（[onDragEnd]）；没超过就当点按展开回面板（[onTap]）。
 * 拆成"拖动一个手势 + 点击一个手势"是两个节点各自判定同一根手指，
 * 真机上就是"一动就误判成点击"（面板那两条的历史问题，这里不再犯）。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun BallForm(
    label: String,
    touchSlopPx: Int,
    onDragStart: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onTap: () -> Unit
) {
    val noPrice = stringResource(R.string.ovl_ball_no_price)
    val lines = label.split('\n')
    val priceLine = lines.firstOrNull().orEmpty().ifEmpty { noPrice }
    val ballCd = stringResource(R.string.ovl_ball_cd_expand, label.replace('\n', ' ').ifEmpty { noPrice })
    // 一次拖动的临时状态。用普通类而不是 mutableStateOf：这些值每帧都变，
    // 但没有任何组合期代码读它们 —— 做成 State 只会白白重组整棵浮窗树。
    val drag = remember { BallDragState() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = ballCd }
            // 这里**不画** translationX/Y：球形态下窗口就只有球径大小（为了让球不吃商品页的
            // 触摸），位移画到窗口边界外会被窗口表面裁掉 —— 球最多离开窗口半个身位，手指继续
            // 走它就"停在那儿"。这才是用户"球还是不跟手"的真因；上一版归因成"窗口内坐标被
            // 窗口位移抵消"是判错了方向。现在每帧把窗口搬过去（[onDrag] → dragBallBy）。
            //
            // 为什么用 pointerInteropFilter 而不是 pointerInput：只有 MotionEvent 在
            // ACTION_DOWN 上就给得到**屏幕绝对坐标** rawX/rawY。PointerInputChange 没有
            // rawPosition（1.7.6 的 jar 里 javap 可查，rawPosition 挂在 PointerEvent 那一侧），
            // 而 Compose 的 awaitFirstDown 已经把按下那一帧消费掉了，事后拿不到它的屏幕坐标
            // —— 用"第一帧 move"当锚点会把 down→move 那几像素从窗口位移里漏掉，
            // 球就会固定落后手指一截。
            // 它挂在球这个节点上、走 Compose 自己的命中测试，与当年"根 ComposeView 上
            // setOnTouchListener 和 AndroidComposeView 抢 ACTION_DOWN"不是一回事。
            .pointerInteropFilter { event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        drag.downX = event.rawX
                        drag.downY = event.rawY
                        drag.dragged = false
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - drag.downX
                        val dy = event.rawY - drag.downY
                        if (!drag.dragged && isDrag(sqrt(dx * dx + dy * dy), touchSlopPx)) {
                            drag.dragged = true
                            onDragStart()
                        }
                        // 越过阈值这一帧就把**同一份**总位移交出去：起步不掉帧，
                        // 也不会把 slop 那几像素从窗口位移里漏掉
                        if (drag.dragged) onDrag(dx, dy)
                        true
                    }

                    MotionEvent.ACTION_UP -> {
                        if (drag.dragged) onDragEnd() else onTap()
                        true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        if (drag.dragged) onDragEnd()
                        true
                    }

                    else -> false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.size(BALL_DIAMETER_DP.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = BALL_SURFACE_ALPHA),
            contentColor = MaterialTheme.colorScheme.onPrimary,
            tonalElevation = Elevations.OverlayTonal,
            shadowElevation = Elevations.Overlay
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = BALL_H_PADDING_DP.dp, vertical = BALL_V_PADDING_DP.dp)
            ) {
                Text(
                    text = priceLine,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (lines.size > 1) {
                    Text(
                        text = lines[1],
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * 一次球拖动的状态：按下那一刻的**屏幕**坐标 + 是否已越过 touch slop。
 *
 * 刻意不是 Compose State：这三个值只在 touch 回调里读写，没有任何组合期代码读它们。
 * 每帧一次 updateViewLayout 已经够贵了，别再为它重组浮窗树。
 */
private class BallDragState {
    var downX = 0f
    var downY = 0f
    var dragged = false
}

@Composable
private fun OverlayChip(text: String, tone: Color) {
    Surface(
        shape = RoundedCornerShape(Dims.ChipCorner),
        color = tone.copy(alpha = 0.12f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = tone,
            modifier = Modifier.padding(horizontal = Dims.SpacingS, vertical = CHIP_V_PADDING_DP.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 历史最低行的取数口径：这个最低价是从哪个池子里算出来的 */
internal enum class LowPriceScope {
    /** N 天窗口内有曲线点 —— 可以宣称"N 天最低" */
    WITHIN_DAYS,

    /** 窗口内一个点都没有，值取自更早的曲线 —— 不许再出现窗口天数断言 */
    OLDER_ONLY
}

/** 浮窗"历史最低"行的一枚数据：值 + 取数口径（口径必须是返回值的一部分，不能被 ifEmpty 吞掉） */
internal data class LowPrice(val price: Double, val scope: LowPriceScope)

/** 历史最低行要落哪条文案（NONE = 这一行不出） */
internal enum class HistoryLine { NONE, WINDOW_HIGH, WINDOW_NEAR_LOW, OLDER_HIGH, OLDER_NEAR_LOW }

/**
 * N 天窗口内的最低价 + 它的取数口径。曲线点 date 为 yyyy-MM-dd（ISO 字典序=时间序），直接字符串比较裁剪。
 *
 * 返回 [LowPrice] 而不是裸 Double：窗口内没有点时，min 取的是**更早的历史曲线**，
 * 此时调用方必须换成"90 天窗口外"口径的文案（F3 缺陷一）。
 * 完全没有可用点（无曲线 / 点全为空 / 最低值 ≤0）→ null，那一行整行不出。
 */
internal fun lowestWithinDays(history: ManmanbuyApi.History?, days: Int, today: LocalDate = LocalDate.now()): LowPrice? {
    if (history == null || history.points.isEmpty()) return null
    val cutoff = today.minusDays(days.toLong()).toString()
    val windowed = history.points.filter { it.date >= cutoff }
    // 窗口内没点时仍用全量点算 min（曲线可能整体都比窗口老），
    // 但口径必须如实标成 OLDER_ONLY —— 那个数不是"90 天内最低"（F3 缺陷一）
    val scope = if (windowed.isEmpty()) LowPriceScope.OLDER_ONLY else LowPriceScope.WITHIN_DAYS
    val pool = windowed.ifEmpty { history.points }
    val price = pool.minOf { it.price }.takeIf { it > 0.0 } ?: return null
    return LowPrice(price, scope)
}

/**
 * 历史最低行的文案选择（纯函数）：窗口口径 × 当前价是否高于最低价。
 * 与 [historyLineStringRes] 一起放在 Compose 之外，JVM 层即可断言"窗口外老数据绝不会拿到 90 天文案"。
 */
internal fun historyLineFor(low: LowPrice?, currentPrice: Double): HistoryLine {
    if (low == null || low.price <= 0.0) return HistoryLine.NONE
    val aboveLow = currentPrice > low.price
    return when {
        low.scope == LowPriceScope.OLDER_ONLY && aboveLow -> HistoryLine.OLDER_HIGH
        low.scope == LowPriceScope.OLDER_ONLY -> HistoryLine.OLDER_NEAR_LOW
        aboveLow -> HistoryLine.WINDOW_HIGH
        else -> HistoryLine.WINDOW_NEAR_LOW
    }
}

/** [HistoryLine] → 浮窗文案资源（"90 天最低"仅允许出现在 WINDOW_* 两条） */
@StringRes
internal fun historyLineStringRes(line: HistoryLine): Int = when (line) {
    HistoryLine.WINDOW_HIGH -> R.string.ovl_history_high
    HistoryLine.WINDOW_NEAR_LOW -> R.string.ovl_history_near_low
    HistoryLine.OLDER_HIGH -> R.string.ovl_history_high_older
    HistoryLine.OLDER_NEAR_LOW -> R.string.ovl_history_near_low_older
    HistoryLine.NONE -> R.string.ovl_history_near_low // 不被消费：NONE 时整行不渲染
}

/** 主 CTA（蓝底白字整行按钮）：文案由调用方决定，行为统一是 [onCompare] */
@Composable
private fun PrimaryCta(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dims.ButtonCorner)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * CTA 区应出现哪组按钮（纯函数，分支判定不许写在 Composable 嵌套里，单测直接钉）：
 *  - VIEW_HISTORY：拿到确定性 ID → 只有"查历史价"，问"是不是这个商品"是噪声；
 *  - CONFIRMED：无 ID 但用户确认过本机身份 → 灰字"已记 N 天"+ 取消；
 *  - CONFIRM_AND_COMPARE：无 ID 且素材够格（有标题、平台已知）→ 双按钮，确认是主行动；
 *  - COMPARE_ONLY：连确认素材都没有 → 保持旧降级行为（App 内搜索）。
 */
internal enum class OverlayAction { VIEW_HISTORY, COMPARE_ONLY, CONFIRM_AND_COMPARE, CONFIRMED }

internal fun overlayActionFor(detected: PriceEvents.Detected, identity: Any?, canConfirm: Boolean): OverlayAction = when {
    detected.itemId != null -> OverlayAction.VIEW_HISTORY
    identity != null -> OverlayAction.CONFIRMED
    canConfirm -> OverlayAction.CONFIRM_AND_COMPARE
    else -> OverlayAction.COMPARE_ONLY
}
