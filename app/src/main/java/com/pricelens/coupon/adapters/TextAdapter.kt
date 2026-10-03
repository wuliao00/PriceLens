package com.pricelens.coupon.adapters

import com.pricelens.coupon.normalize.Clause
import com.pricelens.coupon.normalize.Clauses

/**
 * 入口一：剪贴板分享文案（用户从京东/淘宝点"分享"复制下来的那一串）。
 *
 * 只做两件事，而且刻意**不做**第三件：
 *  1. 分句 [Clauses.split] —— 每句独立进抽取，这是治"多券混并"的第一道闸；
 *  2. 规整与去噪**逐句**做（[Clauses.split] 对每一片走一遍 `Normalize.text`）。
 *
 * 刻意不做的是"先整段规整再分句"：`Normalize.text` 把 `\s+`（**含换行**）压成一个空格，
 * 整段先规整就把换行这条分句符在分句之前吃掉了 —— 三行步骤的爆料被并成一句，
 * 病根③原样留着，而症状是"看起来什么都没坏"。反过来是安全的：分句字符类里
 * 全角 `；` 与半角 `;` 两种宽度都收了，不依赖规整；零宽填充与全角数字由逐句规整同一趟扫掉。
 *
 * 刻意不做：链接解码不在这里。[com.pricelens.coupon.normalize.Links] 由抽取管线按需调用，
 * 两个地方各解一次会造出两份"同一张券"的槽位。
 */
object TextAdapter {

    fun normalize(clipboardText: String): List<Clause> = Clauses.split(clipboardText)
}
