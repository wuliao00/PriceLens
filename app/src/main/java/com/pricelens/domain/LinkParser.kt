package com.pricelens.domain

/**
 * 统一入口管道（文档 §4.1）：从任意文本里认出商品链接/口令。
 *
 * 为什么要独立一个解析器：入口有三条（搜索框粘贴、系统分享、回前台剪贴板），
 * 链接形态又多（`item.jd.com` / `item.m.jd.com/product/` / 短链 `u.jd.com`、
 * `m.tb.cn` / 淘口令 / `yangkeduo.com`），**这是最容易回归出错的地方** ——
 * 所以判定全部放在这个纯函数里，用 [LinkParserTest] 的用例库钉住。
 *
 * 短链（`needsRedirect = true`）只标出来，跳转解析在数据层做（见
 * `data/remote/ShortLinkResolver`）：解析器本身不碰网络，纯函数可测。
 */
object LinkParser {

    /** 支持识别的平台（顺带作为入口文案的标签） */
    enum class Platform(val label: String) {
        JD("京东"),
        TAOBAO("淘宝/天猫"),
        PDD("拼多多")
    }

    data class ParsedLink(
        /** 短链解析前可能为 null（只有短链域名时） */
        val platform: Platform?,
        /** 平台内商品 ID（京东 = SKU；淘宝 = id；拼多多 = goods_id）；短链阶段为 null */
        val skuId: String?,
        val url: String,
        /** 原始文本（分享语里常带「【京东】xxx 复制打开」这类噪声） */
        val raw: String,
        /** true = 需要先跟随短链跳转才能拿到 skuId */
        val needsRedirect: Boolean = false
    )

    private val URL_RE = Regex("""https?://[^\s"'<>【】（）()]+""")
    private val JD_ID = Regex("""item(?:\.m)?\.jd\.com/(?:product/)?(\d{6,})""")
    private val TB_ID = Regex("""[?&]id=(\d+)""")
    private val PDD_ID = Regex("""goods_id=(\d+)""")

    private val JD_HOSTS = listOf("jd.com", "jd.hk", "3.cn")

    // tb.cn 是淘宝自家短链：平台可以定（TAOBAO），但 SKU 要跟跳转才知道
    private val TB_HOSTS = listOf("taobao.com", "tmall.com", "tb.cn")
    private val PDD_HOSTS = listOf("yangkeduo.com", "pinduoduo.com")

    /** 短链域名：需要跟一次跳转才知道落到哪个平台 */
    private val SHORT_HOSTS = listOf("3.cn", "u.jd.com", "m.tb.cn", "p.pinduoduo.com", "tb.cn")

    /** 淘口令：`￥xxxx￥` / `¥xxxx¥`（含全角） */
    private val TAO_CODE_RE = Regex("""[¥￥]\s?[A-Za-z0-9]{6,14}\s?[¥￥]""")

    /**
     * 解析一段文本。
     *
     * @return null = 没认出任何商品入口（调用方应回落到普通关键词搜索）
     */
    fun parse(text: String): ParsedLink? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        val url = URL_RE.find(trimmed)?.value?.trimEnd('.', ',', '。', '，', '）', ')')
        if (url == null) {
            // 淘口令没有 URL：识别出平台但没有 sku（引导用户手动打开淘宝 App 或粘贴商品链接）
            return if (TAO_CODE_RE.containsMatchIn(trimmed)) {
                ParsedLink(Platform.TAOBAO, null, "", trimmed)
            } else {
                null
            }
        }

        val host = runCatching { java.net.URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        if (host.isEmpty()) return null

        val platform = when {
            JD_HOSTS.any { host == it || host.endsWith(".$it") } -> Platform.JD
            TB_HOSTS.any { host == it || host.endsWith(".$it") } -> Platform.TAOBAO
            PDD_HOSTS.any { host == it || host.endsWith(".$it") } -> Platform.PDD
            else -> null
        }
        val sku = when (platform) {
            Platform.JD -> JD_ID.find(url)?.groupValues?.get(1)
            Platform.TAOBAO -> TB_ID.find(url)?.groupValues?.get(1)
            Platform.PDD -> PDD_ID.find(url)?.groupValues?.get(1)
            null -> null
        }
        val short = SHORT_HOSTS.any { host == it || host.endsWith(".$it") }
        // 既不是已知平台、也不是短链 → 不是商品链接（比如一条普通网页）
        if (platform == null && !short) return null
        return ParsedLink(platform, sku, url, trimmed, needsRedirect = short && sku == null)
    }

    /** 是否值得在剪贴板横幅里提示（有平台或短链才算） */
    fun isProductLink(text: String): Boolean = parse(text) != null
}
