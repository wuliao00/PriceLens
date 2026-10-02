package com.pricelens.data.repository

import android.content.Context
import com.pricelens.util.SecretKeys
import com.pricelens.util.SecretStore
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

    /**
     * 回前台时读剪贴板、认出商品链接就给一条"去比价"横条（文档 §4.2）。
     * 默认开；关掉后不再读剪贴板（合规：Android 10+ 只有前台应用能读，且本 App 只在本机判断）。
     */
    val clipboardDetectEnabled: Boolean
        get() = prefs.getBoolean("clipboard_detect_enabled", true)

    fun setClipboardDetectEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("clipboard_detect_enabled", enabled).apply()
    }

    /** 免责声明是否已同意（同意后启动不再弹出） */
    val disclaimerAgreed: Boolean
        get() = prefs.getBoolean("disclaimer_agreed", false)

    fun setDisclaimerAgreed(agreed: Boolean) {
        prefs.edit().putBoolean("disclaimer_agreed", agreed).apply()
    }

    /**
     * 星罗好货开放平台 apikey（可选：历史低价参考 + 盯价兜底）。
     *
     * 2026-10-02 起经 [SecretStore] 加密存取（KeyStore AES-GCM）；换机恢复后密钥不在时读回空串，
     * 按"没配"处理并让 UI 引导重新获取 —— 这是正确的安全行为，不是 bug。
     */
    val linkstarsApiKey: String
        get() = SecretStore.getString(context, SecretKeys.APIKEY_XL).orEmpty()

    fun setLinkstarsApiKey(value: String) {
        SecretStore.putString(context, SecretKeys.APIKEY_XL, value.trim())
    }

    /** 慢慢买登录 Cookie（可选；加密存储，见 [linkstarsApiKey] 的说明） */
    val manmanbuyCookie: String
        get() = SecretStore.getString(context, SecretKeys.COOKIE_MMB).orEmpty()

    /**
     * 保存 Cookie 时**一并记下抓取时刻**：慢慢买的登录态通常 7~30 天有效，
     * 到期提醒靠这个时间戳（见 PriceCheckWorker 的到期检查）。
     */
    fun setManmanbuyCookie(value: String) {
        SecretStore.putString(context, SecretKeys.COOKIE_MMB, value.trim())
        if (value.isNotBlank()) {
            SecretStore.putString(context, SecretKeys.COOKIE_FETCHED_AT, System.currentTimeMillis().toString())
        }
    }

    /** Cookie 的抓取时刻（毫秒）；0 = 没有记录（老数据或从未抓取） */
    val manmanbuyCookieFetchedAt: Long
        get() = SecretStore.getString(context, SecretKeys.COOKIE_FETCHED_AT)?.toLongOrNull() ?: 0L

    /** 这个 fetchedAt 是否已经提醒过到期（同一次抓取只提醒一次） */
    var cookieExpiryNotifiedFor: Long
        get() = prefs.getLong("cookie_expiry_notified_for", 0L)
        set(value) = prefs.edit().putLong("cookie_expiry_notified_for", value).apply()

    // ---------- 强制更新闸门状态（v2.6.0 新增，全部本机持久化，不上传） ----------

    /**
     * 灰度桶（0..99）：首次读取时随机生成并**永久固定**。
     * 必须固定 —— 若每次冷启重摇，同一份清单会"今天提示、明天不提示"，
     * 用户体感是"更新提示抽风"，灰度也失去可复现性。
     */
    val installBucket: Int
        get() {
            val stored = prefs.getInt("install_bucket", -1)
            if (stored in 0..99) return stored
            val generated = kotlin.random.Random.nextInt(0, 100)
            prefs.edit().putInt("install_bucket", generated).apply()
            return generated
        }

    /** 是否已经走完新手引导 */
    val onboardingDone: Boolean
        get() = prefs.getBoolean("onboarding_done", false)

    fun setOnboardingDone(done: Boolean) {
        prefs.edit().putBoolean("onboarding_done", done).apply()
    }

    /** 上一次已提示过的清单 generatedAt：同一次发布只提示一次（含阻断层） */
    val lastCheckedGeneratedAt: Long
        get() = prefs.getLong("last_checked_generated_at", 0L)

    fun setLastCheckedGeneratedAt(generatedAtMs: Long) {
        prefs.edit().putLong("last_checked_generated_at", generatedAtMs).apply()
    }

    /**
     * 静默截止时间：同时承载两种语义（都是"这段时间内别再弹"）——
     *  - 阻断层逃生口"我已升级仍提示我" → 24 小时
     *  - 可跳过提示的"以后再说" → 清单 cooldownHours
     */
    val skipUntilMs: Long
        get() = prefs.getLong("skip_until_ms", 0L)

    fun setSkipUntilMs(untilMs: Long) {
        prefs.edit().putLong("skip_until_ms", untilMs).apply()
    }

    /** 连续下载安装失败次数：达到阈值后阻断层降级为普通提示，成功一次即清零 */
    val updateFailCount: Int
        get() = prefs.getInt("update_fail_count", 0)

    fun incrementUpdateFailCount(): Int {
        val next = updateFailCount + 1
        prefs.edit().putInt("update_fail_count", next).apply()
        return next
    }

    fun resetUpdateFailCount() {
        if (updateFailCount != 0) prefs.edit().putInt("update_fail_count", 0).apply()
    }
}
