package com.pricelens.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.pricelens.ui.components.PriceBadge
import com.pricelens.ui.theme.BadgeTone
import com.pricelens.ui.theme.Dims
import com.pricelens.ui.theme.PillShape

/**
 * 引导步骤契约（对标 Mihon `OnboardingStep.kt` 的形态，不引库）：
 *  - `isComplete` 决定能否推进；
 *  - **权限类步骤恒为 true**：Mihon 的 `PermissionStep.isComplete` 也直接返回 true ——
 *    系统权限的授予动作发生在系统设置里、应用无法代劳，把它做成硬门禁只会把用户关在门外。
 *    缺权限的补救入口交给完成后的首页 [SetupHintBar]。
 */
interface OnboardingStep {

    /** 稳定标识（日志与单测用；展示顺序由流程列表决定） */
    val key: String

    /** 是否可以离开这一步 */
    val isComplete: Boolean
        get() = true

    @Composable
    fun Content()

    /** 点"下一步 / 开始比价"时的副作用（如保存可选凭证），默认无操作 */
    fun onConfirm() {}
}

/** 步骤通用骨架：品牌标记 + 标题 + 状态徽标（可选） + 说明 + 正文 */
@Composable
fun StepScaffold(title: String, desc: String, status: OnboardingStatus? = null, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dims.SpacingL, vertical = Dims.SpacingXL)
    ) {
        // 大改造：每一步都给同一枚品牌标记（价格放大镜 + 名字），
        // 让"这是 PriceLens 的引导"贯穿四步，而不是四个长得不一样的表单。
        Surface(shape = PillShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = Dims.SpacingM, vertical = Dims.SpacingXS)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_brand_mark),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(Dims.SpacingL)
                )
                Spacer(Modifier.width(Dims.SpacingXS))
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Spacer(Modifier.height(Dims.SpacingL))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f)
            )
            status?.let {
                PriceBadge(it.label, it.tone)
            }
        }
        Spacer(Modifier.height(Dims.SpacingM))
        Text(
            desc,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Dims.SpacingXL),
            verticalArrangement = Arrangement.spacedBy(Dims.SpacingM)
        ) { content() }
    }
}

/** 步骤状态徽标：已开启 / 未开启（仅作提示，不阻断） */
data class OnboardingStatus(val label: String, val granted: Boolean) {
    val tone: BadgeTone get() = if (granted) BadgeTone.POSITIVE else BadgeTone.NEGATIVE
}
