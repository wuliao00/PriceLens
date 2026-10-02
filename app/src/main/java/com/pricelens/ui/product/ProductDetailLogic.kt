package com.pricelens.ui.product

import com.pricelens.accessibility.PriceNodeMatcher
import com.pricelens.util.SearchQueryCleaner
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * §十一 商品详情页的纯逻辑：评测关键词派生、发布时间窗口、B 站跳转目标选择。
 *
 * 这里刻意不放任何 Android 依赖（`PriceNodeMatcher.cleanTitle` 与 [SearchQueryCleaner]
 * 都是纯 Kotlin 文本规则），所以三条行为都能在 JVM 单测里直接断言，
 * 见 `app/src/test/java/com/pricelens/ui/product/ProductDetailLogicTest.kt`。
 */

/** 详情页「近半年」筛选开关使用的窗口月数（与开关文案同源，改这里即改开关） */
const val REVIEW_WINDOW_MONTHS = 6

/** B 站 App 深链前缀（装了 App 时直达播放器；未注册该 scheme 时系统会给回退页） */
const val BILI_DEEP_LINK_PREFIX = "bilibili://video/"

/** B 站网页兜底前缀（没装 App / 用户关掉深链时走这条） */
const val BILI_WEB_PREFIX = "https://www.bilibili.com/video/"

/** B 站包名（判断"装没装"用，与 [com.pricelens.util.UrlOpener] 的 RULES 同源） */
const val BILI_PACKAGE = "tv.danmaku.bili"

/** 链接整段：商详链接里只剩 SKU 数字，拿去搜"评测"只会命中带货号，必须先整段删掉 */
private val URL_TEXT = Regex("[A-Za-z][A-Za-z0-9+.-]*://\\S+")

/** 全角/半角圆括号内容（【】与 [] 由 [SearchQueryCleaner] 负责，这里只补它没覆盖的两种） */
private val BRACKET_ROUND = Regex("（[^）]*）|\\([^)]*\\)")

/** 营销词：与商品是什么无关、只与"怎么卖"有关，对评测搜索纯噪声 */
private val MARKETING_WORDS = listOf(
    "京东自营", "京东旗舰店", "旗舰店", "官方旗舰", "官方", "正品", "包邮", "现货",
    "秒杀", "抢购", "特惠", "满减", "领券", "拍下", "顺丰", "分期免息", "质保", "百亿补贴"
)
private val MARKETING = Regex(MARKETING_WORDS.joinToString("|"))

/** 数字紧跟量词（12包3层 / 42码 / 500ml）：必须"数字 + 量词"连着才算规格，避免拆坏 "Mate 8" */
private val COUNTER_RUN =
    Regex("\\d+(?:\\.\\d+)?\\s*(?:包|件|支|盒|袋|瓶|条|双|只|片|卷|层|码|号|寸|升|斤|ml|ML)装?")

/**
 * 整词命中的颜色词才删：`1-4 个汉字 + 颜色字`（曜石黑 / 远峰蓝 / 星空黑）或 `…色`（金色 / 中国色）。
 * 刻意不含"金"（否则"黄金"被吃掉）、也不做子串匹配（否则"福鼎老白茶"这类粘连词被拆坏）。
 */
private val COLOR_TOKEN = Regex(
    "[\\u4e00-\\u9fff]{1,4}(?:黑|白|蓝|绿|粉|紫|灰|银|红|橙|棕|青)|(?:[\\u4e00-\\u9fff]{1,4}|[A-Za-z])色"
)

/** 版本/促销后缀（标准版 / 青春版 / 新款 / 爆款）：同样只按整词删 */
private val VARIANT_TOKEN = Regex(
    "[\\u4e00-\\u9fffA-Za-z0-9]{0,6}(?:标准版|豪华版|青春版|增强版|尊享版|至尊版|旗舰版|高配版|低配版|轻享版|新款|爆款|热销)"
)

