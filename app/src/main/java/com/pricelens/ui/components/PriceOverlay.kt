package com.pricelens.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pricelens.R
import com.pricelens.accessibility.PriceBasis
import com.pricelens.accessibility.PriceEvents
import com.pricelens.accessibility.ShopPlatform
import com.pricelens.data.remote.ManmanbuyApi
import com.pricelens.data.repository.OverlayBundle
import com.pricelens.ui.theme.Dims
import com.pricelens.util.PriceFormatter
import com.pricelens.util.TimeAgo
import java.time.LocalDate
import kotlin.math.roundToInt

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
 */
@Composable
fun PriceOverlay(
    detected: PriceEvents.Detected,
    bundle: OverlayBundle?,
    onDrag: (Float, Float) -> Unit,
    onToggleExpanded: (Boolean) -> Unit,
    onCompare: () -> Unit,
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

    // 展开态属于浮窗 UI，挂在 composable 内部；换商品（签名变化）自动回到折叠态
    var expanded by remember(detected.signature) { mutableStateOf(false) }

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

    Column(
        modifier = Modifier
            .padding(12.dp)
            .graphicsLayer {
                alpha = progress
                translationY = (1f - progress) * 12.dp.toPx()
            }
    ) {
        // ---------- 折叠胶囊（常驻，无 15s 自动消失；手动 X 关闭） ----------
        val capsuleMaxWidth = (LocalConfiguration.current.screenWidthDp * 0.4f).dp
        Surface(
            modifier = Modifier
                .widthIn(max = capsuleMaxWidth)
                .heightIn(max = 48.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        onDrag(drag.x, drag.y)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        expanded = !expanded
                        onToggleExpanded(expanded)
                    })
                },
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            shadowElevation = 6.dp
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
            ) {
                Text(
                    text = "$basisLabel ${PriceFormatter.format(detected.price)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(2.dp))
                IconButton(onClick = onDismiss, modifier = Modifier.size(22.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.ovl_cd_close),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ---------- 展开 5 行面板：缺数据的行整行不出现 ----------
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Surface(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .widthIn(max = 280.dp),
                shape = RoundedCornerShape(Dims.CardCorner),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
                shadowElevation = 8.dp
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
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
                    Spacer(Modifier.height(6.dp))

                    // ② 历史位置（防错配硬规则：仅确定性 ID 才允许出现）
                    val low = if (detected.itemId != null) lowestWithinDays(bundle?.history, 90) else null
                    if (low != null && low > 0) {
                        val pct = ((detected.price - low) / low * 100).roundToInt()
                        Text(
                            text = if (pct > 0) {
                                stringResource(R.string.ovl_history_high, PriceFormatter.format(low), pct)
                            } else {
                                stringResource(R.string.ovl_history_near_low, PriceFormatter.format(low))
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
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
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            OverlayChip("$ownPlatform ${PriceFormatter.format(detected.price)}", MaterialTheme.colorScheme.primary)
                            platforms.take(2).forEach {
                                OverlayChip("${it.platform} ${PriceFormatter.format(it.price)}", MaterialTheme.colorScheme.tertiary)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }

                    // ④ 券（门槛 0 = "无门槛"；TITLE_ONLY 命中时同样标仅供参考）
                    val coupons = bundle?.coupons.orEmpty()
                    if (coupons.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
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
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }

                    // ⑤ 底部灰字：来源 + 数据时间 + 非实时（必须项；stale 再补一行降级提示）
                    val b = bundle
                    Text(
                        text = if (b != null) {
                            stringResource(R.string.ovl_footer_bundle, b.sourceLabel ?: "-", TimeAgo.format(b.fetchedAtMs))
                        } else {
                            stringResource(
                                R.string.ovl_footer_live,
                                detected.sourceText ?: stringResource(R.string.ovl_source_unknown)
                            )
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
                    Spacer(Modifier.height(8.dp))

                    // CTA：确定性 ID → 查历史价；仅标题命中 → 降级为"在 App 内搜索"
                    Button(
                        onClick = onCompare,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(Dims.ButtonCorner)
                    ) {
                        Text(
                            text = if (detected.itemId != null) {
                                stringResource(R.string.overlay_cta)
                            } else {
                                stringResource(R.string.ovl_title_only_cta)
                            },
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }
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
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * N 天窗口内的最低价：慢慢买曲线点 date 为 yyyy-MM-dd（ISO 字典序=时间序），
 * 直接字符串比较裁剪；曲线不足 N 天时用全量点（历史价行仍可出，文案里写明窗口）。
 * 纯计算，单独抽出便于未来在 ViewModel 侧复用/测试。
 */
internal fun lowestWithinDays(history: ManmanbuyApi.History?, days: Int, today: LocalDate = LocalDate.now()): Double? {
    if (history == null || history.points.isEmpty()) return null
    val cutoff = today.minusDays(days.toLong()).toString()
    val windowed = history.points.filter { it.date >= cutoff }
    val pool = windowed.ifEmpty { history.points }
    return pool.minOf { it.price }.takeIf { it > 0.0 }
}
