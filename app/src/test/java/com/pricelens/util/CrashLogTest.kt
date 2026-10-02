package com.pricelens.util

import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯本地崩溃日志（文档 §十三的本地形态，2026-10-02 拍板：不上传、无 ACRA）。
 *
 * 钉住四件事：
 *  1. 留存策略只动自己的 crash-*.log，绝不误删同目录其它文件；
 *  2. 崩溃条目带时间/线程/机型信息与完整堆栈；
 *  3. 导出文本整体脱敏（Cookie / Authorization / apikey / 会话串名后的值一律打成 ***），
 *     且对散文零误伤；
 *  4. 落盘行为：写入、按新在前列出、清空、超量裁剪。
 */
class CrashLogTest {

    // ---- 文件名与留存 ----

    @Test
    fun `file name round trips through parseEpoch`() {
        val name = CrashLog.fileName(1_700_000_000_123)
        assertEquals("crash-1700000000123.log", name)
        assertEquals(1_700_000_000_123L, CrashLog.parseEpoch(name))
    }

    @Test
    fun `parseEpoch rejects anything not in our naming`() {
        assertNull(CrashLog.parseEpoch("crash-123.log.bak"))
        assertNull(CrashLog.parseEpoch("crash-.log"))
        assertNull(CrashLog.parseEpoch("crash--5.log"))
        assertNull(CrashLog.parseEpoch("crash-12a.log"))
        assertNull(CrashLog.parseEpoch("notes.txt"))
    }

    @Test
    fun `prune keeps the newest N and deletes only the oldest own files`() {
        val names = (1..25).map { CrashLog.fileName(1_700_000_000_000 + it) }
        val toDelete = CrashLog.pruneList(names, keep = 20)
        assertEquals(5, toDelete.size)
        assertEquals(names.take(5).toSet(), toDelete.toSet())
    }

    @Test
    fun `prune never touches foreign files`() {
        val names = listOf(
            "crash-1700000000001.log",
            "crash-1700000000002.log",
            "crash-1700000000003.log",
            "backup.db",
            "crash-notanumber.log",
            "笔记.txt"
        )
        assertEquals(listOf("crash-1700000000001.log"), CrashLog.pruneList(names, keep = 2))
    }

    // ---- 条目 ----

    @Test
    fun `entry carries time thread info lines and the full stack`() {
        val entry = CrashLog.buildEntry(
            epochMs = 0L,
            zone = ZoneId.of("UTC"),
            threadName = "main",
            infoLines = listOf("app=PriceLens 2.8.0 (21)", "android=11 (sdk=30)", "device=vivo V2156A / PD2156"),
            throwable = IllegalStateException("boom-marker-7c3")
        )
        assertTrue(entry.contains("time=1970-01-01 00:00:00 (epoch=0)"))
        assertTrue(entry.contains("thread=main"))
        assertTrue(entry.contains("app=PriceLens 2.8.0 (21)"))
        assertTrue(entry.contains("device=vivo V2156A / PD2156"))
        assertTrue(entry.contains("--- stack ---"))
        assertTrue(entry.contains("java.lang.IllegalStateException: boom-marker-7c3"))
    }

    // ---- 脱敏 ----

    @Test
    fun `redact masks values after cookie auth and keys but keeps the names`() {
        val raw = "Cookie: pt_key=AAJxxxSECRET; pt_pin=user123;\n" +
            "Authorization: Basic QWxhZGRpbjpvcGVu\n" +
            "apikey = sk-live-9f8e7d\n" +
            "wskey=wxsecret==;"
        val safe = CrashLog.redact(raw)
        assertFalse(safe.contains("AAJxxxSECRET"))
        assertFalse("cookie 行里的第二个会话名值也要整行消失", safe.contains("user123"))
        assertFalse(safe.contains("QWxhZGRpbjpvcGVu"))
        assertFalse(safe.contains("sk-live-9f8e7d"))
        assertFalse(safe.contains("wxsecret"))
        assertTrue(safe.contains("Cookie=***"))
        assertTrue(safe.contains("Authorization=***"))
        assertTrue(safe.contains("apikey=***"))
        assertTrue(safe.contains("wskey=***"))
    }

    @Test
    fun `redact is idempotent`() {
        val once = CrashLog.redact("Cookie: a=b; apikey=xx")
        assertEquals(once, CrashLog.redact(once))
    }

    @Test
    fun `redact leaves ordinary prose alone`() {
        val text = "已保存的 cookie 已过期；价格 123 元，无匹配结果"
        assertEquals(text, CrashLog.redact(text))
    }

    // ---- 导出报告 ----

    @Test
    fun `report includes header all crashes in order and logcat when present`() {
        val report = CrashLog.buildReport(
            generatedAtMs = 0L,
            zone = ZoneId.of("UTC"),
            appLine = "app=PriceLens 2.8.0 (21) / android=11 (sdk=30)",
            entries = listOf("E1-body", "E2-body"),
            logcat = "line-a\nline-b"
        )
        assertTrue(report.contains("generated=1970-01-01 00:00:00 (epoch=0)"))
        assertTrue(report.contains("app=PriceLens 2.8.0 (21) / android=11 (sdk=30)"))
        assertTrue(report.contains("crashes=2"))
        assertTrue(report.indexOf("E1-body") < report.indexOf("E2-body"))
        assertTrue(report.contains("logcat"))
        assertTrue(report.contains("line-b"))
    }

    @Test
    fun `report omits the logcat section when nothing was captured`() {
        val report = CrashLog.buildReport(0L, ZoneId.of("UTC"), "app=x", emptyList(), logcat = "")
        assertFalse(report.contains("logcat"))
        assertTrue(report.contains("crashes=0"))
    }

    @Test
    fun `report is redacted as a whole`() {
        val report = CrashLog.buildReport(
            generatedAtMs = 0L,
            zone = ZoneId.of("UTC"),
            appLine = "app=x",
            entries = listOf("boom\nCookie: top-secret-token"),
            logcat = "Authorization: Basic dXNlcjpwYXNz"
        )
        assertFalse(report.contains("top-secret-token"))
        assertFalse(report.contains("dXNlcjpwYXNz"))
    }

    @Test
    fun `export file name is ascii and timestamped`() {
        assertEquals(
            "pricelens-diagnostics-19700101-0000.txt",
            CrashLog.exportFileName(0L, ZoneId.of("UTC"))
        )
    }

    // ---- 落盘 ----

    @Test
    fun `store writes lists newest first and clears`() {
        val dir = Files.createTempDirectory("crashlogs-test").toFile()
        val store = CrashLogStore(dir)
        store.write("first", 1_000L)
        store.write("second", 2_000L)

        val files = store.listNewestFirst()
        assertEquals(listOf("crash-2000.log", "crash-1000.log"), files.map { it.name })
        assertEquals("second", files.first().readText())

        store.clear()
        assertEquals(0, store.listNewestFirst().size)
    }

    @Test
    fun `store keeps at most MAX_KEEP and never deletes foreign files`() {
        val dir = Files.createTempDirectory("crashlogs-keep").toFile()
        File(dir, "keepme.txt").writeText("本目录里别的文件不该被删")
        val store = CrashLogStore(dir)
        repeat(CrashLog.MAX_KEEP + 5) { i -> store.write("n$i", 1_000L + i) }

        val files = store.listNewestFirst()
        assertEquals(CrashLog.MAX_KEEP, files.size)
        assertEquals("crash-1005.log", files.last().name)
        assertTrue(File(dir, "keepme.txt").exists())
    }
}
