package com.pricelens.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.pricelens.coupon.ai.LlamaRuntime
import java.io.File

/**
 * **只在 debug 包里的探针**：用 adb 广播触发一次端侧推理，把结果写到 logcat 与
 * `filesDir/llm-probe.txt`，供"这个模型在这台机器上到底跑不跑得动、吐的对不对"取证。
 *
 * 为什么不塞进正式代码路径：验完就要能删。找券的 UI 入口那条路（搜索关键词）已经报出
 * "几乎不可能出券"的问题（见任务 #61），把一个测试开关挂进去只会让两件事混在一起。
 *
 * 用法：
 *   adb push Qwen3-0.6B-Q4_K_M.gguf /sdcard/Download/
 *   adb shell am broadcast -a com.pricelens.dev.LLM_PROBE \
 *     -n com.pricelens.dev/com.pricelens.debug.LlmProbeReceiver \
 *     --es text "满199减50，券码：ABCD1234"
 * 默认模型路径 = `/sdcard/Download/Qwen3-0.6B-Q4_K_M.gguf`（应用有读 sdcard 的权限时直接用，
 * 免去再拷一份 400MB 进私有目录）；也可以用 `--es model /path/to.gguf` 覆盖。
 */
class LlmProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val modelPath = intent.getStringExtra("model") ?: "/sdcard/Download/Qwen3-0.6B-Q4_K_M.gguf"
        val text = intent.getStringExtra("text") ?: "满199减50"
        val pending = goAsync()
        Thread {
            val out = File(context.filesDir, "llm-probe.txt")
            try {
                val prompt = context.assets.open("ai/coupon_prompt_v1.txt").bufferedReader().use { it.readText() }
                val gbnf = context.assets.open("ai/coupon_schema.gbnf").bufferedReader().use { it.readText() }
                val runtime = LlmRuntimeHolder.runtimeFor(File(modelPath), prompt, gbnf)
                val started = System.currentTimeMillis()
                val json = runtime.extract(text)
                val elapsed = System.currentTimeMillis() - started
                val stats = runtime.lastStats()
                val line = buildString {
                    append("engine=").append(runtime.isEngineAvailable)
                    append(" ready=").append(runtime.isReady)
                    append(" engineError=").append(runtime.engineError ?: "-")
                    append("\nmodel=").append(modelPath)
                    append("\ntext=").append(text)
                    append("\nelapsedMs=").append(elapsed)
                    append("\nstats=").append(stats ?: "-")
                    append("\njson=").append(json ?: "null")
                }
                Log.i(TAG, line)
                out.writeText(line, Charsets.UTF_8)
            } catch (t: Throwable) {
                val line = "probe 失败：${t::class.java.name}: ${t.message}"
                Log.e(TAG, line, t)
                runCatching { out.writeText(line, Charsets.UTF_8) }
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val TAG = "PriceLensLLMProbe"
    }
}

/**
 * 进程内单例：加载 400MB 权重要好几秒，一次广播加载一次就够，
 * 连续几次广播（不同文案）应该复用同一份运行时 —— 不然测出来的耗时里大半是加载时间。
 */
private object LlmRuntimeHolder {
    private var cached: LlamaRuntime? = null
    private var cachedKey: String? = null

    @Synchronized
    fun runtimeFor(model: File, prompt: String, gbnf: String): LlamaRuntime {
        val key = model.absolutePath
        val existing = cached
        if (existing != null && cachedKey == key) return existing
        existing?.close()
        val fresh = LlamaRuntime(model, prompt, gbnf)
        cached = fresh
        cachedKey = key
        return fresh
    }
}
