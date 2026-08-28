package com.pricelens.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 应用设置单点封装（阶段2）：
 * 所有 SharedPreferences("pricelens") 的读写都收口到此处，
 * MainActivity / 设置页不再裸取 prefs。
 *
 *  - [dynamicColor]      Material You 动态取色开关（StateFlow 响应式暴露）
 *  - [disclaimerAgreed] 免责声明已同意标记（同意后不再展示）
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val prefs by lazy {
        context.getSharedPreferences("pricelens", Context.MODE_PRIVATE)
    }

    /** Material You 动态取色开关（低版本自动回退品牌蓝） */
    private val _dynamicColor = MutableStateFlow(prefs.getBoolean("dynamic_color", true))
    val dynamicColor: StateFlow<Boolean> = _dynamicColor

    fun setDynamicColor(enabled: Boolean) {
        prefs.edit().putBoolean("dynamic_color", enabled).apply()
        _dynamicColor.value = enabled
    }

    /** 免责声明是否已同意（同意后启动不再弹出） */
    val disclaimerAgreed: Boolean
        get() = prefs.getBoolean("disclaimer_agreed", false)

    fun setDisclaimerAgreed(agreed: Boolean) {
        prefs.edit().putBoolean("disclaimer_agreed", agreed).apply()
    }
}
