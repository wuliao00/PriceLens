package com.pricelens.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pricelens.coupon.ai.DeviceCapability
import com.pricelens.coupon.ai.DeviceProbe
import com.pricelens.coupon.ai.ModelAdvice
import com.pricelens.coupon.ai.ModelAdvisor
import com.pricelens.coupon.ai.ModelStore
import com.pricelens.data.remote.ApiClient
import com.pricelens.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 端侧识别那一组的界面状态（一次快照：能力 + 是否已装 + 是否已同意 + 下载进度 + 一句话反馈） */
data class AiModelUiState(
    val capability: DeviceCapability,
    val advice: ModelAdvice,
    val downloaded: Boolean,
    val enabled: Boolean,
    val progress: Float? = null,
    val message: Message = Message.NONE
) {
    /** 反馈用枚举而不是字符串：文案在 Composable 里出（ViewModel 不碰资源，才能被单测） */
    enum class Message { NONE, DOWNLOADED, FAILED, DELETED }
}

/**
 * 端侧模型这一组的逻辑：检测（[DeviceProbe]）、判据（[ModelAdvisor]，纯函数）、
 * 下载/删除（[ModelStore]）。**下载不走 ViewModel 的缓存**，因为它是一次几百 MB 的 IO。
 *
 * 为什么单独一个 ViewModel 而不是塞进 ProfileViewModel：这里是"往用户手机里放 397MB 权重"，
 * 生命周期与失败语义都该独立（切页/旋屏不该把下载状态搅进缓存统计那一摊）。
 */
@HiltViewModel
class AiModelViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val apiClient: ApiClient,
    @ApplicationContext context: Context
) : ViewModel() {

    // 形参落成属性：Kotlin 的初始化顺序就是声明顺序，而 init{} 里也要用它，
    // 所以必须在 _state / init 之前声明（写成构造函数参数里的 `private val context: Context`
    // 会与 @ApplicationContext 限定符混在一起，索性显式落一份）
    private val appContext: Context = context

    private val store = ModelStore(File(appContext.filesDir, "models"))

    private val _state = MutableStateFlow(
        AiModelUiState(
            capability = DeviceProbe.capability(appContext),
            advice = ModelAdvice(ModelAdvice.State.OFF, ModelAdvisor.REASON_OFF),
            downloaded = false,
            enabled = settings.aiModelEnabled
        )
    )
    val state: StateFlow<AiModelUiState> = _state

    init {
        refresh()
        // 首次进入那一次检测：跑过就打标记（只是"别再弹"的标记；判据每次现算）
        if (!settings.aiDeviceChecked) settings.setAiDeviceChecked(true)
    }

    fun refresh() {
        val capability = DeviceProbe.capability(appContext)
        val downloaded = store.isPresentQuick()
        val enabled = settings.aiModelEnabled
        _state.update {
            it.copy(
                capability = capability,
                advice = ModelAdvisor.advice(capability, downloaded, enabled, cacheBudgetMb = 0L),
                downloaded = downloaded,
                enabled = enabled
            )
        }
    }

    /** 点"下载"= 用户同意（先写开关再下；失败不清开关，用户重试不用再同意一遍） */
    fun startDownload() {
        if (_state.value.progress != null) return
        settings.setAiModelEnabled(true)
        _state.update { it.copy(progress = 0f, enabled = true, message = AiModelUiState.Message.NONE) }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    store.download(apiClient.http) { done, total ->
                        _state.update { s -> s.copy(progress = if (total > 0) done.toFloat() / total else 0f) }
                    }
                }.getOrDefault(false)
            }
            // 下完必须重新问一次 DeviceProbe：进度条挂了几分钟，电量与内存都可能已经变了
            refresh()
            _state.update {
                it.copy(
                    progress = null,
                    message = if (ok) AiModelUiState.Message.DOWNLOADED else AiModelUiState.Message.FAILED
                )
            }
        }
    }

    fun deleteModel() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.delete() }
            settings.setAiModelEnabled(false)
            refresh()
            _state.update { it.copy(message = AiModelUiState.Message.DELETED) }
        }
    }

    fun setEnabled(enabled: Boolean) {
        settings.setAiModelEnabled(enabled)
        refresh()
    }
}
