package com.pricelens.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pricelens.R
import com.pricelens.ui.theme.Dims
import com.pricelens.ui.theme.PillShape
import com.pricelens.ui.theme.PriceType

/**
 * 「熔金行情」通用视觉件（2026-11 UI 大改新增）。
 *
 * 为什么单独成文件而不是继续往 PriceCard.kt 里塞：那里的 PriceCard/PriceBadge/PriceRow
 * 被全库多屏依赖，签名一动就要改一片调用点；这批新件只服务新排版（英雄区/入口行/统计格），
 * 单独成文件既方便审查，也把「哪些是这次新长出来的」标了出来。
 */

/**
 * 品牌金英雄区（概览首屏 / 空态引导的主力视觉）。
 *
 * 设计取舍：
 *  - 整块**品牌金**底 + onPrimary 排字：两主题下对比度都达标（亮色白字、暗色墨字），
 *    一眼就把「这是 PriceLens 的第一屏」认出来，而不是又一张灰卡片；
 *  - 背景画一条极淡的下行价格线（[HeroSparkline]）：装饰用，只走 draw 通道、不参与布局，
 *    读屏也读不到它（Icon 的 contentDescription 为 null，Canvas 无默认语义）；
 *  - 两个 CTA **并排等宽**：「现在就要做的事」与「打开后一劳永逸」权重相同，
 *    不把一个挤成文字链。副 CTA 为空时主按钮占满整行。
 */
@Composable
fun HeroBanner(
    title: String,
    desc: String,
    primaryLabel: String,
    onPrimaryClick: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryLabel: String? = null,
    onSecondaryClick: (() -> Unit)? = null
) {
    val gold = MaterialTheme.colorScheme.primary
    val onGold = MaterialTheme.colorScheme.onPrimary
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = gold
    ) {
        Box {
            // 装饰价格线：画在内容之下，matchParentSize 不参与测量
            Canvas(Modifier.matchParentSize()) {
                HeroSparkline.paint(this, onGold.copy(alpha = 0.18f))
            }
            Column(Modifier.padding(Dims.SpacingXL)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_brand_mark),
                        contentDescription = null,
                        tint = onGold,
                        modifier = Modifier.size(Dims.SpacingXXXL)
                    )
                    Spacer(Modifier.width(Dims.SpacingM))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineSmall,
                            color = onGold
                        )
                        Spacer(Modifier.size(Dims.SpacingXS))
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodyMedium,
                            color = onGold.copy(alpha = 0.86f)
                        )
                    }
                }
                Spacer(Modifier.size(Dims.SpacingXL))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Dims.SpacingS),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // 主 CTA 反色：金底上的墨/白按钮，是本屏唯一的实心按钮。
                    // maxLines=2：窄屏/大字号下宁可折成两行，也不把「粘贴链接」截成「粘贴链」
                    // （真机 2026-11 首轮实测 412dp 下两个标签都会缺最后一个字）。
                    Button(
                        onClick = onPrimaryClick,
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = onGold,
                            contentColor = gold
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = Dims.TouchMin)
                    ) {
                        Text(primaryLabel, maxLines = 2, textAlign = TextAlign.Center)
                    }
                    if (secondaryLabel != null && onSecondaryClick != null) {
                        OutlinedButton(
                            onClick = onSecondaryClick,
                            shape = MaterialTheme.shapes.medium,
                            border = BorderStroke(1.dp, onGold.copy(alpha = 0.75f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = onGold),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = Dims.TouchMin)
                        ) {
                            Text(secondaryLabel, maxLines = 2, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 英雄区背景的装饰价格线：从左侧高点折到右侧低点，末尾一枚圆点。
 * 纯绘制、固定比例（不读数据），所以任何宽度下形状一致，不会因数据刷新而跳动。
 */
private object HeroSparkline {
    fun paint(scope: DrawScope, color: Color) {
        val w = scope.size.width
        val h = scope.size.height
        // 只占右侧下半区：文字与按钮在全宽上排，线条退到右下方当底纹
        val left = w * 0.52f
        val top = h * 0.42f
        val bottom = h * 0.86f
        val xs = floatArrayOf(
            left,
            left + (w - left) * 0.28f,
            left + (w - left) * 0.52f,
            left + (w - left) * 0.74f,
            w * 0.94f
        )
        val ys = floatArrayOf(
            top,
            top + (bottom - top) * 0.22f,
            top + (bottom - top) * 0.34f,
            top + (bottom - top) * 0.68f,
            bottom
        )
        val path = Path().apply {
            moveTo(xs[0], ys[0])
            for (i in 1 until xs.size) lineTo(xs[i], ys[i])
        }
        scope.drawPath(
            path = path,
            color = color,
            style = Stroke(width = scope.size.minDimension * 0.012f, cap = StrokeCap.Round)
        )
        scope.drawCircle(
            color = color,
            radius = scope.size.minDimension * 0.022f,
            center = Offset(xs.last(), ys.last())
        )
    }
}

/**
 * 入口行：图标（tonal 圆角方块）+ 主名 + 次要说明 + 右箭头。
 *
 * 为什么不用 CompactTileRow：那是「一行数据」的紧凑行（固定 60dp，给价格/徽标留位），
 * 这是「一个入口」（要放下 40dp 图标与两行文字），两者高度与语义都不同，混用会把入口挤扁。
 */
@Composable
fun EntryTile(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    desc: String? = null,
    tint: Color = MaterialTheme.colorScheme.primary
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(Dims.SpacingM)
        ) {
            Surface(shape = MaterialTheme.shapes.medium, color = tint.copy(alpha = 0.14f)) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier
                        .padding(Dims.SpacingS)
                        .size(Dims.SpacingXXL)
                )
            }
            Spacer(Modifier.width(Dims.SpacingM))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (desc != null) {
                    Text(
                        desc,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Dims.IconInline)
            )
        }
    }
}

/**
 * 统计格：大号等宽数字（品牌金）+ 小标签。
 * 值里禁止再出现标签词（旧实现出现过「缓存 内存 0 KB · 图片 3 MB」的标签套标签）。
 */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            value,
            style = PriceType.PriceLarge,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/**
 * 卡片内小标题行：标题 + 可选右侧说明。
 * 与 SectionHeader（整页分组标题）分工：那个是页级分组，这个是卡片内部的分段。
 */
@Composable
fun CardSectionTitle(title: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) {
            Text(
                trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

/**
 * 胶囊选中块（分段选择器 / 频率选择共用）。
 * 选中态是实心品牌金 + onPrimary 字，未选中透明；触控高度守 [Dims.TouchMin]。
 */
@Composable
fun PillChoice(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = PillShape,
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .heightIn(min = Dims.TouchMin)
                .padding(horizontal = Dims.SpacingM),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}

/** 一行实心主 CTA（金底 onPrimary 字）：用于「打开自动化中心」「记录这笔购买」这类整行动作 */
@Composable
fun PrimaryActionRow(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dims.TouchMin)
    ) {
        Text(label, maxLines = 1)
    }
}

/** 空态卡里的图标块（与 EmptyState 同款视觉语言，供卡片内自绘空态复用） */
@Composable
fun IconTile(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.primary) {
    Surface(shape = MaterialTheme.shapes.medium, color = tint.copy(alpha = 0.14f)) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .padding(Dims.SpacingS)
                .size(Dims.SpacingXXL)
        )
    }
}

/** 1dp 分隔线（走 outlineVariant；不用 Divider 的默认 8% 黑，暗色下会脏） */
@Composable
fun HairLine(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.fillMaxWidth(),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
    )
}
