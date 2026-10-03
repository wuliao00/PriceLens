package com.pricelens.coupon.adapters

import com.pricelens.coupon.normalize.Clause
import com.pricelens.coupon.normalize.Clauses
import com.pricelens.coupon.normalize.Normalize

/**
 * 入口一：剪贴板分享文案（用户从京东/淘宝点"分享"复制下来的那一串）。
 *
 * 只做三件事，而且刻意**不做**第四件：
 *  1. 整体规整 [Normalize.text] —— 必须**先规整再分句**：全角分号 `；` 规整成 `;` 之后
 *     [Clauses] 才切得动，顺序反了整句都不拆，症状是"多券混并"治不好；
 *  2. 分句 [Clauses.split] —— 每句独立进抽取；
 *  3. 去噪（零宽/emoji）已在 1 里做完。
 *
 * 刻意不做：链接解码不在这里。[com.pricelens.coupon.normalize.Links] 由抽取管线按需调用，
 * 两个地方各解一次会造出两份"同一张券"的槽位。
 */
object TextAdapter {

    fun normalize(clipboardText: String): List<Clause> = Clauses.split(Normalize.text(clipboardText))
}
