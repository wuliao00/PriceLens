package com.pricelens.ui.onboarding

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.pricelens.R
import com.pricelens.ui.theme.Dims

/**
 * 第 1 步：一屏讲清产品价值 —— "打开京东/淘宝/拼多多商品页，比价浮窗自动出现"。
 * 对标 Mihon：首步纯信息，不做任何权限动作，`isComplete` 恒 true。
 */
class WelcomeStep : OnboardingStep {

    override val key = "welcome"

    @Composable
    override fun Content() {
        StepScaffold(
            title = stringResource(R.string.onboarding_welcome_title),
            desc = stringResource(R.string.onboarding_welcome_desc)
        ) {
            Bullet(stringResource(R.string.onboarding_welcome_p1))
            Bullet(stringResource(R.string.onboarding_welcome_p2))
            Bullet(stringResource(R.string.onboarding_welcome_p3))
            Text(
                stringResource(R.string.onboarding_welcome_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    @Composable
    private fun Bullet(text: String) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(Dims.SpacingL)
            )
            Spacer(Modifier.width(Dims.SpacingS))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
