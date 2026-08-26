package com.pricelens.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.pricelens.ui.theme.Dims

/**
 * 通用空态/引导组件（极简原则）：图标 + 标题 + 说明 + 可选动作。
 * 替换各屏私有的 GuideCard / EmptyHint / EmptyText 重复实现。
 */
@Composable
fun EmptyState(
    icon: ImageVector? = null,
    title: String,
    desc: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(
            modifier = Modifier.padding(Dims.SpacingL),
            horizontalAlignment = Alignment.Start
        ) {
            icon?.let {
                Icon(
                    it,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(Dims.SpacingXXL)
                )
                Spacer(Modifier.height(Dims.SpacingM))
            }
            Text(title, style = MaterialTheme.typography.titleMedium)
            desc?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Dims.SpacingXS)
                )
            }
            if (actionLabel != null && onAction != null) {
                TextButton(
                    onClick = onAction,
                    modifier = Modifier.padding(top = Dims.SpacingXS)
                ) { Text(actionLabel) }
            }
        }
    }
}

/**
 * 轻量空提示（无卡片容器）：用于列表内"暂无数据"的次级说明。
 */
@Composable
fun EmptyText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}
