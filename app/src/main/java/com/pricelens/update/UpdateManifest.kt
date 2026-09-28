package com.pricelens.update

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.json.JSONArray
import org.json.JSONObject

/**
 * 更新清单数据模型（对标 Magisk `stable.json` 的"一个静态 JSON + 一条链接"极简形态，
 * 字段命名对齐 XUpdate `UpdateEntity`：hasUpdate / isForce / isIgnorable / versionCode /
 * versionName / updateContent / downloadEntity / cooldown）。
 *
 * 铁律：本文件**不 import 任何 Android 框架类**，只依赖 org.json + java.time，
 * 因此可在 JVM 单元测试中直接跑（test 源集已引入 org.json 参考实现）。
 * 序列化框架一个都不引（红线：APK < 20MB、不引序列化库），手写 opt* 解析。
 */

/** 已知清单结构版本；不认识的版本一律按"无更新"处理（fail-open，永不阻断） */
const val MANIFEST_SCHEMA_VERSION = 1

/**
 * 下载目标类型：
 *  - APK  = 可应用内直连下载（需配合 sha256 校验）
 *  - PAGE = 人工兜底下载页（只能开浏览器，绝不进自动下载链路）
 */
enum class ApkTargetKind {
    APK,
    PAGE
}

/**
 * 单个下载目标。三级顺序由数组顺序决定（清单即优先级）：
 *  Gitee 发行版附件 → GitHub Release → 手动下载页。
 */
data class ApkTarget(val label: String, val url: String, val kind: ApkTargetKind)

/** latest 块：新版本号信息（比较一律用 [versionCode] 整数，绝不用 versionName 字符串） */
data class Latest(val versionCode: Int, val versionName: String, val releaseDate: String)