/**
 * 商品标题 / 分享链接文本 → 适合 B 站「评测」搜索的关键词。
 *
 * 只做"减法"，不加"评测"这类后缀词：详情页的关键词一旦改写会同时影响历史价/券的查询
 * （全站共用一个关键词，见 [com.pricelens.ui.overview.SearchViewModel]），
 * 附加词由调用方自己决定。
 *
 * 步骤（每步都可单测）：
 *  1. 删链接整段；2. 删零宽/不可见控制字符并压缩空白（复用 [PriceNodeMatcher.cleanTitle]，
 *  真机京东用 U+200B 填充标题防爬）；3. 删圆括号内容；4. 删营销词；5. 删数字量词规格；
 *  6. 整词删颜色 / 版本后缀；7. 交给既有的 [SearchQueryCleaner.clean] 收尾
 *  （促销前缀、`12GB+256GB` 组合容量、尺寸单位、尾部颜色、**按 token 边界限长**）；
 *  8. 结果里一个字母/汉字都不剩（纯 SKU、纯符号）时返回空串 —— 宁可不搜，也不拿数字去猜商品。
 *
 * 返回空串表示"派生不出关键词"，调用方应隐藏重搜入口而不是搜一个空关键词。
 */
fun reviewKeyword(raw: String, maxLen: Int = SearchQueryCleaner.DEFAULT_MAX_LEN): String {
    var s = URL_TEXT.replace(raw, " ")
    s = PriceNodeMatcher.cleanTitle(s) ?: return ""
    s = BRACKET_ROUND.replace(s, " ")
    s = MARKETING.replace(s, " ")
    s = COUNTER_RUN.replace(s, " ")
    s = s.split(' ').filterNot { COLOR_TOKEN.matches(it) || VARIANT_TOKEN.matches(it) }.joinToString(" ")
    val cleaned = SearchQueryCleaner.clean(s, maxLen) ?: return ""
    return cleaned.takeIf { it.any(Char::isLetter) }.orEmpty()
}

/**
 * 发布时间是否落在 `now` 往前 [months] 个日历月之内（UTC 口径，纯 epoch 秒运算）。
 *
 *  - 边界**含端点**：恰好等于窗口起点的算"在窗口内"。
 *  - `pubdateEpochSec <= 0` 表示接口没给发布时间（B 站部分响应确实没有），按"不知道"处理并**保留**，
 *    否则「近半年」开关一按就可能把整列表清成空。
 *  - `months <= 0` 退化为"只看还没发布的"，等价于只放过 `pubdate >= now`（调用方不该传，兜底不崩）。
 */
fun withinRecentWindow(pubdateEpochSec: Long, nowEpochSec: Long, months: Int): Boolean {
    if (pubdateEpochSec <= 0L) return true
    if (months <= 0) return pubdateEpochSec >= nowEpochSec
    // Instant 本身不认 MONTHS（只有日期维度的类才行）→ 落到 UTC 日历上减月，再回到 epoch 秒。
    // 用 UTC 而不是设备时区：窗口的可测性优先，半年级筛选差几小时不影响结论。
    val start = Instant.ofEpochSecond(nowEpochSec)
        .atZone(ZoneOffset.UTC)
        .minus(months.toLong(), ChronoUnit.MONTHS)
        .toEpochSecond()
    return pubdateEpochSec >= start
}

/**
 * 打开一条 B 站视频该走哪条路：装了 B 站 App 且用户没关掉深链 → 原生深链直达播放器；
 * 否则回落 https（修复"恒为 https、只能开浏览器"）。
 *
 * [bvid] 为空时给出 url 为空串的 Web，调用方据此不跳转（[com.pricelens.util.UrlOpener] 也会自行短路）。
 */
fun biliTarget(bvid: String, appInstalled: Boolean, allowDeepLink: Boolean): BiliTarget {
    val id = bvid.trim()
    if (id.isEmpty()) return BiliTarget.Web("")
    return if (appInstalled && allowDeepLink) {
        BiliTarget.DeepLink(BILI_DEEP_LINK_PREFIX + id)
    } else {
        BiliTarget.Web(BILI_WEB_PREFIX + id)
    }
}

/** 视频跳转目标（只有 URL，不碰 Android API，便于 JVM 单测） */
sealed class BiliTarget {

    abstract val url: String

    /** 原生深链：`bilibili://video/BV…` */
    data class DeepLink(override val url: String) : BiliTarget()

    /** 网页兜底：`https://www.bilibili.com/video/BV…`（url 为空 = 没有可跳的目标） */
    data class Web(override val url: String) : BiliTarget()
}
