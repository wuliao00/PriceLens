package com.pricelens.coupon.ai

/**
 * 端侧 LLM 的 JNI 门面（唯一一处 `System.loadLibrary`）。
 *
 * 库名固定 `pricelens_llm`，**只在带 native 引擎的构建里存在**（`-Ppricelens.llamaDir=...`，
 * 见 app/build.gradle.kts 里的说明）。默认构建里这个类仍然在，但 [isAvailable] 恒为 false ——
 * 这样上层不需要用 try/catch 去猜"这个包有没有引擎"，它问一个明确的布尔值。
 *
 * 为什么不 catch `UnsatisfiedLinkError` 就完事：那会把"引擎没编进来"和"编进来了但加载失败"
 * （ABI 不匹配、依赖缺符号）混成同一种沉默。这里分开：[isAvailable] 只回答"装没装进来"，
 * 加载失败会带上原因打日志（由 [loadError] 带出去），查问题时不用去猜。
 */
object LlamaNative {

    private var loaded = false
    private var failure: String? = null

    init {
        try {
            System.loadLibrary("pricelens_llm")
            loaded = true
        } catch (t: UnsatisfiedLinkError) {
            failure = t.message ?: t.toString()
        }
    }

    /** 这个包里到底有没有 native 引擎 */
    val isAvailable: Boolean get() = loaded

    /** 有引擎但加载失败时的原因（没有失败就是 null） */
    val loadError: String? get() = failure

    /**
     * 加载模型，返回句柄（0 = 失败）。`nCtx` 是上下文长度：抽取任务的输入是"一段文案 + 短 prompt"，
     * 512 足够，开大只会多吃内存（KV cache 与 n_ctx 成正比）。
     */
    external fun nativeLoad(modelPath: String, nThreads: Int, nCtx: Int): Long

    /**
     * 跑一次约束解码。`grammar` 传 GBNF；**传空串不等于"随便生成"** ——
     * 上层要么给语法要么明确接受无约束（本项目的接缝契约要求必须给）。
     * 返回 null 表示失败（prompt 超长 / 语法不合法 / decode 出错），调用方按"没参与"处理。
     */
    external fun nativeRun(handle: Long, prompt: String, grammar: String, maxTokens: Int): String?

    /** 上一轮的生成统计（tokens / tok/s），给"这台机器跑不跑得动"留证据 */
    external fun nativeLastStats(handle: Long): String?

    external fun nativeFree(handle: Long)
}
