package com.pricelens.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
        Column(Modifier.padding(Dims.SpacingM)) {
            // 两行式 banner（真机 2026-10-07 截图复核改的）：
            // 原来是一个 Row 里 [图标][文字列][按钮][关闭]，按钮 + 关闭图标吃掉近一半宽度，
            // 于是那句本来两行就放得下的说明被挤成**五行窄栏**，而同一屏下半部大片空白。
            // 说明文字是这条 banner 的主角，必须拿到整行宽度；动作退到第二行右对齐。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(Dims.SpacingXL)
                )
                Spacer(Modifier.width(Dims.SpacingS))
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
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Dims.SpacingXS),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 关闭键与主按钮同高，避免"一个能点一个像装饰"；两者都按 48dp 触控下限留位
                TextButton(onClick = onReopenOnboarding, modifier = Modifier.heightIn(min = Dims.TouchMin)) {
                    Text(stringResource(R.string.setup_hint_action))
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(Dims.TouchMin)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.setup_hint_dismiss),
                        modifier = Modifier.size(Dims.SpacingL)
                    )
                }
            }
        }
    }
}
