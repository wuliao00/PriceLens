package com.pricelens.ui.onboarding

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.pricelens.R
import com.pricelens.util.ShizukuHelper

/**
 * 第 2 步：无障碍服务。
 *  - 装了 Shizuku 就把"一键开启"置顶（复用 [ShizukuHelper]，与设置页同一套四态判定）；
 *  - 没装则直接跳 ACTION_ACCESSIBILITY_SETTINGS。
 *
 * 与 Mihon 一致：**这一步不阻断完成**（`isComplete` 取默认 true）——
 * 权限由系统在应用外授予，把它做成硬门禁只会让没装 Shizuku 的用户进不了应用。
 */
class AccessibilityStep : OnboardingStep {

    override val key = "accessibility"

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val states = rememberPermissionStates()

        StepScaffold(
            title = stringResource(R.string.onboarding_acc_title),
            desc = stringResource(R.string.onboarding_acc_desc),
            status = OnboardingStatus(
                stringResource(
                    if (states.accessibilityGranted) {
                        R.string.onboarding_status_granted
                    } else {
                        R.string.onboarding_status_missing
                    }
                ),
                states.accessibilityGranted
            )
        ) {
            if (states.shizukuInstalled) {
                Column {
                    Text(
                        stringResource(R.string.onboarding_acc_shizuku),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Button(
                        onClick = {
                            when {
                                !states.shizukuAlive -> ShizukuHelper.openShizukuApp(context)
                                !states.shizukuReady -> ShizukuHelper.requestPermission()
                                else -> ShizukuHelper.oneClickSetup(context) { states.refresh() }
                            }
                        },
                        shape = MaterialTheme.shapes.small
                    ) {
                        // 按钮文案复用设置页既有三态字符串，不新造说法
                        Text(
                            stringResource(
                                when {
                                    !states.shizukuAlive -> R.string.perm_shizuku_open
                                    !states.shizukuReady -> R.string.perm_shizuku_grant
                                    else -> R.string.perm_shizuku_oneclick
                                }
                            )
                        )
                    }
                    Text(
                        stringResource(
                            when {
                                !states.shizukuAlive -> R.string.onboarding_acc_shizuku_open
                                !states.shizukuReady -> R.string.onboarding_acc_shizuku_grant
                                else -> R.string.perm_shizuku_desc_ready
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                },
                shape = MaterialTheme.shapes.small
            ) {
                Text(stringResource(R.string.onboarding_acc_action))
            }

            Text(
                stringResource(R.string.onboarding_permission_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
