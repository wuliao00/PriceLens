package com.pricelens.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pricelens.ui.theme.Dims

/**
 * §1.5 无障碍浮窗：极简、不遮挡操作（WindowManager 层 FLAG_NOT_FOCUSABLE）。
 * §2.3 弹窗进入 200ms spring(dampingRatio=0.8)；动画只走 graphicsLayer 绘制通道。
 */
@Composable
fun PriceOverlay(
    price: String,
    title: String,
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

    Surface(
        modifier = Modifier
            .padding(16.dp)
            .graphicsLayer {
                alpha = progress
                translationY = (1f - progress) * 12.dp.toPx()
            },
        shape = RoundedCornerShape(Dims.CardCorner),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 4.dp,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "关闭浮窗",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = price,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onCompare,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Dims.ButtonCorner)
            ) {
                Text("查看历史价 & 优惠券")
            }
        }
    }
}