data class UpdateManifest(
    val schemaVersion: Int,
    /** generatedAt 解析后的 epoch 毫秒；用于"距今 < 30 天才允许阻断"的新鲜度闸门 */
    val generatedAtMs: Long,
    val channel: String,
    val latest: Latest,
    /** 唯一触发**阻断**的阈值：current < 它 → 强制更新弹窗 */
    val minSupportedVersionCode: Int,
    /** 次级强提示阈值：current < 它 → 可跳过的强提示弹窗 */
    val forceBelow: Int,
    /** 灰度百分比（仅作用于强提示/可选提示；阻断是安全底线，不受灰度影响） */
    val rolloutPercent: Int,
    val apkUrls: List<ApkTarget>,
    val sha256: String,
    val sizeBytes: Long,
    /** 更新说明（对应 XUpdate updateContent），逐条展示 */
    val notes: List<String>,
    /** "以后再说"的静默时长 */
    val cooldownHours: Int
) {

    /** 首个可应用内下载的直链 */
    val apkTarget: ApkTarget? = apkUrls.firstOrNull { it.kind == ApkTargetKind.APK }

    /** 人工兜底下载页 */
    val pageTarget: ApkTarget? = apkUrls.firstOrNull { it.kind == ApkTargetKind.PAGE }

    companion object {

        /**
         * 解析清单。**任何**结构问题（坏 JSON / 缺字段 / schemaVersion 未知 / 无下载目标）
         * 都返回 [ManifestResult.Rejected]，由上层按"无更新"静默处理（fail-open）。
         *
         * 注意：`sha256` 与 `generatedAt` 的新鲜度**不在这里**判定——它们是"能否阻断"的闸门，
         * 属于 [UpdateEvaluator] 的职责，保持"解析"与"决策"分离以便分别单测。
         */
        fun parse(raw: String?): ManifestResult {
            if (raw.isNullOrBlank()) {
                return ManifestResult.Rejected(RejectReason.EMPTY_BODY)
            }
            val root = try {
                JSONObject(raw)
            } catch (_: Exception) {
                return ManifestResult.Rejected(RejectReason.JSON_UNPARSEABLE)
            }

            val missing = mutableListOf<String>()

            val schemaVersion = root.intOrNull("schemaVersion").also {
                if (it == null) missing.add("schemaVersion")
            }
            val generatedAtMs = epochMillisOf(root.opt("generatedAt")).also {
                if (it == null) missing.add("generatedAt")
            }
            val latest = root.optJSONObject("latest").let { node ->
                if (node == null) {
                    missing.add("latest")
                    null
                } else {
                    val code = node.intOrNull("versionCode")
                    if (code == null) missing.add("latest.versionCode")
                    val name = node.stringOrNull("versionName")
                    if (name == null) missing.add("latest.versionName")
                    if (code == null || name == null) {
                        null
                    } else {
                        Latest(code, name, node.stringOrNull("releaseDate").orEmpty())
                    }
                }
            }
            val minSupported = root.intOrNull("minSupportedVersionCode").also {
                if (it == null) missing.add("minSupportedVersionCode")
            }

            if (missing.isNotEmpty()) {
                return ManifestResult.Rejected(RejectReason.MISSING_FIELDS + ":" + missing.joinToString(","))
            }
            // 上面的 missing 检查已保证这四个值非空；elvis-return 只为满足类型系统（不使用 !!）
            val schema = schemaVersion ?: return ManifestResult.Rejected(RejectReason.MISSING_FIELDS + ":schemaVersion")
            val generatedAt = generatedAtMs ?: return ManifestResult.Rejected(RejectReason.MISSING_FIELDS + ":generatedAt")
            val latestInfo = latest ?: return ManifestResult.Rejected(RejectReason.MISSING_FIELDS + ":latest")
            val minSupportedInfo = minSupported
                ?: return ManifestResult.Rejected(RejectReason.MISSING_FIELDS + ":minSupportedVersionCode")

            val targets = parseTargets(root.optJSONArray("apkUrls"))
            if (targets.isEmpty()) {
                return ManifestResult.Rejected(RejectReason.NO_DOWNLOAD_TARGET)
            }
            // schemaVersion 未知 → 这份清单的语义我们无法保证，静默跳过（绝不据它阻断用户）
            if (schema != MANIFEST_SCHEMA_VERSION) {
                return ManifestResult.Rejected(RejectReason.UNKNOWN_SCHEMA)
            }

            return ManifestResult.Valid(
                UpdateManifest(
                    schemaVersion = schema,
                    generatedAtMs = generatedAt,
                    channel = root.stringOrNull("channel") ?: "stable",
                    latest = latestInfo,
                    minSupportedVersionCode = minSupportedInfo,
                    forceBelow = root.intOrNull("forceBelow") ?: minSupportedInfo,
                    rolloutPercent = (root.intOrNull("rolloutPercent") ?: 100).coerceIn(0, 100),
                    apkUrls = targets,
                    sha256 = root.stringOrNull("sha256").orEmpty().lowercase(),
                    sizeBytes = root.longOrNull("sizeBytes") ?: 0L,
                    notes = parseNotes(root.optJSONArray("notes")),
                    cooldownHours = (root.intOrNull("cooldownHours") ?: DEFAULT_COOLDOWN_HOURS)
                        .coerceIn(1, MAX_COOLDOWN_HOURS)
                )
            )
        }

        const val DEFAULT_COOLDOWN_HOURS = 24
        const val MAX_COOLDOWN_HOURS = 720

        /** 只有 64 位小写十六进制才是可信 sha256；占位符/长度不对一律视为"不可校验" */
        fun isSha256Hex(value: String): Boolean = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

        private fun parseTargets(array: JSONArray?): List<ApkTarget> {
            if (array == null) return emptyList()
            val out = ArrayList<ApkTarget>(array.length())
            for (i in 0 until array.length()) {
                val node = array.optJSONObject(i) ?: continue
                val url = node.stringOrNull("url") ?: continue
                val kind = if (node.stringOrNull("kind")?.equals("page", ignoreCase = true) == true) {
                    ApkTargetKind.PAGE
                } else {
                    ApkTargetKind.APK
                }
                out.add(ApkTarget(node.stringOrNull("label") ?: url, url, kind))
            }
            return out
        }

        private fun parseNotes(array: JSONArray?): List<String> {
            if (array == null) return emptyList()
            val out = ArrayList<String>(array.length())
            for (i in 0 until array.length()) {
                val note = array.optString(i).trim()
                if (note.isNotEmpty()) out.add(note)
            }
            return out
        }

        /**
         * `generatedAt` 兼容三种写法：ISO-8601 带时区（`2026-09-26T00:00:00Z`）、
         * 纯日期（`2026-09-26`，按 UTC 零点）、epoch 毫秒 / 秒（数值）。
         */
        private fun epochMillisOf(value: Any?): Long? = when (value) {
            is Number -> value.toLong().let { if (it < SECOND_EPOCH_THRESHOLD) it * 1000 else it }
            is String -> parseDateString(value.trim())
            else -> null
        }

        private fun parseDateString(text: String): Long? {
            if (text.isEmpty()) return null
            runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()?.let { return it }
            runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
            runCatching {
                LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            }.getOrNull()?.let { return it }
            // 数字写成字符串的情况（"1762000000" / "1762000000000"）
            return runCatching { text.toLong() }.getOrNull()?.let {
                if (it < SECOND_EPOCH_THRESHOLD) it * 1000 else it
            }
        }

        private const val ABSENT_INT = Int.MIN_VALUE
        private const val ABSENT_LONG = Long.MIN_VALUE

        /** 字段缺失或类型不对 → null（与"值为 Int.MIN_VALUE"区分开） */
        private fun JSONObject.intOrNull(name: String): Int? {
            if (!has(name)) return null
            val value = optInt(name, ABSENT_INT)
            return if (value == ABSENT_INT) null else value
        }

        private fun JSONObject.longOrNull(name: String): Long? {
            if (!has(name)) return null
            val value = optLong(name, ABSENT_LONG)
            return if (value == ABSENT_LONG) null else value
        }

        private fun JSONObject.stringOrNull(name: String): String? = opt(name)?.toString()?.trim()?.takeIf { it.isNotEmpty() }

        /** 小于该阈值的数值按"秒"理解（1e11 毫秒 ≈ 1973 年，不可能作为毫秒值） */
        private const val SECOND_EPOCH_THRESHOLD = 100_000_000_000L
    }
}

/** 解析结果：有效清单，或被拒绝的原因（拒绝=按无更新静默处理） */
sealed interface ManifestResult {
    data class Valid(val manifest: UpdateManifest) : ManifestResult
    data class Rejected(val reason: String) : ManifestResult
}

/** 机器可读拒绝原因（仅入日志与单测断言，不直接展示给用户） */
object RejectReason {
    const val EMPTY_BODY = "empty_body"
    const val JSON_UNPARSEABLE = "json_unparseable"
    const val MISSING_FIELDS = "missing_fields"
    const val UNKNOWN_SCHEMA = "unknown_schema"
    const val NO_DOWNLOAD_TARGET = "no_download_target"
}
