package com.pricelens.data.remote

/**
 * 慢慢买移动端历史价页（`tool.manmanbuy.com/m/history.aspx`，SSR）的**响应体分类器**。
 *
 * 为什么要单独分一类：这条通道是"用户在设置里粘贴登录 Cookie"后才走的路，
 * 而它有三种完全不同的结局，全都长成一个"没有曲线"：
 *  1. 网络不可达 / 非 2xx —— 根本没够着慢慢买；
 *  2. Cookie 无效或已过期 —— 被 302 弹到阿里云人机验证 2.0 的页面；
 *  3. 该商品确实没有历史数据 —— 够着了，也确认了没有。
 * 本文件只负责 2 与 3 的区分（1 由 [CrawlerResult] 的 Network/Blocked 负责）。
 *
 * 判据来自 2026-09-30 的匿名实测（不带 Cookie 直接请求同一条 URL）：
 * `final=https://tool.manmanbuy.com/m/aliVal.aspx?comeFrom=history_mobile_tool`，
 * 响应体 4,018 字符，已原样存为测试夹具
 * `app/src/test/resources/fixtures/mmb_history_captcha_20260930.html`。
 * 该夹具里 `aliVal` 出现 1 次、`AliyunCaptcha` 5 次、`captcha`（不分大小写）17 次，
 * 而 `Date.UTC` 与 `history.aspx` 各 0 次 —— 人机验证页**只有验证脚本、没有数据序列**。
 *
 * 顺序很重要：**先认数据点**，再认验证码特征。真实数据页也可能同时挂着验证码组件的
 * 脚本（慢慢买在页面上常驻 AliyunCaptcha 初始化），若先查验证码特征就会把
 * "能出数据但页面上也带了验证脚本"误判成"被拦"，反而不如旧的空列表诚实。
 */
internal sealed interface HistoryPage {

    /** 页面里有 `Date.UTC` 价格序列（哪怕同时挂着验证码脚本） */
    data class Points(val points: List<ManmanbuyApi.PricePoint>) : HistoryPage

    /** 被弹到人机验证 / 登录态失效：够着了页面，但页面什么都没给 */
    data object Captcha : HistoryPage

    /** 页面正常，但没有该商品的历史数据 */
    data object NoData : HistoryPage
}

/** 移动端 SSR 页内嵌的 flot 序列：`[Date.UTC(y,m,d),price]` */
private val DATE_UTC_RE = Regex("\\[Date\\.UTC\\((\\d+),(\\d+),(\\d+)\\),(\\d+(?:\\.\\d+)?)\\]")

/**
 * 人机验证页特征（全部取自 2026-09-30 那份真实夹具，不是猜的）：
 *  - `aliVal`：302 落地页 `/m/aliVal.aspx?comeFrom=history_mobile_tool`；
 *  - `AliyunCaptcha` / `captcha-frontend`：阿里云人机验证 2.0 的脚本与全局配置；
 *  - `captcha-element`：页面上预留的验证码渲染容器。
 */
private val CAPTCHA_MARKERS = listOf(
    "aliVal",
    "AliyunCaptcha",
    "captcha-frontend",
    "captcha-element"
)

/** 把 `Date.UTC` 序列解析成 [ManmanbuyApi.PricePoint]（月 +1，因为 JS 的月份从 0 起）。 */
internal fun parseDateUtcPoints(html: String): List<ManmanbuyApi.PricePoint> {
    val result = mutableListOf<ManmanbuyApi.PricePoint>()
    for (m in DATE_UTC_RE.findAll(html)) {
        val price = m.groupValues[4].toDoubleOrNull() ?: continue
        if (price <= 0) continue
        val date = "%04d-%02d-%02d".format(
            m.groupValues[1].toInt(), m.groupValues[2].toInt() + 1, m.groupValues[3].toInt()
        )
        result += ManmanbuyApi.PricePoint(date, price)
    }
    return result
}

