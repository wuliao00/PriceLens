package com.pricelens.accuracy

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 用户纠错的本机记录仓（「这条不对」）。
 *
 * 设计约束：
 *  - **纯本机、不上传**：只写 `filesDir/accuracy_feedback.jsonl`，一行一条 JSON；
 *  - 只记录 [AccuracyIssueKind] + 当前 [AccuracySnapshot] 的**摘要**（档位/评分/headline/口径/
 *    证据条数/冲突条数/生成时刻）+ 关键词 + 备注 + 纠错时刻，不落整份证据，控制体积与隐私面；
 *  - 写入经 [Mutex] 串行化，IO 一律 [Dispatchers.IO]；[count] 是同步读文件，调用方必须在
 *    后台线程调用（ViewModel 在 IO 上初始化与刷新）；
 *  - 读失败/写失败都不抛给 UI：纠错是"记一笔"，记不上也不该打断浏览（[count] 回退 0，
 *    [record] 返回当前可读条数，[exportText] 回退空串）。
 *
 * 消费方：`SearchViewModel.reportAccuracyIssue(...)` 记录并刷新 `accuracyFeedbackCount`；
 * 设置页/诊断可调 [exportText] 导出文本（供用户自己复制，仍不上传）。
 */
@Singleton
class AccuracyFeedbackStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** 反馈文件（每行一条 JSON；不存在时 count=0、exportText=""） */
    private val file: File
        get() = File(context.filesDir, FILE_NAME)

    /** 写入串行化：并发「这条不对」不会交错写坏行 */
    private val writeLock = Mutex()

    /**
     * 当前已记录条数（同步读文件，**必须在后台线程调用**）。
     *
     * 只数非空行：崩溃/断电留下的半行不会让计数虚高。
     */
    fun count(): Int = runCatching { countLines(file) }.getOrDefault(0)

    /**
     * 追加一条纠错记录，返回写入后的总条数（写入失败也返回当前可读条数，不抛异常）。
     *
     * @param kind 纠错类型
     * @param keyword 纠错时的搜索关键词（截断到 [MAX_KEYWORD_CHARS]）
     * @param note 用户可选备注（截断到 [MAX_NOTE_CHARS]；null 记 JSON null）
     * @param snapshot 纠错时的准确性快照（只存摘要，不存整份证据）
     */
    suspend fun record(kind: AccuracyIssueKind, keyword: String, note: String? = null, snapshot: AccuracySnapshot? = null): Int =
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                val line = buildRecord(kind, keyword, note, snapshot).toString()
                runCatching {
                    file.parentFile?.mkdirs()
                    file.appendText(line + "\n", Charsets.UTF_8)
                }
                countLines(file)
            }
        }

    /**
     * 导出全部记录原文（JSONL 文本，不联网）。
     *
     * 供设置页"复制反馈记录"用；文件不存在或读失败返回空串，UI 据此不渲染入口。
     */
    suspend fun exportText(): String = withContext(Dispatchers.IO) {
        runCatching { if (file.exists()) file.readText(Charsets.UTF_8) else "" }.getOrDefault("")
    }

    private fun buildRecord(kind: AccuracyIssueKind, keyword: String, note: String?, snapshot: AccuracySnapshot?): JSONObject =
        JSONObject().apply {
            put("kind", kind.name)
            put("atMs", System.currentTimeMillis())
            put("keyword", keyword.trim().take(MAX_KEYWORD_CHARS))
            put("note", note?.trim()?.take(MAX_NOTE_CHARS) ?: JSONObject.NULL)
            put("snapshot", snapshotSummary(snapshot) ?: JSONObject.NULL)
        }

    /** 快照摘要：只存能复盘"用户当时看到什么档位/口径"的最小字段，不存证据明细 */
    private fun snapshotSummary(snapshot: AccuracySnapshot?): JSONObject? = snapshot?.let {
        JSONObject()
            .put("level", it.level.name)
            .put("score", it.score)
            .put("headline", it.headline)
            .put("basis", it.basis.name)
            .put("basisLabel", it.basisLabel)
            .put("evidenceCount", it.evidence.size)
            .put("conflictCount", it.conflicts.size)
            .put("generatedAtMs", it.generatedAtMs)
    }

    private fun countLines(target: File): Int {
        if (!target.exists()) return 0
        return target.useLines { lines -> lines.count { it.isNotBlank() } }
    }

    companion object {
        /** 反馈文件名（filesDir 下；纯本机 JSONL） */
        const val FILE_NAME: String = "accuracy_feedback.jsonl"

        /** 关键词最长保留字符数 */
        const val MAX_KEYWORD_CHARS: Int = 120

        /** 备注最长保留字符数 */
        const val MAX_NOTE_CHARS: Int = 240
    }
}
