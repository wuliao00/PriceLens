package com.pricelens.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.pricelens.coupon.CouponExtractor
import com.pricelens.coupon.CouponPipeline
import com.pricelens.coupon.ai.ClauseModel
import com.pricelens.coupon.ai.LlamaFallbackExtractor
import com.pricelens.coupon.ai.LlamaRuntime
import com.pricelens.coupon.model.ExtractSource
import com.pricelens.coupon.model.Extraction
import com.pricelens.coupon.normalize.Clause
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * **只在 debug 包里的探针**（正式包不会合并这个文件）。两种模式：
 *
 * 1. 单发（`--es text`）：跑一次推理，把结果写 logcat 与 `files/llm-probe.txt`；
 * 2. A/B（`--es ab <jsonl>`）：逐条跑**规则**与**规则+模型**两条链，各写一份 golden 格式的
 *    predictions —— 这是"模型 vs 规则"的取数口，分数交给 `tools/eval_coupons.py` 算，
 *    探针自己**不判胜负**（量具与裁判分开，是这个仓库的纪律）。
 *
 * 用法：
 *   adb shell am broadcast -a com.pricelens.dev.LLM_PROBE -n com.pricelens.dev/com.pricelens.debug.LlmProbeReceiver \
 *     --es model /data/data/com.pricelens.dev/files/models/Qwen3-0.6B-Q4_K_M.gguf --es ab /data/local/tmp/ab.jsonl
 * 输入每行：`{"id":"…","source":"clipboard|community|page_node","text":"…"}`。
 */
class LlmProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val modelPath = intent.getStringExtra("model") ?: "/sdcard/Download/Qwen3-0.6B-Q4_K_M.gguf"
        val text = intent.getStringExtra("text") ?: "满199减50"
        val abPath = intent.getStringExtra("ab")
        val pending = goAsync()
        Thread {
            try {
                val prompt = context.assets.open("ai/coupon_prompt_v1.txt").bufferedReader().use { it.readText() }
                val gbnf = context.assets.open("ai/coupon_schema.gbnf").bufferedReader().use { it.readText() }
                val runtime = LlmRuntimeHolder.runtimeFor(File(modelPath), prompt, gbnf)
                if (abPath != null) {
                    runAb(context, runtime, File(abPath))
                } else {
                    runSingle(context, runtime, modelPath, text)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "probe 失败：${t::class.java.name}: ${t.message}", t)
                runCatching { File(context.filesDir, "llm-probe.txt").writeText("probe 失败：${t.message}", Charsets.UTF_8) }
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun runSingle(context: Context, runtime: LlamaRuntime, modelPath: String, text: String) {
        val started = System.currentTimeMillis()
        val json = runtime.extract(text)
        val line = buildString {
            append("engine=").append(runtime.isEngineAvailable)
            append(" ready=").append(runtime.isReady)
            append(" engineError=").append(runtime.engineError ?: "-")
            append("\nmodel=").append(modelPath)
            append("\ntext=").append(text)
            append("\nelapsedMs=").append(System.currentTimeMillis() - started)
            append("\nstats=").append(runtime.lastStats() ?: "-")
            append("\njson=").append(json ?: "null")
        }
        Log.i(TAG, line)
        File(context.filesDir, "llm-probe.txt").writeText(line, Charsets.UTF_8)
    }

    /** A/B：同一条文本走两条链，各写一份 predictions（格式与 golden 对齐，直接喂 eval 脚本） */
    private fun runAb(context: Context, runtime: LlamaRuntime, input: File) {
        val fallback = listOf(LlamaFallbackExtractor(ClauseModel { runtime.extract(it) }))
        val rulesLines = ArrayList<String>()
        val modelLines = ArrayList<String>()
        var index = 0
        for (raw in input.readLines()) {
            if (raw.isBlank()) continue
            val entry = JSONObject(raw)
            val id = entry.getString("id")
            val source = entry.getString("source")
            val text = entry.getString("text")
            val rules = extract(source, text, emptyList())
            val withModel = extract(source, text, fallback)
            rulesLines.add(render(id, source, rules))
            modelLines.add(render(id, source, withModel))
            Log.i(TAG, "AB[$index] $id rules=${rules.coupons.size} model=${withModel.coupons.size}")
            index += 1
        }
        File(context.filesDir, "ab-rules.jsonl").writeText(rulesLines.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        File(context.filesDir, "ab-model.jsonl").writeText(modelLines.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        Log.i(TAG, "AB 完成：$index 条 → files/ab-rules.jsonl / files/ab-model.jsonl")
    }

    /** 与 `CouponGoldenPredictionTest` 同口径：三个入口按 source 走各自的门面，page_node 喂单条子句 */
    private fun extract(source: String, text: String, fallback: List<LlamaFallbackExtractor>): Extraction = when (source) {
        "clipboard" -> CouponExtractor.fromClipboard(text, fallback)
        "community" -> CouponExtractor.fromPost(text, 0L, fallback)
        else -> CouponPipeline.extract(
            clauses = listOf(Clause(text = text)),
            source = ExtractSource.PAGE_NODE,
            platform = "unknown",
            itemRef = null,
            templates = CouponExtractor.templates,
            fallback = fallback
        )
    }

    private fun render(id: String, source: String, extraction: Extraction): String {
        val coupons = JSONArray()
        extraction.coupons.forEach { slot ->
            coupons.put(
                JSONObject()
                    .put("discount", slot.discount ?: JSONObject.NULL)
                    .put("threshold", slot.threshold ?: JSONObject.NULL)
                    .put("scope", slot.scope.name.lowercase())
                    .put("state", slot.state.name.lowercase())
                    .put("expiry", slot.expiry ?: JSONObject.NULL)
                    .put("url", slot.url ?: JSONObject.NULL)
            )
        }
        return JSONObject().put("id", id).put("source", source).put("coupons", coupons).toString()
    }

    companion object {
        const val TAG = "PriceLensLLMProbe"
    }
}

/**
 * 进程内单例：加载 400MB 权重要好几秒，连续几次广播应复用同一份运行时。
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