/** 纯函数：一份响应体 → 一个能给人看的结局。 */
internal fun classifyHistoryPage(html: String): HistoryPage {
    // 先判数据：有价格序列就一定是"够着且有货"，验证码脚本的存在不改变这一点
    val points = parseDateUtcPoints(html)
    if (points.isNotEmpty()) return HistoryPage.Points(points)
    // 无数据时再看是不是被弹到了人机验证页
    if (CAPTCHA_MARKERS.any { html.contains(it, ignoreCase = true) }) return HistoryPage.Captcha
    return HistoryPage.NoData
}

/**
 * 「检测 Cookie」的结论：每种结局一句人能看懂的话，带一句原因。
 *
 * 红线：① [Unreachable]（没够着）绝不能显示成"没有历史数据"（够着了才谈得上有没有）；
 * ② 2026-10-02 加了一条更硬的：**未登录 / 未授权京东 / 渠道要人机验证是三件事**，
 * 不许再压成同一句「登录态无效或已过期」——实测里用户明明登录有效，只是没授权京东，
 * 却被这句提示引去重新登录，白折腾。
 */
sealed interface CookieProbe {

    /** Cookie 换到了数据：探针商品的历史价格点（[points] 非空） */
    data class Ok(val points: List<ManmanbuyApi.PricePoint>) : CookieProbe

    /** 慢慢买判定"未登录"：Cookie 空缺或登录态已失效 */
    data class LoggedOut(val reason: String) : CookieProbe

    /** 登录有效，但账号**没有授权京东** —— 慢慢买只对已授权账号返回京东历史价 */
    data class JdNotAuthorized(val authUrl: String, val reason: String) : CookieProbe

    /** 登录有效且已授权京东：数据要在网页里取（移动页那条通道对程序化请求一律要人机验证） */
    data class Ready(val reason: String) : CookieProbe

    /** 被弹到人机验证页，且这次连授权状态都没查成——不能据此断定登录失效 */
    data class Captcha(val reason: String) : CookieProbe

    /** 够着了页面，但这个探针商品自己没有历史价格数据 */
    data class NoData(val reason: String) : CookieProbe

    /** 压根没连上慢慢买（网络不可达 / 被反爬直接拒 / 空响应），与"没有数据"是两回事 */
    data class Unreachable(val reason: String) : CookieProbe
}

/**
 * 判定优先级：**登录态 → 京东授权 → 页面**。纯函数，JVM 可测。
 *
 * 为什么把授权放在页面之前：移动页对任何程序化请求都会弹人机验证（实测四组对照同 4,135 字节），
 * 于是"页面弹了验证码"对所有人都成立、对谁都说明不了问题；而 `checkJdAuth` 的 JSON 是**账号侧的事实**。
 * 只有连账号状态都问不出来（[JdAuthState.Unknown]）时，才退回用页面现象说话。
 */
internal fun probeOutcome(auth: JdAuthState, page: HistoryPage?): CookieProbe = when (auth) {
    JdAuthState.LoggedOut -> CookieProbe.LoggedOut("checkJdAuth 返回 code=0/login=0")
    is JdAuthState.NotAuthorized ->
        CookieProbe.JdNotAuthorized(auth.authUrl, "checkJdAuth 返回 auth=false")
    JdAuthState.Authorized -> when (page) {
        is HistoryPage.Points -> CookieProbe.Ok(page.points)
        else -> CookieProbe.Ready("checkJdAuth 返回 auth=true")
    }
    is JdAuthState.Unknown -> when (page) {
        is HistoryPage.Points -> CookieProbe.Ok(page.points)
        HistoryPage.Captcha -> CookieProbe.Captcha("授权状态未知(${auth.detail})；页面弹了人机验证")
        HistoryPage.NoData -> CookieProbe.NoData("授权状态未知(${auth.detail})；页面也没有数据")
        null -> CookieProbe.Unreachable("授权状态未知：${auth.detail}")
    }
}
