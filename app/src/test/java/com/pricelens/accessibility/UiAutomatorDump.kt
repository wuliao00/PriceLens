package com.pricelens.accessibility

import java.io.ByteArrayInputStream
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * 真机 `uiautomator dump` XML → [NodeSnapshot] 转换器（**仅测试侧**，A2 真机回归补齐）。
 *
 * 为什么要它：[NodeFixtures] 的快照全是手工按结构构造的，只能证明"我想象中的京东首页
 * 不会触发规则"。本转换器把 2026-09-29 从真机（vivo V2156A）抓下来的**真实节点树**
 * 直接喂给 [isProductPage] / [extractPriceHit] / [extractTitle]，见 [RealDumpGatingTest]。
 *
 * 字段映射（XML 属性 → AccessibilityNodeInfo → [NodeSnapshot]）：
 *  - `text`          → `node.text`                    → [NodeSnapshot.text]
 *  - `content-desc`  → `node.contentDescription`      → [NodeSnapshot.contentDescription]
 *  - `class`         → `node.className`               → [NodeSnapshot.className]
 *  - `resource-id`   → `node.viewIdResourceName`      → [NodeSnapshot.resourceName]
 *  - `clickable`     → `node.isClickable`             → [NodeSnapshot.clickable]
 *  空串一律折成 null：uiautomator 把 null 属性序列化成 `text=""`，而生产侧读到的就是 null。
 *
 * **与生产代码在 clickable 上的差异**（务必知道再读断言）：
 * [PriceMonitorService] 的 `toSnapshot` 取的是
 * `node.isClickable || node.actionList.any { it.id == ACTION_CLICK }`；
 * uiautomator dump **不序列化 actionList**，本映射只能覆盖 `isClickable` 这一个来源，
 * 因此测试快照的 clickable 是生产语义的**子集**（可能少报、绝不虚报）。
 * 影响面只有 [extractTitle] 三级启发式的 -4 打分和 [pickSameCardTitleAndPrice] 的卡片筛选；
 * [isProductPage] 的全部判据（文本特征 + 价格 + 标题）不读 clickable，故门控结论不受该差异影响。
 *
 * 预算复刻：生产侧 `snapshotOf` 带 `MAX_SNAPSHOT_NODES = 4000` / `MAX_SNAPSHOT_DEPTH = 64`
 * （[PriceMonitorService] 私有 companion，值在此原样复制）。递归顺序、扣预算时机与生产一致，
 * 保证测试判定的那棵树 == 生产实际看到的那棵树；截断前后节点数由 [RealDump] 暴露给用例打印。
 */
internal const val REAL_DUMP_MAX_SNAPSHOT_NODES = 4000
internal const val REAL_DUMP_MAX_SNAPSHOT_DEPTH = 64

/** 一棵真机 dump：截断前的原始规模 + 按生产预算截断后实际进判定的树 */
internal class RealDump(
    val name: String,
    val root: NodeSnapshot,
    /** dump 里 `<node>` 元素总数（截断前） */
    val rawNodeCount: Int,
    /** 按生产预算构建出来的快照节点数（截断后） */
    val snapshotNodeCount: Int,
    val rawMaxDepth: Int,
    val snapshotMaxDepth: Int
) {
    val truncated: Boolean get() = snapshotNodeCount < rawNodeCount

    fun describeBudget(): String =
        "$name: 截断前 rawNodeCount=$rawNodeCount rawMaxDepth=$rawMaxDepth -> " +
            "按生产预算(节点 ${REAL_DUMP_MAX_SNAPSHOT_NODES}/深度 ${REAL_DUMP_MAX_SNAPSHOT_DEPTH}) " +
            "截断后 snapshotNodeCount=$snapshotNodeCount snapshotMaxDepth=$snapshotMaxDepth " +
            "truncated=$truncated"
}

/** 从测试 classpath 的 `fixtures/` 读一棵真机 dump（沿用既有夹具的读取方式） */
internal fun loadRealDump(fileName: String): RealDump {
    val stream = checkNotNull(
        RealDump::class.java.classLoader?.getResourceAsStream("fixtures/$fileName")
    ) { "缺少夹具 fixtures/$fileName" }
    return stream.use { parseRealDump(fileName, it) }
}

