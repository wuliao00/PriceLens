package com.pricelens.util

import com.pricelens.BuildConfig

/**
 * 日志薄封装（阶段2）：
 *  - 统一 TAG，避免各文件散落字符串
 *  - Release 包（BuildConfig.DEBUG == false）静默，不产生任何日志开销；
 *    例外：错误级 [e] 在 Release 也输出（评审修复：线上问题可观测性）
 */
object LogT {

    const val TAG = "PriceLens"

    fun d(msg: String) {
        if (BuildConfig.DEBUG) android.util.Log.d(TAG, msg)
    }

    fun i(msg: String) {
        if (BuildConfig.DEBUG) android.util.Log.i(TAG, msg)
    }

    fun w(msg: String) {
        if (BuildConfig.DEBUG) android.util.Log.w(TAG, msg)
    }

    fun e(msg: String, t: Throwable? = null) {
        // 错误级在 Release 也输出：线上故障可观测（其余级别保持 DEBUG 限定）
        android.util.Log.e(TAG, msg, t)
    }
}
