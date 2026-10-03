package com.pricelens.coupon.adapters

import com.pricelens.accessibility.NodeBounds
import com.pricelens.accessibility.NodeSnapshot
import com.pricelens.accessibility.PriceNodeMatcher
import com.pricelens.accessibility.ancestorChain
import com.pricelens.accessibility.bfs
import com.pricelens.coupon.normalize.Clause
import com.pricelens.coupon.normalize.Normalize
import com.pricelens.coupon.slots.CouponHints
import com.pricelens.coupon.slots.CouponVocabulary

/**
 * 入口三：商品页无障碍节点树。
 *
 * 头号错因是**金额碎片化**：`满` `199` `减` `50` 在无障碍树里是四个兄弟节点。
 * 真机复核（夹具 `jd_home_20260929.xml`）：京东首页把价格拆成 `¥`（bounds 左上 916,1279 右下 935,1313）
 * 与 `19.5`（左上 935,1268 右下 1006,1316）—— x 相接（gap=0）、y 区间重叠；
 * 同列上方的 `省1元`（左上 900,1235 右下 972,1269）与它们 **y 不重叠** ⇒ 不是同一行。
 * 所以拼行只用两种**相对关系**，一个绝对阈值都没有（几何做页面分类已经被 8 棵真树否证过，
 * 见 `NodeBounds` 的注释与 docs/ROADMAP §9.4）：
 *  - y 区间真重叠 ⇒ 同一行；
 *  - 水平间隙 ≤ 两者里较大的**字高** ⇒ 相邻（字高约等于一个字符宽，是自带尺子的相对量）。
 *
 * 三步（顺序即职责）：
 *  1. 筛候选：文本含券形状词 或 resource-id 含 coupon/promotion 的节点才是锚点；
 *  2. 同行合并：把锚点所在的**碎片串**拼回一句。已经自带金额的"整句"节点是**断点**，
 *     绝不与邻居拼接 —— 真机同一行里并排的 `¥13199` / `国补领后价` / `¥14699` 是三个
 *     独立语义单元，拼成一句会让 `领后` 把划线价 14699 判成到手价（假阳性最难查的那种）；
 *  3. 记 [Clause.nodePath]（root 到锚点的子索引链，核验层沿它回点复探）与上下文
 *     （祖先链文案 **+ 同层兄弟文案**：真机"领取"按钮常是券文案节点的兄弟而非祖先）。
 *
 * `bounds == null`（虚拟节点 / 部分 ROM 取不到坐标，本仓库为它专门记过 `c72e8a4`）时
 * **没有几何可依据，就退回同一父节点的兄弟文档顺序当作一行** —— 仍然按"整句"切段，
 * 但绝不整条丢弃：丢了就是漏检，而漏检在真机上看起来就是"券没找到"。
 * 早前这里写的是"无 bounds 的节点自己成句"，代码却把无 bounds 的碎片全删了，两处口径
 * 只剩这一处（拼行），注释已按代码改齐。
 */
object NodeAdapter {

    /**
     * 碎片长度上限（字符数，**不是几何阈值**）：碎片要么没数字，要么短到"半个词"。
     * 取 12 的依据是仓库实测：最短商品名 32 字、真机整句券文案 15 字（「【点击领取】¥70无门槛立减券」）
     * —— 12 落在"整句"与"碎片"中间的空档里，两侧都留有余量（数值出处见注释末尾的夹具）。
     */
    private const val FRAGMENT_MAX_LEN = 12

    /** 自带金额的整句特征：数字 + 运算符词（`满` `减` `折` `到手` `券后`），或数字带货币符号 */
    private val SELF_CONTAINED_OPERATORS = listOf("满", "减", "折", "到手", "券后")

    fun clauses(root: NodeSnapshot, vocabulary: CouponVocabulary = CouponVocabulary.DEFAULT): List<Clause> {
        val out = ArrayList<Clause>()
        val claimed = HashSet<String>()
        forEachNode(root, emptyList()) { node, path ->
            val carriers = carriersOf(node, path)
            if (carriers.isEmpty()) return@forEachNode
            // 去重的单位是**碎片**（carrier 的 path），不是"成句结果"：同一个叶子会被它的父节点
            // 和祖父节点各看见一次（祖父允许下沉一层单壳容器），不去重就会把同一张券抽两遍。
            // 前序遍历保证**靠上的父节点先 claim**，而靠下的父节点的候选池必然是它的子集
            // （只有"独子容器"才会被下沉，子集里不可能有父池没有的碎片）⇒ 先 claim 后判闸
            // 不会把一句拼成缺字的半句：闸在父池没过，子池同样过不了（锚点是池的子集）。
            val fresh = ArrayList<Carrier>(carriers.size)
            for (carrier in carriers) if (claimed.add(carrier.key)) fresh.add(carrier)
            out.addAll(clausesOfSiblings(fresh, root, vocabulary))
        }
        return out
    }

