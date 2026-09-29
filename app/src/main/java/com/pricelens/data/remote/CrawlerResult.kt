package com.pricelens.data.remote

/**
 * 爬虫结果类型（阶段2：状态与错误体系解耦）。
 *
 * 旧模型中 [ApiClient] 吞掉所有异常、一律返回 `null`，上层无法区分
 * "真的没有数据" 与 "被反爬拦截 / 网络故障"。该类型把四种结局显式建模：
 *  - [Success]  拿到有效响应体
 *  - [Empty]    服务端返回成功但内容为空/无效（非反爬）
 *  - [Blocked]  反爬拦截：403/412、JS challenge 页、风控异常
 *  - [Network]  网络层失败：超时 / DNS / 连接重置 / 非 2xx 状态码
 *
 * [asNullable] 提供向后兼容桥：旧的返回 `String?` / `JSONObject?` 的方法
 * 内部委托新方法后调用本扩展。
 *
 * **F4（2026-09-29）修正适用范围**：五个"关键词搜索列表"解析类
 * （当当/值得买/找券/识货/B站）不再走这座桥——桥会把 Blocked/Network 与"真没结果"抹平，
 * 改为经 [toSourceFailure] 冒泡类型化失败。仍用 [asNullable] 的只有非列表通道
 * （京东商品页、慢慢买、星罗），它们的 `null` 语义见 `docs/API.md` 与本文件的 F4 说明。
 */
sealed interface CrawlerResult<out T> {

    /** 成功：[data] 为响应体（HTML/JSON 原文） */
    data class Success<T>(val data: T) : CrawlerResult<T>

    /** 请求成功但无内容（空响应体），非反爬 */
    data object Empty : CrawlerResult<Nothing>

    /** 反爬拦截（403/412、challenge 页、风控）；[reason] 供 UI 与日志诊断 */
    data class Blocked(val reason: String) : CrawlerResult<Nothing>

    /** 网络层失败；[cause] 携带底层异常（超时 / IO 等） */
    data class Network(val cause: Throwable) : CrawlerResult<Nothing>
}

/** 是否拿到有效数据 */
fun <T> CrawlerResult<T>.isSuccess(): Boolean = this is CrawlerResult.Success

/** 是否被反爬拦截 */
fun CrawlerResult<*>.isBlocked(): Boolean = this is CrawlerResult.Blocked

/**
 * 兼容桥：Success → data，其余 → null。
 * 旧签名方法（如 [ApiClient.getHtml]）内部即经此转换，语义与改造前完全一致。
 */
fun <T> CrawlerResult<T>.asNullable(): T? = (this as? CrawlerResult.Success)?.data

/** 面向用户/日志的一句话原因（Blocked 原因 / Network 异常名 / Empty 文案） */
fun CrawlerResult<*>.reasonOrNull(): String? = when (this) {
    is CrawlerResult.Blocked -> reason
    is CrawlerResult.Network -> cause.javaClass.simpleName
    is CrawlerResult.Empty -> "无内容"
    is CrawlerResult.Success -> null
}

/** 反爬拦截异常：供 ViewModel 层将 CrawlerResult.Blocked 映射为 AsyncValue.Error */
class CrawlerBlockedException(val reason: String) : RuntimeException(reason)

/**
 * 网络不可达异常（F4，2026-09-29）：超时 / DNS / 连接被拒 / 非 2xx。
 *
 * 与 [CrawlerBlockedException] 分开，是为了让状态徽标能如实区分「反爬」与「失败」，
 * 也为了让 `AsyncValue.Error.cause` 带上成因——二者都不是"这个关键词没匹配上"。
 */
class SourceUnreachableException(reason: String) : RuntimeException(reason)

/**
 * 四态 → 冒泡异常（F4 的唯一映射点，`data/remote` 各解析类共用）。
 *
 * 修复前 `getHtml` 经 [asNullable] 把四种结局塌成 `String?`，于是 Blocked/Network 在下游与
 * "这商品真没结果"不可区分：徽标写「正常」、文案写「未匹配到关键词」、`SourceHealth` 三连败
 * 冷却永远触发不了。解析类改用本函数后，**只有"够着了"**（[Success] / [Empty]）才可能返回空列表。
 *
 * 返回 null 表示"这一结局属于合法空结果"：
 *  - [Success] 拿到响应体，交由解析器决定条目数（0 条 = 真的没有）；
 *  - [Empty] 请求成功但响应体为空（够着了，只是服务端什么都没给）。
 */
fun CrawlerResult<*>.toSourceFailure(): Throwable? = when (this) {
    is CrawlerResult.Blocked -> CrawlerBlockedException(reason)
    is CrawlerResult.Network ->
        SourceUnreachableException("网络不可达：${cause.javaClass.simpleName} ${cause.message ?: ""}".trim())
    is CrawlerResult.Empty -> null
    is CrawlerResult.Success -> null
}

