package com.pricelens.util

/**
 * 搜索结果相关性过滤（2026-09 新增，修复"接口内容不准确"）。
 *
 * 背景：上游站点（什么值得买 / 当当 / 识货）的关键词搜索会把无关条目混进结果——
 * 通用配件（壳/膜/线）、图书（"iPhone 15 User Guide"）、其它品牌、甚至站点热榜商品。
 * 旧实现直接取"第一条带价格的条目"当商品候选，于是搜 "iPhone 15" 可能得到手机壳或
 * 英文说明书；《当当》之于数码品类尤其明显（图书/配件库存占比大）。
 *
 * 规则（保守优先，宁可少过滤不可错杀）：
 *  1. 归一化：大小写、全角字母数字拉平，标点/空白丢弃；
 *  2. 拉丁段**按序**覆盖（2026-09-28 重写，见下）：关键词按非"字母数字汉字"切成段，
 *     每段（归一化后取其中的字母数字串）都必须在标题中出现，且保持原顺序、
 *     相邻段之间最多允许 [MAX_SEGMENT_GAP] 个字符的间隔；
 *  3. 配件反向排除：关键词里没有配件词时，标题含配件词（或"适用xxx"前缀）的条目剔除；
 *  4. 图书反向排除：关键词里没有图书词时，标题形如说明书的条目剔除（guide/manual/指南…）；
 *  5. 品牌反向排除：关键词命中已知品牌时，标题若出现"其它品牌"且不含本品牌任何写法，剔除；
 *  6. 纯中文关键词且未命中品牌时：至少有一段长度 ≥2 的中文词出现在标题中。
 *
 * 为什么重写规则 2（真实 bug）：旧实现先 [normalize] 删掉所有空白再切 token，
 * `"oppo x8s"` 归一化成 `oppox8s` → 变成**一个必须整体连续出现的巨型 token**；
 * 而站点标题写的是 `OPPO Find X8s`（归一化 `oppofindx8s`，不含 `oppox8s`）→ 判不相关，
 * 于是当当/值得买全被过滤 → 无候选 → 历史价 URL 为 null → 盯价入口隐藏
 * （用户原话"搜 oppo x8s 和 mate 80 显示不准确、跟踪错误"）。
 * 分段后 `oppo x8s` → [`oppo`,`x8s`]，间隔 `find`（4 字符）≤ G=12 → 命中；
 * 顺序 + 间隔上限又挡住了"巧合串"（实测样例 `EG80MATE33S` 含 `mate`+`80` 但顺序颠倒）；
 * `Mate80` / `Mate 80` / `MATE 80` 互认，不需要机型别名词表。
 *
 * 所有规则的边界样例见 `QueryRelevanceTest`；桌面端同规则实现见
 * `desktop/src/main/utils/relevance.js`（用例同名同内容，见 `desktop/_unit_check.js`）。
 */
object QueryRelevance {

    /**
     * 配件词（反向排除用；来源：上游实测混入的无关条目类型）。
     * 2026-09-28 追加 6 项：放宽规则 2 后实况里唯一新增的漏网条目是
     * "怎么挑适用华为mate50荣耀80Pro GT无线蓝牙骨传导耳机不入耳运动"（耳机），
     * 同批实测还把 充电宝/移动电源/电池/保护贴/手机膜 归为同一类混入项。
     * 已知副作用：`电池` 也出现在部分整机标题里。7 个关键词 × 2 源的实况池子里它只多剔掉一条
     * 「华为 新品mate80pro手机可选】2026新品手机90Pro Max …」——本身就是多机型山寨混卖链接，
     * 剔除可接受（宁可少一条，也不让历史价盯到山寨链接）。
     */
    private val ACCESSORY_WORDS = listOf(
        "手机壳", "保护壳", "保护套", "手机套", "壳", "钢化膜", "镜头膜", "贴膜", "保护膜", "软膜", "膜",
        "数据线", "充电线", "充电器", "转接头", "转换头", "支架", "挂绳", "挂饰", "贴纸", "表带",
        "收纳", "防尘塞", "卡托", "替换带", "笔尖", "防摔",
        "耳机", "充电宝", "移动电源", "电池", "保护贴", "手机膜"
    )

