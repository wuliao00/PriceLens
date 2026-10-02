package com.pricelens.util

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 纯本地崩溃日志（文档 §十三的本地形态，2026-10-02 用户拍板：不上传）。
 *
 * 只做三件事：把崩溃现场写成小文本文件、裁掉超额旧文件、把导出文本里的
 * 凭证串统统打成 `***`。绝不联网、绝不写 Cookie/header（文档红线：日志与崩溃
 * 上报里严禁出现 Cookie/header）。
 */
object CrashLog {
    const val DIR_NAME = "crashlogs"
    const val MAX_KEEP = 20
    const val LOG_PREFIX = "crash-"
    const val LOG_SUFFIX = ".log"

    private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
    private val EXPORT_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.ROOT)
    private val NAME_RE = Regex("^${Regex.escape(LOG_PREFIX)}(\\d+)${Regex.escape(LOG_SUFFIX)}$")

    /**
     * 会写进日志/报告的敏感键。除文档点名的 cookie/authorization/apikey 外，
     * 加上我们实际会经手的会话串名（京东 pt_key/pt_pin、微信 wskey、淘宝 skey）。
     * 故意要求 = 或 : 作分隔，并一直吃到行尾 —— 值（含 "Basic xxx" 这类双 token）
     * 全部消失；散文里的「cookie 已过期」不会中招。
     */
    private val SECRET_RE = Regex(
        "(?i)(cookie|authorization|api[_-]?key|pt_key|pt_pin|wskey|skey)\\s*[=:][ \\t]*[^\\r\\n]*"
    )

    fun fileName(epochMs: Long): String = "$LOG_PREFIX$epochMs$LOG_SUFFIX"

    /** 文件名 → 毫秒时间戳；不是本模块的命名一律 null（裁剪时不许误删别人的文件） */
    fun parseEpoch(fileName: String): Long? = NAME_RE.matchEntire(fileName)?.groupValues?.get(1)?.toLongOrNull()

    fun formatTime(epochMs: Long, zone: ZoneId): String = TIME_FORMAT.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    /** 崩溃现场单条：时间 / 线程 / 机型与版本（由调用方给） / 完整堆栈 */
    fun buildEntry(epochMs: Long, zone: ZoneId, threadName: String, infoLines: List<String>, throwable: Throwable): String = buildString {
        appendLine("time=${formatTime(epochMs, zone)} (epoch=$epochMs)")
        appendLine("thread=$threadName")
        infoLines.forEach { appendLine(it) }
        appendLine("--- stack ---")
        append(throwable.stackTraceToString())
    }

    /** 敏感键后的值一律 ***；幂等。 */
    fun redact(text: String): String = text.replace(SECRET_RE) { "${it.groupValues[1]}=***" }

    /** 该删哪些文件（返回文件名）：只认 crash-*.log，keep 是保留条数 */
    fun pruneList(fileNames: List<String>, keep: Int): List<String> = fileNames
        .mapNotNull { name -> parseEpoch(name)?.let { name to it } }
        .sortedByDescending { it.second }
        .drop(keep.coerceAtLeast(0))
        .map { it.first }

    /** 导出文本：头（生成时间/版本/机型）+ 每条崩溃（新在前）+ 可选的 logcat；整体过一遍脱敏 */
    fun buildReport(generatedAtMs: Long, zone: ZoneId, appLine: String, entries: List<String>, logcat: String): String {
        val body = buildString {
            appendLine("PriceLens 诊断报告（本机生成，未上传）")
            appendLine("generated=${formatTime(generatedAtMs, zone)} (epoch=$generatedAtMs)")
            appendLine(appLine)
            appendLine("crashes=${entries.size}")
            entries.forEachIndexed { index, entry ->
                appendLine("======== crash ${index + 1}/${entries.size} ========")
                appendLine(entry.trimEnd())
            }
            if (logcat.isNotBlank()) {
                appendLine("======== logcat（最近 800 行，已脱敏） ========")
                append(logcat)
            }
        }
        return redact(body)
    }

    fun exportFileName(epochMs: Long, zone: ZoneId): String =
        "pricelens-diagnostics-${EXPORT_FORMAT.format(Instant.ofEpochMilli(epochMs).atZone(zone))}.txt"
}

/** 崩溃日志目录的最小封装；所有磁盘异常都由调用方兜住（崩溃处理器里 runCatching）。 */
class CrashLogStore(private val dir: File) {

    fun write(content: String, epochMs: Long): File {
        dir.mkdirs()
        val file = File(dir, CrashLog.fileName(epochMs))
        file.writeText(content)
        prune()
        return file
    }

    fun listNewestFirst(): List<File> = dir.listFiles()
        ?.mapNotNull { f -> CrashLog.parseEpoch(f.name)?.let { f to it } }
        ?.sortedByDescending { it.second }
        ?.map { it.first }
        ?: emptyList()

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    fun prune(keep: Int = CrashLog.MAX_KEEP) {
        val names = dir.listFiles()?.map { it.name } ?: return
        CrashLog.pruneList(names, keep).forEach { File(dir, it).delete() }
    }
}