    /**
     * 页面平台：任一节点的 resource-id 暴露宿主包名即可判定；判不出返回 `unknown`（不猜）。
     *
     * 为什么在适配器里判而不是在管线里判：管线只看句子文本，而真机的宿主信息只出现在
     * resource-id（`com.jingdong.app.mall:id/xxx`）这类节点属性上 —— 文本里没有"京东"两个字。
     * 前缀表在 [CouponVocabulary.platformPrefixes]（可被远端规则包整体覆盖），不写死在这里：
     * 接一个新宿主应该是推一条规则，不是发一次 APK。
     */
    fun platform(root: NodeSnapshot, vocabulary: CouponVocabulary = CouponVocabulary.DEFAULT): String {
        for (node in bfs(root)) {
            val name = node.resourceName ?: continue
            val hit = vocabulary.platformPrefixes.firstOrNull { name.startsWith(it.first) } ?: continue
            return hit.second
        }
        return "unknown"
    }

    /**
     * 一个父节点下的文本承载者：直接有文本的子节点，外加"只包一个 TextView 的容器"下沉一层
     * （真机常把每个碎片再包一层 View，不下沉就拼不到一起）。
     * 每个承载者顺带记下**同层其它文本**，供状态/范围判定当上下文用。
     */
    private fun carriersOf(parent: NodeSnapshot, parentPath: List<Int>): List<Carrier> {
        val found = ArrayList<Pair<NodeSnapshot, String>>()
        val paths = ArrayList<List<Int>>()
        for ((index, child) in parent.children.withIndex()) {
            val own = usableText(child)
            if (own != null) {
                found.add(child to own)
                paths.add(parentPath + index)
                continue
            }
            if (child.children.size != 1) continue
            val grand = child.children[0]
            if (grand.children.isNotEmpty()) continue
            val grandText = usableText(grand) ?: continue
            found.add(grand to grandText)
            paths.add(parentPath + listOf(index, 0))
        }
        if (found.isEmpty()) return emptyList()
        return found.mapIndexed { position, entry ->
            val others = ArrayList<String>(found.size - 1)
            found.forEachIndexed { other, pair -> if (other != position) others.add(pair.second) }
            Carrier(entry.first, entry.second, entry.first.bounds, paths[position], others, isSelfContained(entry.second))
        }
    }

    /** 同一父节点：有坐标的按几何聚行，没坐标的整体当一行；两种都再按"整句 / 碎片"切段 */
    private fun clausesOfSiblings(carriers: List<Carrier>, root: NodeSnapshot, vocabulary: CouponVocabulary): List<Clause> {
        val out = ArrayList<Clause>()
        val loose = carriers.filter { it.bounds == null }
        if (loose.isNotEmpty()) out.addAll(clausesOfRow(loose, root, vocabulary))
        val bounded = carriers.filter { it.bounds != null }
        for (row in rowsOf(bounded)) out.addAll(clausesOfRow(row, root, vocabulary))
        return out
    }

    /** 一行之内：整句是断点，剩下的连续碎片拼成一句 */
    private fun clausesOfRow(row: List<Carrier>, root: NodeSnapshot, vocabulary: CouponVocabulary): List<Clause> {
        val out = ArrayList<Clause>()
        var cursor = 0
        while (cursor < row.size) {
            val current = row[cursor]
            if (current.selfContained) {
                val single = listOf(current)
                if (isEmittable(textOf(single), single, vocabulary)) out.add(clauseOf(single, root, vocabulary))
                cursor++
                continue
            }
            var end = cursor
            while (end < row.size && row[end].selfContained.not()) end++
            val run = row.subList(cursor, end)
            if (isEmittable(textOf(run), run, vocabulary)) out.add(clauseOf(run, root, vocabulary))
            cursor = end
        }
        return out
    }

    /** 连通分量聚行：只用 [sameLine]，没有任何"距离小于 N 像素"的拟合常数 */
    private fun rowsOf(bounded: List<Carrier>): List<List<Carrier>> {
        val remaining = ArrayList(bounded)
        val rows = ArrayList<List<Carrier>>()
        while (remaining.isNotEmpty()) {
            val cluster = ArrayList<Carrier>()
            cluster.add(remaining.removeAt(0))
            var grown = true
            while (grown) {
                grown = false
                val iterator = remaining.iterator()
                while (iterator.hasNext()) {
                    val candidate = iterator.next()
                    if (cluster.any { sameLine(it, candidate) }) {
                        iterator.remove()
                        cluster.add(candidate)
                        grown = true
                    }
                }
            }
            rows.add(cluster.sortedBy { it.bounds?.left ?: 0 })
        }
        return rows
    }

    /** 同行 = y 区间真重叠（不看"接近"，不给容差） */
    private fun sameLine(a: Carrier, b: Carrier): Boolean {
        val left = a.bounds ?: return false
        val right = b.bounds ?: return false
        if (maxOf(left.top, right.top) >= minOf(left.bottom, right.bottom)) return false
        return horizontallyAdjacent(left, right)
    }

