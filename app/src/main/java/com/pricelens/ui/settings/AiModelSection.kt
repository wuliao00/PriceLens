package com.pricelens.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pricelens.R
import com.pricelens.coupon.ai.DeviceCapability
import com.pricelens.coupon.ai.ModelAdvice
import com.pricelens.coupon.ai.ModelAdvisor
import com.pricelens.coupon.ai.ModelRepository
import com.pricelens.coupon.ai.OnDeviceAiPolicy
import com.pricelens.ui.theme.Dims

/**
 * 端侧识别（可选）—— 把"这台手机能不能跑、值不值得下、下到哪一步"摊在一张卡里。
 *
 * 三件事的顺序是刻意的，对应的就是用户问的三个问题：
 *  1. **能不能**：检测在 `AiModelViewModel` 构造时跑（DeviceProbe 读六个标量，ModelAdvisor 给结论）；
 *  2. **值不值**：把检测结果**写出来**（几核、多少内存、电池），不只给一句"推荐" ——
 *     用户看不到依据的推荐，本质上是推销；
 *  3. **装不装**：下载走分发仓库（release 资产，397MB），**默认不装**；点了下载才写同意开关，
 *     中途失败可重试（断点续传），装完可删。
 *
 * 状态与副作用全在 ViewModel 里，这里只负责"照状态画"（第一版直接在这里调 ApiClient.http，
 * 编译就红了 —— 分层不是洁癖，是它当场告诉了我这个类不属于 UI）。
 */
@Composable
fun AiModelSection(modifier: Modifier = Modifier, viewModel: AiModelViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val capability = state.capability

    Column(modifier = modifier.fillMaxWidth()) {
        Text(stringResource(R.string.ai_model_title), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(Dims.SpacingXS))
        // 依据先摆出来（几核 / 多少内存 / 电池），再给结论
        Text(deviceSummary(capability), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Dims.SpacingS))

        when (state.advice.state) {
            ModelAdvice.State.READY -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ai_model_ready), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(Dims.SpacingS))
                    TextButton(onClick = viewModel::deleteModel) { Text(stringResource(R.string.ai_model_delete)) }
                }
            }
            ModelAdvice.State.SUGGEST_INSTALL, ModelAdvice.State.NEEDS_DOWNLOAD -> {
                val recommendedMb = ModelRepository.BYTES / (1024L * 1024L)
                Text(stringResource(R.string.ai_model_recommend, recommendedMb), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(Dims.SpacingXS))
                ReasonText(state.advice)
                Spacer(Modifier.height(Dims.SpacingS))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(enabled = state.progress == null, onClick = viewModel::startDownload) {
                        Text(stringResource(R.string.ai_model_download))
                    }
                    val progress = state.progress
                    if (progress != null) {
                        Spacer(Modifier.width(Dims.SpacingS))
                        CircularProgressIndicator(modifier = Modifier.size(Dims.IconInline))
                        Spacer(Modifier.width(Dims.SpacingS))
                        Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            ModelAdvice.State.WAIT -> {
                Text(stringResource(R.string.ai_model_wait), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(Dims.SpacingXS))
                ReasonText(state.advice)
            }
            ModelAdvice.State.UNSUPPORTED -> {
                Text(stringResource(R.string.ai_model_unsupported), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(Dims.SpacingXS))
                ReasonText(state.advice)
            }
            ModelAdvice.State.OFF -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ai_model_installed_off), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    // 装好了但关着：这里只露出开关，不再弹一次推荐
                    Switch(checked = false, onCheckedChange = { viewModel.setEnabled(true) })
                }
            }
        }
        if (state.message != AiModelUiState.Message.NONE) {
            Spacer(Modifier.height(Dims.SpacingXS))
            Text(
                stringResource(
                    when (state.message) {
                        AiModelUiState.Message.DOWNLOADED -> R.string.ai_model_download_done
                        AiModelUiState.Message.FAILED -> R.string.ai_model_download_failed
                        else -> R.string.ai_model_deleted
                    }
                ),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/** 换行/截断都交给 Compose，三处复用同一个"小字理由"的样式 */
@Composable
private fun ReasonText(advice: ModelAdvice) {
    Text(reasonLine(advice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** 依据一句话（"8 核 · 内存 12 GB（可用 4 GB）· 电量 76%"），比"推荐"两个字诚实 */
@Composable
private fun deviceSummary(capability: DeviceCapability): String = stringResource(
    R.string.ai_model_device_summary,
    Runtime.getRuntime().availableProcessors(),
    capability.deviceRamMb / 1024,
    capability.freeRamMb / 1024,
    capability.batteryPercent
)

@Composable
private fun reasonLine(advice: ModelAdvice): String = when (advice.reasonKey) {
    ModelAdvisor.REASON_BATTERY -> stringResource(R.string.ai_model_reason_battery, OnDeviceAiPolicy.MinBatteryPercent)
    ModelAdvisor.REASON_FREE_RAM -> stringResource(R.string.ai_model_reason_ram)
    ModelAdvisor.REASON_METERED -> stringResource(R.string.ai_model_reason_metered)
    ModelAdvisor.REASON_ABI -> stringResource(R.string.ai_model_reason_abi)
    ModelAdvisor.REASON_NOT_INSTALLED -> {
        val recommendedMb = ModelRepository.BYTES / (1024L * 1024L)
        stringResource(R.string.ai_model_reason_not_installed, recommendedMb)
    }
    else -> stringResource(R.string.ai_model_reason_device_ram, OnDeviceAiPolicy.MinDeviceRamMb / 1024)
}
