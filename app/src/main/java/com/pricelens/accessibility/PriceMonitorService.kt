package com.pricelens.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * §1.4 核心无障碍服务：监听京东/淘宝/拼多多商品页，识别当前价 + 商品标题，
 * 通过 [PriceEvents] 通知浮窗/应用内 UI。
 *
 * 配置见 res/xml/accessibility_service_config.xml（packageNames 白名单，
 * 300ms 事件节流），服务本身不持有任何 UI，保持极简。
 */
class PriceMonitorService : AccessibilityService() {

    /** 同一页面去重：价格+标题没变就不重复广播 */
    private var lastSignature: String? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        /** 标题文本排除货币符号：含 ¥/￥ 的视为价格节点而非标题（预编译常量，避免每个事件/节点新建 Regex） */
        private val CURRENCY_PATTERN = Regex("[¥￥]")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 服务就绪后开始订阅价格事件并管理浮窗
        OverlayManager.start(this, serviceScope)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        val packageName = event.packageName?.toString() ?: return
        if (!PriceNodeMatcher.isKnownApp(packageName)) return

        val rootNode = rootInActiveWindow ?: return
        try {
            val priceText = findFirstPrice(rootNode) ?: return
            val price = PriceNodeMatcher.extractPrice(priceText) ?: return
            val title = extractTitle(rootNode)

            val signature = "$packageName|$priceText|$title"
            if (signature == lastSignature) return
            lastSignature = signature

            PriceEvents.emit(
                PriceEvents.Detected(
                    price = price,
                    rawPriceText = priceText,
                    title = title,
                    packageName = packageName
                )
            )
        } finally {
            rootNode.recycleCompat()
        }
    }

    /**
     * 深度优先遍历（显式栈迭代实现），返回第一个命中价格控件的文本
     * （详情页主价格通常最先出现）。UI 树可能很深，主线程上递归有栈溢出风险，
     * 故改为显式栈；子节点逆序入栈，保持与递归版相同的访问顺序。
     * 节点读出子节点后立即回收；提前返回时统一回收栈中剩余节点。
     */
    private fun findFirstPrice(root: AccessibilityNodeInfo): String? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        try {
            while (stack.isNotEmpty()) {
                val node = stack.removeLast()
                try {
                    val text = node.text?.toString()
                    if (PriceNodeMatcher.isPriceNode(node.viewIdResourceName, text)) return text
                    for (i in node.childCount - 1 downTo 0) {
                        node.getChild(i)?.let { stack.addLast(it) }
                    }
                } finally {
                    if (node !== root) node.recycleCompat()
                }
            }
        } finally {
            // 提前 return 时栈中可能残留未访问节点，统一回收防泄漏（root 由调用方回收）
            while (stack.isNotEmpty()) {
                stack.removeLast().recycleCompat()
            }
        }
        return null
    }

    /**
     * §1.1 提取商品标题：字数最多（>8 字）、非价格、非按钮的 TextView 文本。
     * 主线程调用，改为显式队列（BFS）迭代遍历，避免深层 UI 树递归栈溢出；
     * 节点读出子节点后立即回收，队列自然清空无残留。
     */
    private fun extractTitle(root: AccessibilityNodeInfo): String? {
        var best: String? = null
        var bestLen = 0

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            try {
                val text = node.text?.toString()
                val isTextView = node.className?.toString()?.contains("TextView") == true
                val hasClickAction = node.actionList.any {
                    it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK
                }
                if (text != null && isTextView && !hasClickAction &&
                    text.length > bestLen && text.length > 8 &&
                    !CURRENCY_PATTERN.containsMatchIn(text) && !text.contains('\n')
                ) {
                    best = text
                    bestLen = text.length
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.addLast(it) }
                }
            } finally {
                if (node !== root) node.recycleCompat()
            }
        }
        return best?.take(80)
    }

    override fun onInterrupt() {
        // 系统中断服务（如用户关闭权限）：清理浮窗
        OverlayManager.hide()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        OverlayManager.stop()
        super.onDestroy()
    }
}

/** API 33+ recycle 已是 no-op，低版本仍需手动回收 */
private fun AccessibilityNodeInfo.recycleCompat() {
    @Suppress("DEPRECATION")
    if (android.os.Build.VERSION.SDK_INT < 33) recycle()
}
