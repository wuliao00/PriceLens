package com.pricelens.ui.common

import android.content.ClipboardManager
import android.content.Context
import com.pricelens.domain.LinkParser

/**
 * 回前台时的剪贴板检测（文档 §4.2）。
 *
 * 合规与技术边界：
 *  - Android 10+ **只有当前聚焦的应用能读剪贴板**，所以只能在 ON_RESUME 之后读；
 *  - 同一段内容只提示一次（[lastHash]），既是体验问题也是合规问题
 *    （反复读剪贴板会被系统/厂商标记为骚扰）；
 *  - 只在设置开关打开时才调用（开关在 [com.pricelens.data.repository.SettingsRepository]）。
 *
 * 纯逻辑（什么算"值得提示"）在 [LinkParser]，这里只管拿文本与去重。
 */
object ClipboardDetector {

    /** 上一次提示过的文本哈希；0 = 还没提示过任何内容 */
    private var lastHash = 0

    /**
     * @return 值得提示的商品入口；null = 没有剪贴板 / 不是商品链接 / 与上次相同
     */
    fun detect(context: Context): LinkParser.ParsedLink? {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return null
        if (!manager.hasPrimaryClip()) return null
        val text = runCatching {
            manager.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        }.getOrDefault("")
        if (text.isBlank()) return null
        val hash = text.hashCode()
        if (hash == lastHash) return null
        val parsed = LinkParser.parse(text)
        if (parsed != null) lastHash = hash
        return parsed
    }

    /** 用户点「忽略」后调用：同一段内容不再提示（直到换一段） */
    fun markIgnored(text: String) {
        lastHash = text.hashCode()
    }

    /** 诊断/测试用 */
    fun reset() {
        lastHash = 0
    }
}