    /** "适用 xxx" 前缀是第三方配件的强信号（正品商品标题极少以此开头） */
    private val ACCESSORY_PREFIX = Regex("^[\\s【\\[（(]*适用")

    /** 图书/说明书词（当当等书城站点会把导购书混进商品搜索） */
    private val BOOK_WORDS = listOf("指南", "手册", "教程", "攻略", "宝典", "说明书")

    /** 英文图书词用词边界匹配，避免误伤 "guideline" 之类无关子串 */
    private val BOOK_WORD_ASCII = Regex(
        "(?:^|[^a-z])(guide|guides|guidebook|manual|handbook|mastery|unboxed|essentials)(?:[^a-z]|$)"
    )

    /**
     * 品牌别名表：`该品牌在标题里的常见写法`。
     * 只收录能安全做子串匹配的写法（避免 gree/green、mi/mini 这类误报）。
     */
    private val BRANDS: List<List<String>> = listOf(
        listOf("iphone", "apple", "苹果", "ipad", "macbook", "airpods"),
        listOf("小米", "红米", "redmi", "xiaomi"),
        listOf("华为", "huawei"),
        listOf("荣耀", "honor"),
        listOf("三星", "samsung"),
        listOf("oppo"),
        listOf("vivo"),
        listOf("一加", "oneplus"),
        listOf("魅族", "meizu"),
        listOf("索尼", "sony"),
        listOf("任天堂", "nintendo", "switch"),
        listOf("大疆", "dji"),
        listOf("戴森", "dyson"),
        listOf("美的", "midea"),
        listOf("格力"),
        listOf("海尔", "haier"),
        listOf("罗技", "logitech"),
        listOf("漫步者", "edifier"),
        listOf("安克", "anker")
    )

    private val LATIN_TOKEN = Regex("[a-z0-9]+")
    private val CJK_RUN = Regex("[\u4e00-\u9fff]{2,}")

    /** 切段分隔符：非"字母 / 数字 / 汉字"（空白、标点、连字符、全角符号都算分隔） */
    private val WORD_SPLIT = Regex("[^0-9A-Za-z\u4e00-\u9fff]+")

    /**
     * 相邻两段之间允许的最大间隔（归一化后的字符数）。
     * 依据 2026-09-28 实况回放（PriceLens-probe/out/rule_variants2.txt）：
     * `OPPO Find X8s` 的间隔是 `find` = 4 字符，全部命中样例 ≤ 5；
     * 而"跨机型/巧合串"样例如 `OPPO Find N6 折叠屏 官方旗舰正品店 X8s`（间隔 15）需要挡住。
     * G=12 是同时满足这两点的最大安全值；G=0 即退化为旧的"整串连续子串"行为。
     */
    internal const val MAX_SEGMENT_GAP = 12

    /** 条目标题是否与关键词相关（关键词为空或标题为空一律视为不相关） */
    fun isRelevant(keyword: String, title: String): Boolean {
        val query = normalize(keyword)
        val text = normalize(title)
        if (query.isEmpty() || text.isEmpty()) return false

        // 规则 3：关键词没提配件，标题却在卖配件（"适用 xxx" 前缀同属强配件信号）
        val queryHasAccessory = hasWord(query, ACCESSORY_WORDS)
        if (!queryHasAccessory) {
            if (hasWord(text, ACCESSORY_WORDS)) return false
            if (ACCESSORY_PREFIX.containsMatchIn(title.trim())) return false
        }

        // 规则 4：关键词不是书，标题却是说明书/导购书（英文词用原串 + 词边界判断）
        val titleLower = title.lowercase()
        val queryIsBookish = hasWord(query, BOOK_WORDS) || BOOK_WORD_ASCII.containsMatchIn(keyword.lowercase())
        if (!queryIsBookish && (hasWord(text, BOOK_WORDS) || BOOK_WORD_ASCII.containsMatchIn(titleLower))) {
            return false
        }

        // 规则 5：品牌一致性
        val queryBrand = brandOf(query)
        if (queryBrand != null) {
            val titleHasOwnBrand = hasAnyAlias(queryBrand, text)
            if (!titleHasOwnBrand) {
                val titleHasOtherBrand = BRANDS.any { it !== queryBrand && hasAnyAlias(it, text) }
                if (titleHasOtherBrand) return false
            }
        }

        // 规则 2：拉丁段按序覆盖（空段 = 纯中文关键词，走规则 6）
        val segments = querySegments(keyword)
        if (segments.isNotEmpty()) return matchesSegmentsInOrder(segments, text, MAX_SEGMENT_GAP)

        // 规则 6：纯中文关键词（有品牌命中时品牌规则已足以判定）
        if (queryBrand != null) return true
        return CJK_RUN.findAll(query).any { text.contains(it.value) }
    }

