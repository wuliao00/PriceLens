package com.pricelens.coupon

import com.pricelens.coupon.model.CouponScope
import com.pricelens.coupon.model.CouponSlot
import com.pricelens.coupon.model.CouponState
import com.pricelens.coupon.model.ExtractSource
import com.pricelens.coupon.model.Extraction
import com.pricelens.util.CrashLog
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 任务书 B2 交付二：一键"这条不对" → 纯本地导出。
 *
 * 三条口径：
 *  1. 点按钮**只加内存里的一份待纠错清单**（[MisreadLedger]）：不弹窗、不上传、不联网，
 *     清单随进程消失——没有核验层的猜测不该看起来像用户的资产，更不该偷偷进任何数据库；
 *  2. 导出文本 [CouponMisreadJsonl] 与 `tools/golden/coupons.jsonl` 逐字段兼容：
 *     判据是 `tools/eval_coupons.py` 的解析函数——条目必须有 id / source / coupons 三键，
 *     source 只认 page_node / clipboard / community，券条目必须**齐且仅齐**
 *     discount / threshold / scope / state / expiry / url 六键（多一键少一键 eval 都判红）。
 *     格式不一致的导出文件回灌不进评测集，这条链子就白做，所以 `MisreadExportTest`
 *     用固定表逐字段把导出的行解析回来对齐；
 *  3. 落盘沿用崩溃日志那条纯本地链路（files 下子目录、毫秒时间戳命名、保数量裁剪、
 *     SAF 兜底由 UI 层按 `CrashHandler.exportTo` 的写法走 content resolver），
 *     脱敏直接复用 [CrashLog.redact]：凭证 / Cookie / token 不落盘（SecretStore 同源红线）。
 *
 * 本版**没有核验层**：措辞不许声称核过或确认过；导出行里也没有这类词。
 * 这份文件要人工放进 `tools/golden/` 才会参与回归（默认不自动进），每行 note 字段里写明。
 */

/** 标记的键：抽取内下标 + 那张券自己的原文。同一段文案在新一轮抽取里再被标中，不重复记账 */
data class MisreadKey(val index: Int, val sourceText: String)

/** 一条被用户点过"这条不对"的券（连同它所属的抽取上下文，导出时缺一样都回查不了） */
data class MisreadMark(val key: MisreadKey, val coupon: CouponSlot, val extraction: Extraction, val markedAtMs: Long)

/**
 * 内存里的待纠错清单（进程级，不入库）。
 *
 * 只增不减是刻意的：反悔的方式是"不导出"，而不是再做一个删除 UI ——
 * 本版唯一的数据出口就是那份 JSONL。
 */
class MisreadLedger {

    private val marks = LinkedHashMap<MisreadKey, MisreadMark>()

    /** 标记一次。成功返回 true；下标越界或重复标记返回 false（幂等，调用方可直接当"按钮点了没用"处理） */
    fun mark(extraction: Extraction, index: Int, nowMs: Long): Boolean {
        val coupon = extraction.coupons.getOrNull(index) ?: return false
        val key = MisreadKey(index, coupon.sourceText)
        if (marks.containsKey(key)) return false
        marks[key] = MisreadMark(key, coupon, extraction, nowMs)
        return true
    }

    fun isMarked(extraction: Extraction, index: Int): Boolean {
        val coupon = extraction.coupons.getOrNull(index) ?: return false
        return marks.containsKey(MisreadKey(index, coupon.sourceText))
    }

    fun markedCount(): Int = marks.size

    /** 导出用快照：按标记顺序，逐行序号保证 id 唯一（同一毫秒标记的两条也不会撞 id） */
    fun snapshot(): List<MisreadMark> = marks.values.toList()

    fun clear() {
        marks.clear()
    }
}

/**
 * 与 golden 逐字段兼容的 JSONL 序列化（一行一条标记过的券）。
 *
 * 字段口径（全部照 `tools/eval_coupons.py`）：
 *  - id：`usermark-<标记毫秒>-<行序>`，非空且行行唯一；
 *  - source：[ExtractSource] 与 golden 的三值一一对应（[goldenSource]）；
 *  - raw：这张券自己的 [CouponSlot.sourceText]（规整后的那一句，不是整段输入）；
 *  - coupons：单元素数组，六键齐且仅齐。scope/state 是**枚举名小写**而不是原文措辞
 *    （CouponSlot 没存原文口径词），回灌前须人工照 raw 校准——note 里写明了这一点；
 *  - price：抽取时读到的商品价三键，原样带上（golden 里 price 与券分属两套槽位，不许互抄）；
 *  - user_mark：用户的纠错载体（golden 里这键存在且为 null，这里放标记详情；券码 code
 *    也寄存在这里，因为 golden 的券条目没有 code 槽）；
 *  - note：怎么让这条进回归的人话说明。
 *
 * 只导被标记的券；脱敏对每个自由文本字段单独做（JSON 骨架不整行过正则，
 * 否则凭证串会把整行后半段吃掉，把好字段也毁掉）。
 */
object CouponMisreadJsonl {

    const val NOTE = "用户在真机点了『这条不对』，本机导出；须人工核对后放进 tools/golden/coupons.jsonl 才会参与回归（默认不自动进）。" +
        "scope/state 是枚举名不是原文措辞，回灌前照 raw 校准；券码 code 在 user_mark 里；origin 需人工补（eval 的来源核对查得到才算数）。"

