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
 *  1. 归一化：大小写、全角字母数字、空白与标点全部拉平后做子串判断；
 *  2. 拉丁 token 全覆盖：关键词里的每段字母/数字（如 iphone、15、pro）都必须出现在标题中；
 *  3. 配件反向排除：关键词里没有配件词时，标题含配件词（或"适用xxx"前缀）的条目剔除；
 *  4. 图书反向排除：关键词里没有图书词时，标题形如说明书的条目剔除（guide/manual/指南…）；
 *  5. 品牌反向排除：关键词命中已知品牌时，标题若出现"其它品牌"且不含本品牌任何写法，剔除；
 *  6. 纯中文关键词且未命中品牌时：至少有一段长度 ≥2 的中文词出现在标题中。
 *
 * 所有规则的边界样例见 `QueryRelevanceTest`。
 */
object QueryRelevance {

    /** 配件词（反向排除用；来源：上游实测混入的无关条目类型） */
    private val ACCESSORY_WORDS = listOf(
        "手机壳", "保护壳", "保护套", "手机套", "壳", "钢化膜", "镜头膜", "贴膜", "保护膜", "软膜", "膜",
        "数据线", "充电线", "充电器", "转接头", "转换头", "支架", "挂绳", "挂饰", "贴纸", "表带",
        "收纳", "防尘塞", "卡托", "替换带", "笔尖", "防摔"
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

        // 规则 2：拉丁 token（字母/数字段）必须全部命中
        val tokens = LATIN_TOKEN.findAll(query).map { it.value }.toList()

        // 规则 5：品牌一致性
        val queryBrand = BRANDS.firstOrNull { brand -> brand.any { query.contains(it) } }
        if (queryBrand != null) {
            val titleHasOwnBrand = queryBrand.any { text.contains(it) }
            if (!titleHasOwnBrand) {
                val titleHasOtherBrand = BRANDS.any { it !== queryBrand && it.any { alias -> text.contains(alias) } }
                if (titleHasOtherBrand) return false
            }
        }

        if (tokens.isNotEmpty()) return tokens.all { text.contains(it) }

        // 规则 6：纯中文关键词（有品牌命中时品牌规则已足以判定）
        if (queryBrand != null) return true
        return CJK_RUN.findAll(query).any { text.contains(it.value) }
    }

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
