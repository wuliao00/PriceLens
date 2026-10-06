package com.pricelens.accessibility

import java.util.regex.Pattern

/**
 * **服务视角**的节点树 → [NodeSnapshot]（仅测试侧）。
 *
 * 为什么还需要第二个夹具通道（2026-10-06，§9.27 六 第 3 条、§9.28 一）：
 * [UiAutomatorDump] 读的是 `uiautomator dump` 的 XML，而**同一页**服务通过
 * `rootInActiveWindow` 读到的树与它不是同一棵——真机实测同一张京东迷你详情页，
 * uiautomator 只有 19~21 个节点、服务读到 1279~1291 个，而且边界框归属也不同
 * （uiautomator 把底栏算进来，服务那棵树里没有）。所有判据跑的是后者，
 * 所以拿前者当夹具测出来的"真机行为"是假的。
 *
 * 输入格式由 `PriceMonitorService` 里那段 TEMP-DUMP 产出（不许提交的那段），每个节点一条：
 * `缩进(2空格/层) [Class] [click] b=左,上,右,下 | b=null t="文本" d="描述"`。
 * 节点文本可以含换行（真机的竖排提示就是 `继\n续\n滑\n动…`），所以按"整条记录"匹配而不是按行。
 *
 * 与生产侧的两处**已知差异**（读断言前要知道）：
 *  - `resourceName` 恒为 null（现版京东的 id 全是混淆短名，探针当时没打这个字段）；
 *  - 文本/描述被探针截到 90/70 字（防落盘过大），所以**长商品名在这里比页面上短**。
 *    这会让长度打分的绝对值变小，不影响本文件用例要证的事（b=null 的候选有没有被挡掉）。
 */
internal object ServiceTreeDump {

    private val RECORD = Pattern.compile(
        "^( *)\\[([^\\]]*)\\]( click)? (?:b=(-?\\d+),(-?\\d+),(-?\\d+),(-?\\d+)|b=null) " +
            "t=\"([^\"]*)\" d=\"([^\"]*)\"",
        Pattern.MULTILINE
    )

    /** 读一棵服务视角的树（classpath 的 `fixtures/` 下，文件名带 `svc_` 前缀） */
    fun load(fileName: String): NodeSnapshot {
        val text = checkNotNull(
            ServiceTreeDump::class.java.classLoader?.getResourceAsStream("fixtures/$fileName")
        ) { "缺少夹具 fixtures/$fileName" }.use { it.readBytes().toString(Charsets.UTF_8) }
        return parse(text)
    }

    fun parse(text: String): NodeSnapshot {
        val matcher = RECORD.matcher(text)
        val records = ArrayList<Pair<Int, NodeSnapshot>>()
        while (matcher.find()) {
            val bounds = matcher.group(4)?.let {
                NodeBounds(it.toInt(), matcher.group(5)!!.toInt(), matcher.group(6)!!.toInt(), matcher.group(7)!!.toInt())
            }?.takeUnless { it.isEmpty }
            records.add(
                matcher.group(1).length / 2 to NodeSnapshot(
                    text = matcher.group(8).takeIf { s -> s.isNotEmpty() },
                    contentDescription = matcher.group(9).takeIf { s -> s.isNotEmpty() },
                    className = matcher.group(2),
                    resourceName = null,
                    clickable = matcher.group(3) != null,
                    bounds = bounds
                )
            )
        }
        check(records.isNotEmpty()) { "服务视角夹具没解析出任何节点（格式漂了？）" }
        var index = 0
        fun build(): NodeSnapshot {
            val (depth, head) = records[index]
            index++
            val children = ArrayList<NodeSnapshot>()
            while (index < records.size && records[index].first > depth) children.add(build())
            return head.copy(children = children)
        }
        val root = build()
        check(index == records.size) { "服务视角夹具的缩进不闭合：消费 $index / 共 ${records.size} 条" }
        return root
    }
}
