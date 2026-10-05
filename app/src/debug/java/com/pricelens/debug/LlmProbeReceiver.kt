package com.pricelens.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.pricelens.accessibility.NodeSnapshot
import com.pricelens.coupon.ClipboardCapture
import com.pricelens.coupon.CouponExtractor
import com.pricelens.coupon.CouponPipeline
import com.pricelens.coupon.PageCapture
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
 * 3. 页面树注入（`--es page_capture "文案1；文案2"`）：**不碰模型**，只往 `PageCapture` 交一棵合成树
 *    并打出 `fromPage` 的产出 —— 用来在真机上证明 #61 那条接线通（服务发布 → UI 收集 → 门面 → 渲染），
 *    不需要电商 App 联网、也不动无障碍开关。
 * 4. 剪贴板注入（`--es clipboard_capture "文本"`）：同上，走 #63 的 `ClipboardCapture` 单槽与
 *    `fromClipboard` 门面，绕开系统剪贴板本身（adb 写它不安全）。
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
        val pageTexts = intent.getStringExtra("page_capture")
        val clipboardText = intent.getStringExtra("clipboard_capture")
        val pending = goAsync()
        Thread {
            try {
                // 注入模式**不加载模型**：它要证明的是接线（发布→UI 收集→fromPage→合并→渲染），
                // 而那条路上一个 token 都不需要推理；顺手加载 400MB 只会让排查变慢。
                if (pageTexts != null) {
                    publishSyntheticPage(pageTexts)
                } else if (clipboardText != null) {
                    publishClipboard(clipboardText)
                } else {
                    val prompt = context.assets.open("ai/coupon_prompt_v1.txt").bufferedReader().use { it.readText() }
                    val gbnf = context.assets.open("ai/coupon_schema.gbnf").bufferedReader().use { it.readText() }
                    val runtime = LlmRuntimeHolder.runtimeFor(File(modelPath), prompt, gbnf)
                    if (abPath != null) {
                        runAb(context, runtime, File(abPath))
                    } else {
                        runSingle(context, runtime, modelPath, text)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "probe 失败：${t::class.java.name}: ${t.message}", t)
                runCatching { File(context.filesDir, "llm-probe.txt").writeText("probe 失败：${t.message}", Charsets.UTF_8) }
            } finally {
                pending.finish()
            }
        }.start()
    }

    /**
     * 注入一棵合成树并发布给 [PageCapture]（#61 的真机接线取证）。
     *
     * **从 #73 起这条模式验不到找券 UI 了**：页面树那一路加了身份闸（一棵树只能用一次，
     * 而且必须与本次商品上下文的 `Detected.signature` 相同），注入造的签名不等于任何一次真实检测。
     * 这不是量具坏了 —— 这件事本来就只能用真机真实跳转验，注入造不出"身份归属"。
     * 模式保留，是因为它仍能单独验"发布 → 门面 → 抽取"这半段（下面那行日志就是证据）。
     *
     * 为什么用合成树而不是"上真机打开京东再看"：那台测试机没有可用 DNS，且这条路要求动
     * 无障碍开关（改完还得还原）。**内容**的正确性另有 `CouponExtractorTest` 的真机 dump 用例负责
     * （从 `jd_home_20260929.xml` 这种真实树里挑角标）；这里只证明
     * "服务发布 → UI 收集 StateFlow → fromPage → 与关键词那一路做加法 → 渲染成行"这一段接得上。
     */
    private fun publishSyntheticPage(texts: String) {
        val clauses = texts.split('；', ';').map { it.trim() }.filter { it.isNotEmpty() }
        val root = node(text = null, kids = clauses.map { node(text = it, kids = emptyList()) }, cls = "android.widget.LinearLayout")
        PageCapture.publish(
            PageCapture.Capture(
                signature = "probe|${clauses.firstOrNull() ?: ""}",
                packageName = "com.jingdong.app.mall",
                itemId = null,
                capturedAtElapsedMs = SystemClock.elapsedRealtime(),
                root = root
            )
        )
        val extraction = CouponExtractor.fromPage(root)
        Log.i(
            TAG,
            "PAGE_CAPTURE 注入叶子=${clauses.size} 券数=${extraction.coupons.size} " +
                "keys=${extraction.coupons.map { slot -> "${slot.discount}|${slot.threshold}" }} " +
                "conf=${extraction.confidence} 出处=${extraction.coupons.map { slot -> slot.sourceText }}"
        )
    }

    /**
     * 注入一段"剪贴板文本"（#63 的真机取证）：走的是生产同一个单槽与同一条门面，
     * 只是绕开 `ClipboardManager`（adb 侧没法安全地写系统剪贴板）。
     */
    private fun publishClipboard(text: String) {
        ClipboardCapture.publish(text, SystemClock.elapsedRealtime())
        val extraction = CouponExtractor.fromClipboard(text)
        Log.i(
            TAG,
            "CLIPBOARD_CAPTURE 字数=${text.length} 券数=${extraction.coupons.size} " +
                "keys=${extraction.coupons.map { slot -> "${slot.discount}|${slot.threshold}" }} " +
                "conf=${extraction.confidence} 出处=${extraction.coupons.map { slot -> slot.sourceText }}"
        )
    }

    /** NodeSnapshot 是纯数据模型：debug 侧手工搭一棵，不需要框架节点也能进生产管线 */
    private fun node(text: String?, kids: List<NodeSnapshot>, cls: String = "android.widget.TextView"): NodeSnapshot {
        return NodeSnapshot(
            text = text,
            contentDescription = null,
            className = cls,
            resourceName = null,
            clickable = false,
            children = kids,
            bounds = null
        )
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
            // 模型的**原始输出**也留一行：不然"这条为什么没捞到"只能猜（复核层会丢数字，看不出是模型没给还是被丢掉）
            val probe = runtime.extract(text)
            // 用 lines().joinToString 而不是 replace 转义符：这条链路上"反斜杠 n"被工具层吃掉过两次
            Log.i(TAG, "AB[$index] $id raw=" + (probe?.lines()?.joinToString(" ")?.take(180) ?: "null"))
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

    /**
     * 与 `CouponGoldenPredictionTest` 同口径，但**写明一件事**：
     * `page_node` 这里喂的是**一条子句文本**直接进管线，没有经过 `NodeAdapter`/`CouponExtractor.fromPage`
     * —— 因为评测集里 page_node 的 `raw` 本来就是一条节点文案，不是整棵树
     * （#61 之后生产路径是 `fromPage(整棵树)`，那条路的覆盖在 `CouponExtractorTest` 的真机 dump 用例里）。
     * 所以 A/B 各轮表里的 `page_node` 分数衡量的是"给定一条节点文案，规则/模型读得对不对"，
     * 不是"从一棵真实树里能不能挑出这些文案"—— 两件事别混着说。
     */
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
