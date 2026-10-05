package com.pricelens.ui.common

import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import com.pricelens.coupon.ClipboardCapture
import com.pricelens.coupon.ClipboardTriage
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
 * 纯逻辑（什么算"值得提示"）在 [LinkParser]，"一次读取之后两条链各拿什么"在
 * [com.pricelens.coupon.ClipboardTriage]（都是纯函数，能在 JVM 里钉边界），这里只管拿文本。
 */
object ClipboardDetector {

    /** 上一次处理过的文本哈希；0 = 还没处理过任何内容 */
    private var lastHash = 0

    /**
     * 读一次剪贴板，**同时喂两条链**（#63）：
     *  - 链接链：返回值给概览页横幅（只在认得链接且没提示过时非空）；
     *  - 找券链：原文进 [ClipboardCapture] 单槽，由找券段按新鲜度决定用不用。
     *
     * 旧版在这里写的是 `if (parsed != null) lastHash = hash; return parsed` ——
     * 于是"没有链接的分享文本"既不会被记住、也永远进不了找券链，
     * 而找券段自己再读一次就是第二次读取（正是这个开关的合规前提不允许的）。
     */
    fun detect(context: Context): LinkParser.ParsedLink? {
        val text = readText(context) ?: return null
        val parsed = LinkParser.parse(text)
        val decision = ClipboardTriage.decide(lastHash, text, parsed)
        lastHash = decision.newHash
        decision.publishText?.let { ClipboardCapture.publish(it, SystemClock.elapsedRealtime()) }
        return if (decision.showBanner) parsed else null
    }

    /** 用户点「忽略」后调用：同一段内容不再提示（直到换一段）。券那一路不受影响 */
    fun markIgnored(text: String) {
        lastHash = text.hashCode()
    }

    /** 诊断/测试用 */
    fun reset() {
        lastHash = 0
        ClipboardCapture.clear()
    }

    private fun readText(context: Context): String? {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return null
        if (!manager.hasPrimaryClip()) return null
        val text = runCatching {
            manager.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        }.getOrDefault("")
        return text.takeIf { it.isNotBlank() }
    }
}