    /**
     * 相邻 = 两矩形之间的水平间隙 ≥0（重叠摆放不算"同一行的下一格"）
     * 且 ≤ 较大的那个**字高**：一个字高≈一个字符宽，间隙超过它说明中间空出一列。
     */
    private fun horizontallyAdjacent(a: NodeBounds, b: NodeBounds): Boolean {
        val first = if (a.left <= b.left) a else b
        val second = if (first === a) b else a
        return second.left - first.right <= maxOf(a.height, b.height) && second.left >= first.right
    }

    private fun isAnchor(carrier: Carrier, vocabulary: CouponVocabulary): Boolean =
        CouponHints.looksLikeCouponText(carrier.text, vocabulary) ||
            CouponHints.looksLikeCouponResource(carrier.node.resourceName, vocabulary)

    /**
     * 这一行能不能成句：句里有券形状锚点（任务书 #5 第 1 步的候选闸），**或**拼出来的是
     * "货币符号 + 数字"的价格行。
     *
     * 放开价格行的唯一理由是让"拼行"这个动作在产出里**看得见**：真机
     * `jd_home_20260929.xml` 里 `¥`[916,1279][935,1313] 与 `19.5`[935,1268][1006,1316] 这一对
     * 兄弟一个券词都没有，纯券词闸会把整行丢掉，于是"合并成功"与"什么都没找到"两种结果
     * 在测试里长得一模一样（只有拒绝分支的量具看不出差别）。放开它不会多造券：
     * 券槽位要有**角色词**才成立（见 `CouponPipeline.consume`），`¥19.5` 这种句子一个槽位也填不出来。
     */
    private fun isEmittable(text: String, run: List<Carrier>, vocabulary: CouponVocabulary): Boolean =
        run.any { isAnchor(it, vocabulary) } ||
            (PriceNodeMatcher.hasCurrency(text) && text.any { it.isDigit() })

    /**
     * 整句（拼行断点）：长度超限 / 含空格 / 数字带货币符号 / 数字带运算符词。
     * `199`、`减`、`¥`、`50元券` 都不是整句（要继续拼）；`满4999减300`、`¥13199` 是整句。
     *
     * **只看形状**，不看"这句话像不像在说券"：早期版本在这里额外 and 了券形状词，
     * 于是 `¥13199`（没有券词）掉回碎片通道，与同一行的 `国补领后价`、`¥14699` 拼成一句 ——
     * 那正是类注释举的"领后把划线价判成到手价"的假阳性。断点判据与候选闸是两件事，各留一处。
     */
    private fun isSelfContained(text: String): Boolean {
        if (text.length > FRAGMENT_MAX_LEN || text.contains(' ')) return true
        if (text.any { it.isDigit() }.not()) return false
        if (PriceNodeMatcher.hasCurrency(text)) return true
        return SELF_CONTAINED_OPERATORS.any { text.contains(it) }
    }

    /** 一句 = 碎片按行内顺序直接拼接（真机碎片之间没有空格，拼完才是人眼看的那一句） */
    private fun textOf(run: List<Carrier>): String = run.joinToString(separator = "") { it.text }

    /** 一句的出处：锚点=句里第一个券形状碎片（价格行没有锚点，取行首），路径与上下文都从它取 */
    private fun clauseOf(run: List<Carrier>, root: NodeSnapshot, vocabulary: CouponVocabulary): Clause {
        val anchor = run.firstOrNull { isAnchor(it, vocabulary) } ?: run.first()
        val context = ArrayList<String>()
        ancestorChain(root, anchor.node).dropLast(1).forEach { ancestor -> usableText(ancestor)?.let { context.add(it) } }
        context.addAll(anchor.siblingTexts)
        return Clause(text = textOf(run), nodePath = anchor.path, ancestors = context)
    }

    /** text 优先、contentDescription 兜底；再过读屏角色剥离与规整（与抽取层同一口径） */
    internal fun usableText(node: NodeSnapshot): String? {
        val raw = node.text?.takeIf { it.isNotBlank() } ?: node.contentDescription?.takeIf { it.isNotBlank() }
            ?: return null
        val cleaned = PriceNodeMatcher.cleanTitle(raw) ?: return null
        return Normalize.text(cleaned).takeIf { it.isNotEmpty() }
    }

    /** 先序遍历，带 root 起的子索引链（root 自身路径为空表） */
    private fun forEachNode(node: NodeSnapshot, path: List<Int>, visit: (NodeSnapshot, List<Int>) -> Unit) {
        visit(node, path)
        node.children.forEachIndexed { index, child -> forEachNode(child, path + index, visit) }
    }

    /**
     * 一个文本承载者。
     * [path] 是从整棵树 root 开始的子索引链（不是相对父节点），所以可以直接进 [Clause.nodePath]。
     * [selfContained] 由 [isSelfContained] 在构造处一次算定 —— 类里不再按第二个口径重算，
     * 否则"拼行断点"取决于哪一处赢，而现在两处都活着。
     */
    internal class Carrier(
        val node: NodeSnapshot,
        val text: String,
        val bounds: NodeBounds?,
        val path: List<Int>,
        val siblingTexts: List<String>,
        val selfContained: Boolean
    ) {
        val key: String get() = path.joinToString("-")
    }
}
