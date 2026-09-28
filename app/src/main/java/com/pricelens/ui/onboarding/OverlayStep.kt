package com.pricelens.ui.onboarding

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.pricelens.R
import com.pricelens.accessibility.OverlayManager

/**
 * 第 3 步：悬浮窗权限。
 * 只调用 [OverlayManager.requestPermission]（该文件属敏感链路，本次零改动）。
 * 同样不阻断完成：缺权限时由首页 [SetupHintBar] 继续给入口。
 */
class OverlayStep : OnboardingStep {

    override val key = "overlay"

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val states = rememberPermissionStates()

        StepScaffold(
            title = stringResource(R.string.onboarding_overlay_title),
            desc = stringResource(R.string.onboarding_overlay_desc),
            status = OnboardingStatus(
                stringResource(
                    if (states.overlayGranted) {
                        R.string.onboarding_status_granted
                    } else {
                        R.string.onboarding_status_missing
                    }
                ),
                states.overlayGranted
            )
        ) {
            OutlinedButton(
                onClick = { OverlayManager.requestPermission(context) },
                shape = MaterialTheme.shapes.small
            ) {
                Text(stringResource(R.string.onboarding_overlay_action))
            }
            Text(
                stringResource(R.string.onboarding_overlay_rom_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.onboarding_permission_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
