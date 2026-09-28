package com.pricelens.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.pricelens.R
import com.pricelens.ui.theme.Dims

/**
 * 引导完成后仍缺必要权限时，首页顶部的一条可关闭提示。
 *
 * 设计取舍（与 Mihon 的"权限步骤不阻断"配套）：引导不强留人，但也不能让权限缺失变成隐形——
 * 这里给一个常驻（本次会话内可关）入口，主按钮直接**重开引导**，而不是各步骤文案复制一遍。
 */
@Composable
fun SetupHintBar(missing: List<MissingEssential>, onReopenOnboarding: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (missing.isEmpty()) return
    val label = when {
        missing.size >= 2 -> stringResource(R.string.setup_hint_missing_both)
        missing.first() == MissingEssential.ACCESSIBILITY -> stringResource(R.string.setup_hint_missing_acc)
        else -> stringResource(R.string.setup_hint_missing_overlay)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dims.SpacingL)
            .padding(top = Dims.SpacingM),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(Dims.SpacingM),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(Dims.SpacingXXL)
            )
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.setup_hint_title),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    stringResource(R.string.setup_hint_body, label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onReopenOnboarding) {
                Text(stringResource(R.string.setup_hint_action))
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.setup_hint_dismiss),
                    modifier = Modifier.size(Dims.SpacingL)
                )
            }
        }
    }
}
