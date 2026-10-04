package com.pricelens.coupon.ai

import java.io.Closeable
import java.io.File

/**
 * 端侧兜底的**运行时**：一个 GGUF + 一段 GBNF，把文案变成一段 JSON 文本。
 *
 * 刻意留在这条线上的三件事，都不在这层做：
 *  1. **不算数**：模型只负责把文本变成结构，金额/日期/URL 必须由确定性解析器再核一遍
 *     （"199 变 190"这类幻觉只有复核能挡）；
 *  2. **不做准入判断**：能不能跑由 [OnDeviceAiPolicy] 决定，这层只管"给了模型路径就跑"；
 *  3. **不管模型从哪来**：split delivery 的下载/校验在别处（本版是人工 adb push 做验证）。
 *
 * 生命周期：构造即加载（加载要几秒），[close] 释放。持有它的人应该长期持有，
 * 不要每次调用都新建 —— 那是把"加载 400MB 权重"当成"一次函数调用"。
 */
class LlamaRuntime(
    private val modelFile: File,
    private val promptTemplate: String,
    private val gbnf: String,
    private val nThreads: Int = defaultThreads(),
    private val nCtx: Int = 512,
    private val maxTokens: Int = 256
) : Closeable {

    private var handle: Long = 0L

    /** 引擎在不在这个包里（不含"模型能不能加载"，那是 [isReady]） */
    val isEngineAvailable: Boolean get() = LlamaNative.isAvailable

    val engineError: String? get() = LlamaNative.loadError

    /** 模型已加载、可以推理 */
    val isReady: Boolean get() = handle != 0L

    /** 加载统计（tokens / tok/s），给"这台机器跑得动吗"留证据；没跑过就是 null */
    fun lastStats(): String? = if (handle == 0L) null else LlamaNative.nativeLastStats(handle)

    /**
     * 加载模型。返回是否成功。
     * **失败不抛异常**：兜底层的契约是"没参与就返回 null"，加载不了就是"这一版没参与"，
     * 让上层继续用规则结果，而不是把整条找券链路带崩。
     */
    fun load(): Boolean {
        if (handle != 0L) return true
        if (!isEngineAvailable) return false
        if (!modelFile.isFile) return false
        handle = LlamaNative.nativeLoad(modelFile.absolutePath, nThreads, nCtx)
        return handle != 0L
    }

    /**
     * 抽一次。返回模型吐出的 JSON 文本；**没参与 / 失败都返回 null**（上层原样沿用规则结果）。
     *
     * prompt 走 [AiPromptAsset.render]：占位符数量不对时它会抛 —— 那是资产被改坏了，
     * 属于"必须立刻炸出来"的错误，不该被这里吞成"没参与"。
     */
    fun extract(text: String): String? {
        if (!load()) return null
        val prompt = chatWrap(AiPromptAsset.render(promptTemplate, text))
        return LlamaNative.nativeRun(handle, prompt, gbnf, maxTokens)
    }

    override fun close() {
        if (handle != 0L) {
            LlamaNative.nativeFree(handle)
            handle = 0L
        }
    }

    companion object {
        /** 大核减一，封顶 4：手机上再多的线程只会互相抢核，还更烫 */
        fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 4)

        /**
         * 聊天模板：**这一版按 Qwen3 的形态写死**（本版唯一验证过的模型）。
         *
         * 为什么不用 llama.cpp 的 `common/chat.cpp` 去套模型自带模板：那要把 `common/` 一起编进来
         * （更多源码、更多编译风险），而这里只需要一个固定的包壳。代价写在明面上：
         * **换模型必须回来改这两行**，否则模型会对着没有角色标记的裸文本瞎猜（症状是输出像 JSON
         * 但槽位全 null —— 那看起来很像是"模型不行"，其实是包装错了）。
         *
         * `/no_think` 是 Qwen3 的开关：不开它会先想一段再答（token 白烧、抽取任务不需要）。
         * 注意 GBNF 从**第一个 token** 就开始约束，本来也吐不出思考过程 —— 加上这条是为了让它
         * 别把预算花在"试图思考"上。
         */
        private const val CHAT_PREFIX = "<|im_start|>user\n"
        private const val CHAT_SUFFIX = "\n/no_think\n<|im_end|>\n<|im_start|>assistant\n"

        /** 把 prompt 正文包成一次对话轮（见 [CHAT_PREFIX] 的说明） */
        fun chatWrap(body: String): String = CHAT_PREFIX + body + CHAT_SUFFIX
    }
}
