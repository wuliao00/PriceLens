package com.pricelens.coupon

import com.pricelens.accessibility.NodeSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 浮窗抓到的**页面节点树**在进程内的单槽交接（任务 #61，2026-10-05）。
 *
 * 断点原本在这里：`PriceMonitorService` 每个事件都造出一棵 `NodeSnapshot`，
 * 但出口 `PriceEvents.Detected` 只有标量字段，树造完就被丢掉 ⇒
 * `CouponExtractor.fromPage` 生产侧**零调用者**，找券 UI 只能拿关键词字符串喂 `fromClipboard`，
 * "入口几乎不可能出券"就是这么来的（本批改的就是这一句）。
 *
 * 为什么不把树塞进 `PriceEvents.Detected` 一起发：那条总线是 `extraBufferCapacity = 4` 的
 * SharedFlow，树一进去就意味着**最多同时驻留 4 棵 4000 节点的树**；而唯一要它的消费方
 * 需要的永远只是"当前这一页"。单槽 + 覆盖写才是这件事的形状（与 `OverlayManager.content` 同理）。
 *
 * 谁负责清（清理点唯一，别处不许自己留一份）：`PriceMonitorService` 的
 * NotProductPage / 窗口切换的 NoPrice / `onDestroy` 三处。
 */
object PageCapture {

    /**
     * @param signature 与 emitDetection 的去重签名同源：UI 侧拿它判断"这棵树是不是当前这次识别的"，
     *   免得浮窗已经换到别的商品而券行还挂在旧树上（旧浮窗滞留那类串台事故的同一形状）。
     * @param capturedAtElapsedMs 由调用方传入（`SystemClock.elapsedRealtime()`），
     *   本文件不 import android.* —— 判据要能在纯 JVM 里测。
     */
    data class Capture(
        val signature: String,
        val packageName: String,
        val itemId: String?,
        val capturedAtElapsedMs: Long,
        val root: NodeSnapshot
    )

    private val _latest = MutableStateFlow<Capture?>(null)
    val latest: StateFlow<Capture?> = _latest

    fun publish(capture: Capture) {
        _latest.value = capture
    }

    /** 只在签名对得上时清：晚到的旧事件不许把新页面的树擦掉 */
    fun clearIfCurrent(signature: String) {
        if (_latest.value?.signature == signature) _latest.value = null
    }

    fun clear() {
        _latest.value = null
    }
}

/**
 * 本机找券的**输入清单**（纯函数）。
 *
 * 三路输入各有各的存在理由，谁也不覆盖谁：
 *  - `PAGE_TREE` —— 浮窗刚抓的这一页（#61）；
 *  - `CLIPBOARD_TEXT` —— 用户复制的分享文本，常含"领券满X减Y"而**没有链接**（#63）；
 *  - `KEYWORD_TEXT` —— 搜索词/标题；用户手动粘贴时它就是富文本（B2 本机识别的本命）。
 *
 * 判据只回答"这一次有哪几路可用"，产出由 `CouponExtractor.mergeAll` **做加法**
 * （面额与门槛都相同的券只算一张）。旧形状是"二选一"，那意味着任何一路有东西
 * 就把另一路整段换掉 —— 与 A/B 第一轮教训（替换策略吃掉规则抽对的券）是同一个错误。
 */
object LocalCouponInputPlanner {

    enum class Input { PAGE_TREE, CLIPBOARD_TEXT, KEYWORD_TEXT }

    data class Plan(val inputs: List<Input>, val treeAgeMs: Long)

    /**
     * 新鲜度上限：两分钟，带时间戳的两路（树、剪贴板）共用。
     *
     * 依据不是"用户一般停留多久"，而是两条链各自的重发布时机：商详页内容变化会不断重发树，
     * 而回前台会重新读一次剪贴板并刷新时间戳 ⇒ 还挂在槽里却两分钟没刷新的东西，
     * 只可能来自服务被杀/异常没走到清理分支那类情况。正常路径靠显式清理，这里只是备胎。
     */
    const val MAX_AGE_MS = 120_000L

    fun plan(
        capture: PageCapture.Capture?,
        clipboard: ClipboardCapture.Reading?,
        keyword: String,
        nowElapsedMs: Long,
        maxAgeMs: Long = MAX_AGE_MS
    ): Plan {
        val inputs = ArrayList<Input>(3)
        var treeAge = -1L
        if (capture != null) {
            treeAge = nowElapsedMs - capture.capturedAtElapsedMs
            if (isFresh(treeAge, maxAgeMs)) inputs.add(Input.PAGE_TREE)
        }
        if (clipboard != null && clipboard.raw.isNotBlank() && isFresh(nowElapsedMs - clipboard.capturedAtElapsedMs, maxAgeMs)) {
            inputs.add(Input.CLIPBOARD_TEXT)
        }
        if (keyword.isNotBlank()) inputs.add(Input.KEYWORD_TEXT)
        return Plan(inputs, treeAge)
    }

    /** 负龄 = 时钟回拨或两个时钟不可比（elapsedRealtime 单调，出现负值就是接线错了）⇒ 按过期处理 */
    private fun isFresh(ageMs: Long, maxAgeMs: Long): Boolean = ageMs in 0L..maxAgeMs
}
