package com.pricelens.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import com.pricelens.R
import com.pricelens.data.repository.SettingsRepository
import com.pricelens.ui.theme.Dims
import com.pricelens.ui.theme.MotionDurations
import com.pricelens.ui.theme.PriceLensEasing

/**
 * 新手引导流程（对标 Mihon `OnboardingScreen.kt` 的结构，全部自研）：
 *  - `rememberSaveable { mutableIntStateOf(0) }` 保存步骤序号（旋转不丢进度）；
 *  - `BackHandler(enabled = currentStep != 0)`：首步交给系统返回（不出现"返回即退出引导又立刻重进"的死循环）；
 *  - `canAccept = steps.all { it.isComplete }` 控门禁；权限/可选步骤恒可过（见 [OnboardingStep]）；
 *  - 转场复用本仓动效令牌（[MotionDurations] + [PriceLensEasing]，250ms、无弹跳）。
 *
 * 四步：价值说明 → 无障碍 → 悬浮窗 → 可选凭证。
 */
@Composable
fun OnboardingFlow(settings: SettingsRepository, onFinish: () -> Unit) {
    val steps = remember(settings) {
        listOf(WelcomeStep(), AccessibilityStep(), OverlayStep(), OptionalCredsStep(settings))
    }
    var currentStep by rememberSaveable { mutableIntStateOf(0) }
    // 转场方向：后退时水平镜像（Mihon 同款方向感知）
    var backwards by rememberSaveable { mutableStateOf(false) }

    val canAccept = steps.all { it.isComplete }
    val isLast = currentStep == steps.lastIndex

    fun finish() {
        settings.setOnboardingDone(true)
        onFinish()
    }

    fun advance() {
        steps[currentStep].onConfirm()
        if (isLast) {
            finish()
        } else {
            backwards = false
            currentStep++
        }
    }

    fun goBack() {
        backwards = true
        currentStep--
    }

    BackHandler(enabled = currentStep != 0) { goBack() }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dims.SpacingL, vertical = Dims.SpacingM),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    stringResource(R.string.onboarding_step_count, currentStep + 1, steps.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                StepDots(count = steps.size, index = currentStep)
                TextButton(onClick = { finish() }) {
                    Text(stringResource(R.string.onboarding_skip_all))
                }
            }

            AnimatedContent(
                targetState = currentStep,
                transitionSpec = {
                    val dir = if (backwards) -1 else 1
                    val fadeSpec = tween<Float>(MotionDurations.Standard, easing = PriceLensEasing)
                    val slideSpec = tween<IntOffset>(MotionDurations.Standard, easing = PriceLensEasing)
                    (fadeIn(fadeSpec) + slideInHorizontally(slideSpec) { full -> dir * full / 4 })
                        .togetherWith(fadeOut(fadeSpec) + slideOutHorizontally(slideSpec) { full -> -dir * full / 4 })
                },
                modifier = Modifier.weight(1f),
                label = "onboardingStep"
            ) { index ->
                steps[index].Content()
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dims.SpacingL, vertical = Dims.SpacingXL),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (currentStep != 0) {
                    OutlinedButton(onClick = { goBack() }, shape = MaterialTheme.shapes.small) {
                        Text(stringResource(R.string.onboarding_back))
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { advance() },
                    enabled = canAccept,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        stringResource(
                            if (isLast) {
                                R.string.onboarding_done
                            } else {
                                R.string.onboarding_next
                            }
                        )
                    )
                }
            }
        }
    }
}

/** 步骤进度点：当前步加宽（克制，只用尺寸区分，不加弹跳动效） */
@Composable
private fun StepDots(count: Int, index: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(Dims.SpacingXS)) {
        repeat(count) { i ->
            val active = i == index
            Surface(
                modifier = Modifier.size(if (active) Dims.DotActive else Dims.DotIdle),
                shape = CircleShape,
                color = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                content = {}
            )
        }
    }
}
