package com.pricelens.coupon.normalize

/** 链接候选的形态（只到"字符串级"，本版**不展开、不请求**） */
enum class LinkKind {
    /** 淘口令：`￥xxxx￥`（要展开才知道指向哪个商品，展开属于网络层） */
    TB_TOKEN,

    /** 短链分享：`m.tb.cn` / `yangkeduo.com` / `pinduoduo.com` 开头的分享串 */
    SHORT_LINK,

    /** 明文商品链：`item.taobao.com` / `item.jd.com`（可直接抽 itemRef） */
    ITEM_LINK
}

/**
 * 一条链接候选：[value] 是**原样截出的字符串**（未解码、未展开、未补协议头）。
 *
 * 刻意不带任何"已解析出的商品 ID"字段：那是网络层展开之后才允许出现的信息，
 * 放在这一层会诱导后续任务把"看起来像 ID 的字符"当确定性 ID 用。
 */
data class LinkCandidate(val kind: LinkKind, val value: String)

/**
 * 分享链接 / 淘口令的**字符串级**抽取。
 *
 * 红线（本版必须守住）：**绝不发 HTTP**。淘口令与短链只被"认出来 + 原样带着"，
 * HEAD 展开、302 追链、从展开结果里读 sku 都留给网络层；
 * 因此本对象不 import 任何网络类，也没有任何 IO —— 评审时只需确认这一点。
 *
 * 三个来源共用（剪贴板文案 / 社区帖正文 / 商品页节点的文本叶子），
 * 这也是 [com.pricelens.coupon.model.CouponSlot.url] 与
 * [com.pricelens.coupon.model.Extraction.platform] 的唯一填法。
 */
object Links {

    /**
     * 淘口令：`￥` 包起的一串 alnum 字符（2..32 个），且**必须含字母**（[decode] 里逐条校验）。
     *
     * 只认 `￥` 这一种包裹符（任务书口径）；真机见过的 `$xxx$` / `€xxx€` 变体等有样本再加。
     * "含字母"这条形状判据是必须的：`￥19.9` 这类**价格**也是"￥ + 数字"形态，
     * 光靠长度下限挡不住它，会把一行价格抽成一条"口令"（正/负样本钉子见 LinksTest）。
     * 同理不能写"整串里某处有字母就行"的先行断言 —— 通配符能一直看到整句末尾，
     * 只要句子后面还有字母就形同虚设。
     */
    private val TB_TOKEN = Regex("￥([A-Za-z0-9+/]{2,32})￥")

    /** 域名 + 后续非空白/非中文标点，直到句读为止（分享串常把参数直接跟在句子里） */
    private val SHARE_LINK = Regex("(?:m\\.tb\\.cn|item\\.taobao\\.com|item\\.jd\\.com|yangkeduo\\.com|pinduoduo\\.com)[^\\s，。]*")

    /** 平台线索 → `jd|taobao|pdd|unknown`（宿主集合不硬编码成枚举，理由见 Extraction.platform 注释） */
    private val HOST_PLATFORMS = listOf(
        "item.jd.com" to "jd",
        "jd.com" to "jd",
        "jingdong" to "jd",
        "m.tb.cn" to "taobao",
        "item.taobao.com" to "taobao",
        "taobao" to "taobao",
        "tmall" to "taobao",
        "yangkeduo" to "pdd",
        "pinduoduo" to "pdd",
        "xunmeng" to "pdd"
    )

    /** 抽出一条文本里所有链接候选（按出现顺序，去重由调用方决定用途） */
    fun decode(raw: String): List<LinkCandidate> {
        val tokens = TB_TOKEN.findAll(raw)
            .filter { it.groupValues[1].any { body -> body.isLetter() } }
            .map { LinkCandidate(LinkKind.TB_TOKEN, it.value) }
            .toList()
        val links = SHARE_LINK.findAll(raw).map { LinkCandidate(kindOf(it.value), it.value) }.toList()
        return tokens + links
    }

    /**
     * 只从**字符串**判断平台线索（域名、包名前缀都出现在文本里）；没有线索返回 `unknown`。
     * 顺序敏感：先长后短，`item.jd.com` 要先于 `jd.com` 命中（[HOST_PLATFORMS] 已按此排）。
     */
    fun platformHint(raw: String): String {
        val lower = raw.lowercase()
        return HOST_PLATFORMS.firstOrNull { lower.contains(it.first) }?.second ?: "unknown"
    }

    private fun kindOf(value: String): LinkKind = when {
        value.contains("m.tb.cn") -> LinkKind.SHORT_LINK
        value.contains("item.taobao.com") || value.contains("item.jd.com") -> LinkKind.ITEM_LINK
        else -> LinkKind.SHORT_LINK
    }
}
