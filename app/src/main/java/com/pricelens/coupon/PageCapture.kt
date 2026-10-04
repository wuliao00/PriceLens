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
 * 本机找券的**输入选择**（纯函数）。
 *
 * 树不是随时都有的：无障碍服务没开、不在商详、或用户搜的是自己贴进来的分享文本
 * （那条路 keyword 本身就是富文本，是 B2 本机识别的"本命"，不能因为树上有了东西就丢掉）。
 * 所以这里只回答"这一次能用哪几路输入"，两路都有时两路都跑、结果**做加法**。
 */
object LocalCouponInputPlanner {

    enum class Kind {
        /** 只有关键词文本（服务没抓到树，或树已经过期） */
        KEYWORD_ONLY,

        /** 只有页面树（关键词为空） */
        PAGE_ONLY,

        /** 两路都有 ⇒ 两路都跑再合并 */
        BOTH,

        /** 两路都没有：不许假装"读过了" */
        NONE
    }

    data class Plan(val kind: Kind, val ageMs: Long)

    /**
     * 兜底新鲜度上限：两分钟。
     *
     * 依据不是"页面停留一般多久"，而是服务侧的行为：商详页上内容变化事件会不断重发布，
     * 而"离开商详"是显式清理点 ⇒ 一个还挂在槽里、却超过两分钟没被刷新的树，只可能来自
     * 服务被杀/异常没走到清理分支的那类情况。这一条是**备胎**，正常路径靠 clear()。
     */
    const val MAX_AGE_MS = 120_000L

    fun plan(capture: PageCapture.Capture?, keyword: String, nowElapsedMs: Long, maxAgeMs: Long = MAX_AGE_MS): Plan {
        val hasKeyword = keyword.isNotBlank()
        if (capture == null) return Plan(if (hasKeyword) Kind.KEYWORD_ONLY else Kind.NONE, -1L)
        val age = nowElapsedMs - capture.capturedAtElapsedMs
        // 负数 = 时钟回拨或两个时钟不可比（elapsedRealtime 单调，出现负值就是接线错了），按过期处理
        val fresh = age in 0L..maxAgeMs
        if (!fresh) return Plan(if (hasKeyword) Kind.KEYWORD_ONLY else Kind.NONE, age)
        return when {
            hasKeyword -> Kind.BOTH
            else -> Kind.PAGE_ONLY
        }.let { Plan(it, age) }
    }
}
