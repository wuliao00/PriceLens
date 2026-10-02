package com.pricelens.data.remote

import org.json.JSONObject

/**
 * 慢慢买账号的**京东授权**三态（2026-10-02 实测拿到，不是猜的）。
 *
 * 背景：慢慢买只对"已登录**且**已授权京东"的账号返回京东历史价。带用户 Cookie 请求
 * `tool.manmanbuy.com/HistoryLowest.aspx?action=checkJdAuth` 会得到：
 *
 *  - 已登录+已授权：`{"data":{"auth":true,…}}`
 *  - 已登录+未授权：`{"code":1,"msg":"未授权","data":{"auth":false,
 *      "authUrl":"https://apapia-config.manmanbuy.com/h5/jd_oauth_redirect.html?invokeScene=baoliao"}}`
 *  - 未登录      ：`{"code":0,"msg":"请先登录","data":{"login":0}}`
 *
 * 之前 App 把"换不到数据"一律归因成「登录态无效或已过期」（走的是那条**对任何程序化请求
 * 都先弹阿里云滑块**的移动页），把"未授权京东"误报成"登录失效"，用户照着提示找不到出路。
 * 现在这条 JSON 是**权威判定**：先问它，再决定给用户哪句话、哪个按钮。
 */
sealed interface JdAuthState {

    /** Cookie 无效/为空：慢慢买当未登录处理 */
    data object LoggedOut : JdAuthState

    /** 登录有效，但账号还没授权京东 —— 这是"换不到数据"最常见的原因 */
    data class NotAuthorized(val authUrl: String) : JdAuthState

    /** 登录有效且已授权京东：该去取数据了 */
    data object Authorized : JdAuthState

    /** 判定不了（网络/结构变化）：不要把未知说成任何一种结论 */
    data class Unknown(val detail: String) : JdAuthState
}

/** 授权入口兜底：响应里没带 authUrl 时用它，仍是官方那条 h5 授权页 */
internal const val MMB_JD_AUTH_URL_FALLBACK =
    "https://apapia-config.manmanbuy.com/h5/jd_oauth_redirect.html?invokeScene=baoliao"

/**
 * `checkJdAuth` 的响应体 → [JdAuthState]。纯函数，JVM 可测。
 *
 * 顺序即优先级：先看"是否登录"，再看"是否授权"；任何字段缺失/异常都落到 [JdAuthState.Unknown]，
 * **绝不**猜成"已授权"（猜错会让用户去点一个不存在的取数按钮）或"未授权"（会把登录有效的人赶去重新登录）。
 */
internal fun parseJdAuthState(body: String?): JdAuthState {
    if (body.isNullOrBlank()) return JdAuthState.Unknown("empty body")
    val json = runCatching { JSONObject(body) }.getOrElse { e ->
        return JdAuthState.Unknown("not-json: ${e.javaClass.simpleName}")
    }

    val data = json.optJSONObject("data")
    // 未登录的判据来自实测：{"code":0,"msg":"请先登录","data":{"login":0}}
    val loginField = data?.optInt("login", 1) ?: 1
    if (json.optInt("code", 0) == 0 && loginField == 0) return JdAuthState.LoggedOut

    // auth 只认真正的 JSON 布尔值：org.json 会把字符串 "true" 强转成 true，
    // 而"类型不对"= 结构变了 ⇒ 应当落到 Unknown，不能让一个非布尔值决定用户该不该去授权
    val auth = data?.opt("auth")
    if (auth == true) return JdAuthState.Authorized

    // 未授权：data.auth == false 且带入口（实测 code=1,msg=未授权）
    if (auth == false) {
        val authUrl = data.optString("authUrl").takeIf { it.startsWith("http") } ?: MMB_JD_AUTH_URL_FALLBACK
        return JdAuthState.NotAuthorized(authUrl)
    }

    return JdAuthState.Unknown("code=${json.optInt("code")} msg=${json.optString("msg").take(40)}")
}