/** 从内存里的 dump 文本读一棵树（预算用例的自造 dump 走这条路） */
internal fun parseRealDumpFromXml(name: String, xml: String): RealDump =
    parseRealDump(name, ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

private const val A_TEXT = "text"
private const val A_DESC = "content-desc"
private const val A_CLASS = "class"
private const val A_RES = "resource-id"
private const val A_CLICKABLE = "clickable"

internal fun parseRealDump(name: String, stream: InputStream): RealDump {
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = false
    factory.isValidating = false
    factory.isIgnoringComments = true
    // 夹具是自家产出的本地文件，仍按最小权限关掉 DTD/外部实体，避免"解析外部内容"这一类隐患
    runCatching {
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }
    runCatching {
        factory.setFeature("http://javax.xml.XMLConstants/feature/secure-processing", true)
    }
    val doc = factory.newDocumentBuilder().parse(stream)
    val rootElement = childElements(doc.documentElement).firstOrNull()
        ?: error("$name: dump 里没有 <node>")
    val (rawCount, rawDepth) = measureTree(rootElement)
    val built = snapshotOf(rootElement, 0, intArrayOf(REAL_DUMP_MAX_SNAPSHOT_NODES))
    val (snapshotCount, snapshotDepth) = measureSnapshot(built)
    return RealDump(name, built, rawCount, snapshotCount, rawDepth, snapshotDepth)
}

/** 与 `PriceMonitorService.snapshotOf` 逐步同构：深度与节点预算的扣减时机完全一致 */
private fun snapshotOf(element: Element?, depth: Int, budget: IntArray): NodeSnapshot {
    if (element == null) return NodeSnapshot(null, null, null, null, false, emptyList())
    val text = attr(element, A_TEXT)
    val desc = attr(element, A_DESC)
    val className = attr(element, A_CLASS)
    val resourceName = attr(element, A_RES)
    val clickable = attr(element, A_CLICKABLE) == "true"
    val elements = childElements(element)
    val children = ArrayList<NodeSnapshot>(elements.size)
    if (depth < REAL_DUMP_MAX_SNAPSHOT_DEPTH && budget[0] > 0) {
        for (child in elements) {
            if (budget[0] <= 0) break
            budget[0]--
            children.add(snapshotOf(child, depth + 1, budget))
        }
    }
    return NodeSnapshot(text, desc, className, resourceName, clickable, children)
}

/** 空串按生产语义折成 null（uiautomator 把缺失属性序列化成 `attr=""`） */
private fun attr(element: Element, key: String): String? =
    element.getAttribute(key).takeIf { it.isNotEmpty() }

private fun childElements(element: Element): List<Element> {
    val out = ArrayList<Element>()
    var cursor: Node? = element.firstChild
    while (cursor != null) {
        // 关掉命名空间处理后 getLocalName() 会是 null，这里按 uiautomator 的固定标签名匹配
        if (cursor.nodeType == Node.ELEMENT_NODE && (cursor as Element).tagName == "node") out.add(cursor)
        cursor = cursor.nextSibling
    }
    return out
}

private fun measureTree(root: Element): Pair<Int, Int> {
    var count = 0
    var maxDepth = 0
    val stack = ArrayDeque<Pair<Element, Int>>()
    stack.addLast(root to 0)
    while (stack.isNotEmpty()) {
        val (element, depth) = stack.removeLast()
        count++
        if (depth > maxDepth) maxDepth = depth
        childElements(element).forEach { stack.addLast(it to depth + 1) }
    }
    return count to maxDepth
}

private fun measureSnapshot(root: NodeSnapshot): Pair<Int, Int> {
    var count = 0
    var maxDepth = 0
    val queue = ArrayDeque<Pair<NodeSnapshot, Int>>()
    queue.addLast(root to 0)
    while (queue.isNotEmpty()) {
        val (node, depth) = queue.removeFirst()
        count++
        if (depth > maxDepth) maxDepth = depth
        node.children.forEach { queue.addLast(it to depth + 1) }
    }
    return count to maxDepth
}

/**
 * 树内所有非空可读文本（text + contentDescription），与生产侧 `subtreeTexts` 同一口径。
 * 用例只用它来 dump 判据、不改判据本身。
 */
internal fun dumpTexts(root: NodeSnapshot): List<String> {
    val out = ArrayList<String>()
    val queue = ArrayDeque<NodeSnapshot>()
    queue.addLast(root)
    while (queue.isNotEmpty()) {
        val node = queue.removeFirst()
        node.text?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        node.contentDescription?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        node.children.forEach { queue.addLast(it) }
    }
    return out
}