    /** 理解层的入口枚举 → golden 的 source 三值 */
    fun goldenSource(source: ExtractSource): String = when (source) {
        ExtractSource.PAGE_NODE -> "page_node"
        ExtractSource.CLIPBOARD -> "clipboard"
        ExtractSource.COMMUNITY -> "community"
    }

    fun couponJson(coupon: CouponSlot): JSONObject = JSONObject()
        .put("discount", coupon.discount ?: JSONObject.NULL)
        .put("threshold", coupon.threshold ?: JSONObject.NULL)
        .put("scope", if (coupon.scope == CouponScope.UNKNOWN) JSONObject.NULL else coupon.scope.name.lowercase())
        .put("state", if (coupon.state == CouponState.UNKNOWN) JSONObject.NULL else coupon.state.name.lowercase())
        .put("expiry", safe(coupon.expiry) ?: JSONObject.NULL)
        .put("url", safe(coupon.url) ?: JSONObject.NULL)

    /** 单行序列化（含脱敏）；seq 从 1 起，只为 id 唯一性。逐语句构建，不在链式参数里嵌多行对象 */
    fun line(mark: MisreadMark, seq: Int): String {
        val coupon = mark.coupon
        val extraction = mark.extraction
        val price = JSONObject()
        price.put("final", extraction.price.finalPrice ?: JSONObject.NULL)
        price.put("list", extraction.price.listPrice ?: JSONObject.NULL)
        price.put("drop", extraction.price.drop ?: JSONObject.NULL)
        val userMark = JSONObject()
        userMark.put("verdict", "user_says_wrong")
        userMark.put("platform", safe(extraction.platform) ?: "")
        userMark.put("item_ref", safe(extraction.itemRef) ?: JSONObject.NULL)
        userMark.put("confidence", extraction.confidence)
        userMark.put("tier", Tiers.of(extraction.confidence).name)
        userMark.put("extractor", extraction.extractor.name)
        userMark.put("code", safe(coupon.code) ?: JSONObject.NULL)
        userMark.put("node_path", JSONArray(coupon.nodePath))
        userMark.put("marked_at_ms", mark.markedAtMs)
        val entry = JSONObject()
        entry.put("id", "usermark-${mark.markedAtMs}-$seq")
        entry.put("source", goldenSource(extraction.source))
        entry.put("raw", safe(coupon.sourceText) ?: "")
        entry.put("coupons", JSONArray().put(couponJson(coupon)))
        entry.put("price", price)
        entry.put("user_mark", userMark)
        entry.put("note", NOTE)
        return entry.toString()
    }

    /** 全量导出：每标记一条一行，末尾留换行（JSONL 惯例）；一条都没标记就是空文本，不写孤行 */
    fun export(marks: List<MisreadMark>): String {
        if (marks.isEmpty()) return ""
        return marks.mapIndexed { index, mark -> line(mark, index + 1) }.joinToString("\n") + "\n"
    }

    /** 自由文本落盘前逐字段脱敏：凭证 / Cookie / token 键后的值一律 ***（复用崩溃日志同一把闸） */
    private fun safe(value: String?): String? = value?.let { CrashLog.redact(it) }
}

/**
 * 本机落盘通道：崩溃日志链路的同款（files 下独立子目录 + 毫秒命名 + 只认自家前缀的裁剪）。
 *
 * 不复用 crashlogs 目录本身：崩溃处理器与用户导出走的是那份文件，混在一起裁剪会误删；
 * 复用的是**机制**——同样的命名/裁剪/UTF-8 写法，同样的"绝不联网、用户自己要才写"。
 */
object CouponMisreadFiles {
    const val DIR_NAME = "couponmisreads"
    const val FILE_PREFIX = "coupon-misread-"
    const val FILE_SUFFIX = ".jsonl"

    /** 保留条数沿用崩溃日志的预算，不另立数字 */
    const val MAX_KEEP = CrashLog.MAX_KEEP

    private val NAME_RE = Regex("^${Regex.escape(FILE_PREFIX)}(\\d+)${Regex.escape(FILE_SUFFIX)}$")

    fun fileName(epochMs: Long): String = "$FILE_PREFIX$epochMs$FILE_SUFFIX"

    /** 文件名 → 毫秒；不是本模块命名一律 null（裁剪不许误删别人的文件） */
    fun parseEpoch(fileName: String): Long? = NAME_RE.matchEntire(fileName)?.groupValues?.get(1)?.toLongOrNull()

    fun pruneList(fileNames: List<String>, keep: Int): List<String> = fileNames
        .mapNotNull { name -> parseEpoch(name)?.let { name to it } }
        .sortedByDescending { it.second }
        .drop(keep.coerceAtLeast(0))
        .map { it.first }
}

/** 目录封装；磁盘异常由调用方兜住（与 CrashLogStore 同款契约） */
class CouponMisreadStore(private val dir: File) {

    fun write(content: String, epochMs: Long): File {
        dir.mkdirs()
        val file = File(dir, CouponMisreadFiles.fileName(epochMs))
        file.writeText(content, Charsets.UTF_8)
        prune()
        return file
    }

    fun listNewestFirst(): List<File> = dir.listFiles()
        ?.mapNotNull { f -> CouponMisreadFiles.parseEpoch(f.name)?.let { f to it } }
        ?.sortedByDescending { it.second }
        ?.map { it.first }
        ?: emptyList()

    fun prune(keep: Int = CouponMisreadFiles.MAX_KEEP) {
        val names = dir.listFiles()?.map { it.name } ?: return
        CouponMisreadFiles.pruneList(names, keep).forEach { File(dir, it).delete() }
    }
}
