package com.pricelens.coupon

import com.pricelens.domain.LinkParser

/**
 * 「一次剪贴板读取之后，两条链各拿什么」的纯判据（#63）。
 *
 * 拆出来的理由就一句：这件事**不该由"是不是商品链接"来决定**。旧代码把两件事混成一个条件
 * （`if (parsed != null)` 才返回），于是没有链接的分享文本既没横幅也没进找券链。
 * 现在两条链各自判断，互不牵连：
 *  - **横幅**：认出了链接，而且这段内容没提示过（同一段只提示一次，是体验问题也是合规问题）；
 *  - **找券**：文本非空且是新的 —— 新不新按整段文本判，不按"有没有链接"判。
 *
 * 纯函数（不 import android）：`SystemClock`、`ClipboardManager` 都留在调用方，
 * 否则这几条边界只能上真机测。
 */
object ClipboardTriage {

    /**
     * @param publishText 要交给找券链的原文；null = 这次不打扰
     * @param newHash 下一次调用该带回来的 `previousHash`（空白时原样带回，避免把空白当新内容）
     */
    data class Decision(val showBanner: Boolean, val publishText: String?, val newHash: Int)

    fun decide(previousHash: Int, text: String, link: LinkParser.ParsedLink?): Decision {
        if (text.isBlank()) return Decision(showBanner = false, publishText = null, newHash = previousHash)
        val hash = text.hashCode()
        if (hash == previousHash) return Decision(showBanner = false, publishText = null, newHash = previousHash)
        return Decision(showBanner = link != null, publishText = text, newHash = hash)
    }
}
