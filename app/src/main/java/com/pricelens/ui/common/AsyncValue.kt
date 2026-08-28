package com.pricelens.ui.common

/**
 * 统一异步数据状态（阶段2：状态与错误体系解耦）。
 *
 * 取代旧 `MainUiState` 中"一个全局 loading + 一个全局 error 字符串"的粗粒度模型，
 * 让每个数据源（商品 / 历史价 / 视频 / 券 / 爆料 / 识货）各自独立表达：
 *  - [Idle]    尚未发起请求
 *  - [Loading] 请求进行中（可携带上一次的旧数据做占位渲染）
 *  - [Success] 成功并持有数据
 *  - [Error]   失败并携带原因（反爬 / 网络 / 解析），可选保留降级旧数据
 *
 * 为阶段4的 per-source SourceStatusRow 可视化做准备。
 */
sealed interface AsyncValue<out T> {

    /** 尚未发起请求 */
    data object Idle : AsyncValue<Nothing>

    /** 请求进行中；[previous] 保存上一次的数据以便过渡期占位渲染 */
    data class Loading<T>(val previous: T? = null) : AsyncValue<T>

    /** 成功 */
    data class Success<T>(val data: T) : AsyncValue<T>

    /** 失败：[cause] 为真实原因（反爬拦截 / 网络异常等），[fallback] 为可降级展示的旧数据 */
    data class Error<T>(val cause: Throwable, val fallback: T? = null) : AsyncValue<T>
}

/** 是否处于成功态 */
fun <T> AsyncValue<T>.isSuccess(): Boolean = this is AsyncValue.Success

/** 是否处于加载态 */
fun <T> AsyncValue<T>.isLoading(): Boolean = this is AsyncValue.Loading

/** 是否处于失败态 */
fun <T> AsyncValue<T>.isError(): Boolean = this is AsyncValue.Error

/** 是否空闲（未发起请求） */
fun <T> AsyncValue<T>.isIdle(): Boolean = this is AsyncValue.Idle

/**
 * 取当前可用值；优先级：Success 数据 > Error 降级数据 > Loading 旧数据 > [default]。
 * 用于 UI 渲染时"有数据先展示"的兜底。
 */
fun <T> AsyncValue<T>.valueOrDefault(default: T): T = when (this) {
    is AsyncValue.Success -> data
    is AsyncValue.Error -> fallback ?: default
    is AsyncValue.Loading -> previous ?: default
    is AsyncValue.Idle -> default
}

/** 取当前可用值，无数据返回 null */
fun <T> AsyncValue<T>.valueOrNull(): T? = when (this) {
    is AsyncValue.Success -> data
    is AsyncValue.Error -> fallback
    is AsyncValue.Loading -> previous
    is AsyncValue.Idle -> null
}
