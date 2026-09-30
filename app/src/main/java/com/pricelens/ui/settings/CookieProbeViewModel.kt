package com.pricelens.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pricelens.data.remote.CookieProbe
import com.pricelens.data.remote.ManmanbuyApi
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 设置页「检测 Cookie」的状态机：一次性的探测进度 + 结论。
 *
 * 判定逻辑全在 [ManmanbuyApi.probeCookie] / `classifyHistoryPage`（纯函数，可单测），
 * 这里只负责"进行中 / 有结论"的流转——Compose UI 测试在本项目受 Robolectric 限制，
 * 故刻意不往这层塞任何可判定行为。
 */
@HiltViewModel
class CookieProbeViewModel @Inject constructor(
    private val api: ManmanbuyApi
) : ViewModel() {

    sealed interface Ui {
        data object Idle : Ui
        data object Running : Ui
        data class Result(val probe: CookieProbe) : Ui
    }

    private val _ui = MutableStateFlow<Ui>(Ui.Idle)
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    /** 用[cookie]（调用方传输入框当前值，不是已保存值）打一次固定探针 */
    fun probe(cookie: String) {
        if (_ui.value is Ui.Running) return
        viewModelScope.launch {
            _ui.value = Ui.Running
            _ui.value = Ui.Result(api.probeCookie(ManmanbuyApi.PROBE_PRODUCT_URL, cookie.trim()))
        }
    }
}