    /**
     * 关键词的拉丁段（规则 2 的匹配单位）。
     * **先按分隔符切原串，再逐段归一化取字母数字**——顺序反过来（旧实现）会让
     * `oppo x8s` 变成单段 `oppox8s`，从而要求标题里出现连续字面。
     * 例：`oppo x8s` → [`oppo`,`x8s`]；`Mate80` → [`mate80`]；`华为 Mate 80` → [`mate`,`80`]。
     */
    internal fun querySegments(keyword: String): List<String> =
        WORD_SPLIT.split(keyword).flatMap { part -> LATIN_TOKEN.findAll(normalize(part)).map { it.value } }

    /** 标题是否按序包含关键词的每一段（间隔 ≤ [maxGap]）；纯中文关键词返回 true（交给规则 6） */
    internal fun matchesSegments(keyword: String, title: String, maxGap: Int = MAX_SEGMENT_GAP): Boolean {
        val segments = querySegments(keyword)
        if (segments.isEmpty()) return true
        return matchesSegmentsInOrder(segments, normalize(title), maxGap)
    }

    /**
     * 顺序 + 间隔约束的段匹配：每段尝试其在标题里的**所有**出现位置（回溯），
     * 避免"先出现一次的干扰子串"把后面正确的段卡死。
     */
    private fun matchesSegmentsInOrder(segments: List<String>, text: String, maxGap: Int, index: Int, prevEnd: Int): Boolean {
        if (index == segments.size) return true
        val segment = segments[index]
        var pos = text.indexOf(segment)
        while (pos >= 0) {
            val gapOk = prevEnd < 0 || pos - prevEnd in 0..maxGap
            if (gapOk && matchesSegmentsInOrder(segments, text, maxGap, index + 1, pos + segment.length)) return true
            pos = text.indexOf(segment, pos + 1)
        }
        return false
    }

    /** 默认间隔版本的重载入口（内部递归见带 index/prevEnd 的实现） */
    private fun matchesSegmentsInOrder(segments: List<String>, text: String, maxGap: Int): Boolean =
        matchesSegmentsInOrder(segments, text, maxGap, 0, -1)

    /** 关键词命中的品牌组（未命中返回 null）；候选打分用它判断"标题缺本品牌写法" */
    internal fun brandOf(normalizedQuery: String): List<String>? =
        BRANDS.firstOrNull { brand -> brand.any { normalizedQuery.contains(it) } }

    /** 标题（归一化）里是否出现了该品牌组的任一写法 */
    internal fun hasAnyAlias(aliases: List<String>, normalizedText: String): Boolean = aliases.any { normalizedText.contains(it) }

    private fun hasWord(normalized: String, words: List<String>): Boolean = words.any { normalized.contains(it) }

    /** 归一化：去空白、全角转半角、小写（中文与字母数字保留，标点丢弃） */
    internal fun normalize(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            val c = when (ch) {
                in '\uFF01'..'\uFF5E' -> (ch.code - 0xFEE0).toChar() // 全角 ASCII → 半角
                '\u3000' -> ' '
                else -> ch
            }
            if (c.isLetterOrDigit()) sb.append(c.lowercaseChar())
        }
        return sb.toString()
    }
}
