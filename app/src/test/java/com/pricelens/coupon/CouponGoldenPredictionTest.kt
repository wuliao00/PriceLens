package com.pricelens.coupon

import com.pricelens.coupon.model.CouponSlot
import com.pricelens.coupon.model.ExtractSource
import com.pricelens.coupon.model.Extraction
import com.pricelens.coupon.normalize.Clause
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 把**真实流水线**在 golden 上的产出导成 `predictions.jsonl`，交给
 * `py tools/eval_coupons.py` 打分。
 *
 * 为什么要有这个文件：CI 里那条 `golden vs golden 自反性 F1=1.0` 证明的只是**量具不撒谎**
 * （同一份数据喂两边必然满分），它**不是流水线的成绩**。没有这一环，
 * "找券到底准不准"就只剩"75 条分类单测 + 真机目视"——那正是用户报"不准确"却说不清改没改好的原因。
 *
 * 这条测试**只保证产出可判**，不自己算分（算分只有一件事的解释权：eval 脚本）：
 *  - 逐行跑完 golden，条数必须相等（漏读一行 = 把一条静默判成漏抽，分母就错了）；
 *  - 输出的每一行都能被 `JSONObject` 解析回来（写坏一行，python 侧直接 exit 2）；
 *  - **不比准确率**：分数在 python 里判，这里判"文件没写坏"。
 *
 * 口径（写清边界，免得把两件事混成一件）：
 *  - `clipboard` → `CouponExtractor.fromClipboard(raw)`
 *  - `community` → `CouponExtractor.fromPost(raw, 0)`（ageDays=0 不做人为衰减；文案里写明日期的仍按词判）
 *  - `page_node` → golden 的 raw 是**单条子句文本**（不是整棵树），所以这里按同样的粒度
 *    喂 `CouponPipeline.extract(clauses = [Clause(raw)])`。
 *    ⇒ 这一路量的是**抽取层**；`NodeAdapter` 的"金额碎片拼行"另有一组真机正/负样本测试钉着，
 *    不在这个分数里。把两者混着看会得出错误的结论。
 */
class CouponGoldenPredictionTest {

    private val goldenPath = File("../tools/golden/coupons.jsonl")
    private val outPath = File("build/coupon-predictions.jsonl")

    @Test
    fun `用真实流水线产出 predictions 供 eval_coupons 打分`() {
        assertTrue("golden 必须存在（路径漂了这条就白跑）：${goldenPath.absolutePath}", goldenPath.isFile)
        val goldenLines = goldenPath.readLines().filter { it.isNotBlank() }
        assertTrue("golden 不该是空的", goldenLines.isNotEmpty())

        val predictions = goldenLines.map { line ->
            val entry = JSONObject(line)
            val id = entry.getString("id")
            val source = entry.getString("source")
            val raw = entry.getString("raw")
            val extraction = when (source) {
                "clipboard" -> CouponExtractor.fromClipboard(raw)
                "community" -> CouponExtractor.fromPost(raw, 0L)
                "page_node" -> pageClause(raw)
                else -> error("golden 里出现未知 source「$source」（id=$id）——宁可直接失败，不要静默跳过")
            }
            render(id, source, extraction)
        }

        outPath.parentFile?.mkdirs()
        outPath.writeText(predictions.joinToString(separator = "\n", postfix = "\n"), Charsets.UTF_8)

        // 条数相等：少一行就是把一条判成漏抽（fn），静默地把分母改小，分数会凭空好看
        assertEquals("预测条数必须与 golden 相等", goldenLines.size, predictions.size)
        // 每行都能解析回来：写坏一行 python 侧 exit 2，与其到那边报错不如在这里就红
        predictions.forEach { text -> assertTrue("id 不该为空", JSONObject(text).getString("id").isNotEmpty()) }
        println("PREDICTIONS -> ${outPath.absolutePath} lines=${predictions.size}")
    }

    /** golden 的 page_node raw 是一条子句，按同粒度进管线（不伪造一棵树，见类注释的口径说明） */
    private fun pageClause(raw: String): Extraction = CouponPipeline.extract(
        clauses = listOf(Clause(text = raw)),
        source = ExtractSource.PAGE_NODE,
        platform = "unknown",
        itemRef = null,
        templates = CouponExtractor.templates
    )

    /**
     * 一行预测。**只输出 eval 脚本读得懂的键**（它按 `id` 对齐、只看 coupons 的 discount/threshold 判命中，
     * scope/state 进诊断榜、price 只做对照）。
     *
     * scope/state 输出**枚举名的小写**（`platform`/`claimable`…），而 golden 那些位置是人工写的自由词
     * （见过「国补」「立即领」）。两者不可能逐字相等 ⇒ 诊断榜里 scope/state 的差异会把
     * "词汇表没统一"这件事暴露出来，那是**标注侧的活**，不是流水线这轮要修的 bug。
     */
    private fun render(id: String, source: String, extraction: Extraction): String {
        val coupons = JSONArray()
        extraction.coupons.forEach { slot: CouponSlot ->
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
        val price = JSONObject()
            .put("final", extraction.price.finalPrice ?: JSONObject.NULL)
            .put("list", extraction.price.listPrice ?: JSONObject.NULL)
            .put("drop", extraction.price.drop ?: JSONObject.NULL)
        return JSONObject()
            .put("id", id)
            .put("source", source)
            .put("coupons", coupons)
            .put("price", price)
            .put("confidence", extraction.confidence)
            .toString()
    }
}
