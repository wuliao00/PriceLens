package com.pricelens.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
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
            // 大改造：首步给一块品牌金卡片（价值主张 + 价格放大镜），
            // 与主界面 / 我的页的英雄区同一视觉语言，而不是一枚孤零零的大图标。
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primary
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(Dims.SpacingL)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_brand_mark),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(Dims.SpacingXXXL + Dims.SpacingS)
                    )
                    Spacer(Modifier.width(Dims.SpacingM))
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.overview_hero_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.height(Dims.SpacingXS))
                        Text(
                            stringResource(R.string.overview_hero_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.86f)
                        )
                    }
                }
            }
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
