package com.pricelens.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.pricelens.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * §1.4 核心无障碍服务：监听京东/淘宝/拼多多**商详页**，识别当前价（含口径）+ 商品标题
 * + 确定性商品 ID，通过 [PriceEvents] 通知浮窗/应用内 UI。
 *
 * A2 改造（读取准确性 + 页面门控）：
 *  - 本类只剩"节点 → 快照 → 纯函数判定 → 事件"薄适配，全部规则在
 *    [NodeSnapshot.kt] / [PriceNodeMatcher.kt]，可 JVM 表驱动单测；
 *  - 非商详页（首页/列表/购物车/订单）不再 emit，且收起浮窗、清空残留（旧版离开商详不收窗，
 *    旧浮窗最长滞留 15s）；
 *  - 商详页读不到价：窗口切换事件即时收窗，不再"return 但留着旧窗"；
 *  - 包名切换时清 lastSignature（旧版永不清空导致跨 App 串台）。
 *
 * 配置见 res/xml/accessibility_service_config.xml（packageNames 白名单，300ms 事件节流）。
 */
class PriceMonitorService : AccessibilityService() {

    /** 同一页面去重：itemId/标题/价格文本都没变就不重复广播 */
    private var lastSignature: String? = null
    private var lastPackageName: String? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 服务就绪后开始订阅价格事件并管理浮窗
        OverlayManager.start(this, serviceScope)
    }

    /**
     * B2：旋转 / 分屏 / 折叠屏展开后，浮窗的拖动边界必须作废。
     * 旧实现只在进程内首算一次，横屏后仍按竖屏尺寸 clamp，胶囊会被拖到屏幕外。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        OverlayManager.onConfigurationChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val isStateChanged = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (!isStateChanged && event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
        if (!PriceNodeMatcher.isKnownApp(packageName)) return
        if (packageName != lastPackageName) {
            // 跨 App 迁移：签名缓存必须作废，否则回到前台页面误判"未变化"不 emit
            lastPackageName = packageName
            lastSignature = null
        }

        val rootNode = rootInActiveWindow ?: return
        try {
            val platform = ShopPlatform.fromPackage(packageName)
            val snapshot = rootNode.toSnapshotCompat()

            if (!isProductPage(snapshot, platform)) {
                // 离开商详（首页/列表/购物车/其他）：收窗 + 清内容，允许下次进入重新 emit
                if (lastSignature != null) {
                    lastSignature = null
                    OverlayManager.onLeftProductPage()
                }
                return
            }

            val priceHit = extractPriceHit(snapshot, platform)
            if (priceHit == null) {
                // 商详但读不到价（页面加载中/改版）：窗口切换事件收窗；内容变化事件等渲染完成
                if (isStateChanged && lastSignature != null) {
                    lastSignature = null
                    OverlayManager.onLeftProductPage()
                }
                return
            }
            val titleHit = extractTitle(snapshot, platform)
            val itemId = extractItemId(snapshot)

            val signature = "$packageName|${itemId ?: ""}|${titleHit?.text ?: ""}|${priceHit.rawText}"
            if (signature == lastSignature) return
            lastSignature = signature

            PriceEvents.emit(
                PriceEvents.Detected(
                    price = priceHit.value,
                    rawPriceText = priceHit.rawText,
                    title = titleHit?.text,
                    packageName = packageName,
                    platform = platform,
                    priceBasis = priceHit.basis,
                    itemId = itemId,
                    sourceText = sourceLabel(platform)
                )
            )
        } finally {
            rootNode.recycleCompat()
        }
    }

    /** 浮窗脚注"来源"文案（机器可读的平台名保留在 Detected.platform，这里是人读文案） */
    private fun sourceLabel(platform: ShopPlatform): String = getString(
        when (platform) {
            ShopPlatform.JD -> R.string.ovl_source_jd
            ShopPlatform.TAOBAO -> R.string.ovl_source_taobao
            ShopPlatform.PDD -> R.string.ovl_source_pdd
            ShopPlatform.UNKNOWN -> R.string.ovl_source_unknown
        }
    )

    /**
     * AccessibilityNodeInfo → NodeSnapshot 薄适配（唯一与框架耦合的读取点）。
     * 递归带深度/节点数预算：UI 树再深也不会栈溢出或拖垮主线程
     * （旧版为找价格/标题做了两次全树遍历，且每次 getChild 都是跨进程取数；
     * 快照一次取平后，全部判定在纯模型上完成）。
     */
    private fun AccessibilityNodeInfo?.toSnapshotCompat(): NodeSnapshot {
        val budget = intArrayOf(MAX_SNAPSHOT_NODES)
        return snapshotOf(this, 0, budget)
    }

    private fun snapshotOf(
        node: AccessibilityNodeInfo?,
        depth: Int,
        budget: IntArray
    ): NodeSnapshot {
        if (node == null) return NodeSnapshot(null, null, null, null, false, emptyList())
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        val className = node.className?.toString()
        val resourceName = node.viewIdResourceName
        val clickable = node.isClickable ||
            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
        val children = ArrayList<NodeSnapshot>(node.childCount)
        if (depth < MAX_SNAPSHOT_DEPTH && budget[0] > 0) {
            for (i in 0 until node.childCount) {
                if (budget[0] <= 0) break
                budget[0]--
                val child = node.getChild(i) ?: continue
                try {
                    children.add(snapshotOf(child, depth + 1, budget))
                } finally {
                    child.recycleCompat()
                }
            }
        }
        return NodeSnapshot(text, desc, className, resourceName, clickable, children)
    }

    override fun onInterrupt() {
        // 系统中断服务（如用户关闭权限）：清理浮窗
        OverlayManager.onLeftProductPage()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        OverlayManager.stop()
        super.onDestroy()
    }

    private companion object {
        /** 快照预算：与旧版"两次全树遍历"相比 IPC 取数次数同级，判定改在内存 */
        const val MAX_SNAPSHOT_NODES = 4000
        const val MAX_SNAPSHOT_DEPTH = 64
    }
}

/** API 33+ recycle 已是 no-op，低版本仍需手动回收 */
private fun AccessibilityNodeInfo.recycleCompat() {
    @Suppress("DEPRECATION")
    if (android.os.Build.VERSION.SDK_INT < 33) recycle()
}
