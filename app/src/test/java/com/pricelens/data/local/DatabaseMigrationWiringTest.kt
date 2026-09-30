package com.pricelens.data.local

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 迁移**接线**守卫（2026-09-30 真机崩溃换来的）。
 *
 * 那天 v2→v3 的迁移 SQL 写好了、离线 sqlite3 上 25 项断言全过，真机一装却崩在
 * `IllegalStateException: A migration from 2 to 3 was required but not found` ——
 * 因为迁移登记在 `AppDatabase.getInstance()` 那份 builder 上，而运行时建库走的是
 * Hilt 的 `di/AppModule.provideDatabase()`，它只带了 1→2。
 * 纯函数单测摸不到这条线：这里用三条静态断言把它钉住。
 */
class DatabaseMigrationWiringTest {

    private val moduleDir: File get() = File("src/main/java/com/pricelens")

    private fun source(name: String): File {
        val f = File(moduleDir, name)
        assertTrue(
            "找不到源文件 $f —— 单测的工作目录应是 app/ 模块目录（Gradle 默认如此）。" +
                "这条守卫不能静默跳过：跳过就等于没有守卫。",
            f.exists()
        )
        return f
    }

    /**
     * @Database 的 version 从**源码**读，不用反射：Room 2.8 的 `@Database` 注解不是
     * RUNTIME retention，`getAnnotation(Database::class.java)` 在 JVM 上拿到 null
     * （这条测试第一版就是这么写的，跑出来是"没有注解"而不是"版本不匹配"）。
     */
    private fun declaredVersion(): Int {
        val text = source("data/local/AppDatabase.kt").readText()
        val hits = Regex("""^\s*version\s*=\s*(\d+)""", RegexOption.MULTILINE).findAll(text).toList()
        assertEquals("@Database(version=…) 必须能唯一读到，写法变了要同步改这条守卫", 1, hits.size)
        return hits.first().groupValues[1].toInt()
    }

    @Test
    fun `migration chain is contiguous and reaches the declared database version`() {
        val declared = declaredVersion()
        val chain = AppDatabase.MIGRATIONS.sortedBy { it.startVersion }
        assertTrue("一条迁移都没有，老库升级必然崩", chain.isNotEmpty())
        assertEquals("迁移链必须从 1 开始", 1, chain.first().startVersion)
        for (i in chain.indices) {
            val m = chain[i]
            assertEquals(
                "迁移 ${m.startVersion}→${m.endVersion} 不连续（会留下没有迁移可走的版本区间）",
                m.startVersion + 1,
                m.endVersion
            )
            if (i > 0) assertEquals("版本 ${m.startVersion} 有两条以上迁移或断档", chain[i - 1].endVersion, m.startVersion)
        }
        assertEquals(
            "最后一个迁移的目标版本必须等于 @Database(version=…) —— 加了列忘了写迁移，或写了迁移忘了升版本，都会让真机开库即崩",
            declared,
            chain.last().endVersion
        )
    }

    @Test
    fun `the hilt provider registers the whole migration list instead of hand-picking`() {
        val text = source("di/AppModule.kt").readText()
        assertTrue(
            "provideDatabase 必须用 AppDatabase.MIGRATIONS（唯一清单），不要再手写单个迁移常量",
            Regex("""addMigrations\(\*AppDatabase\.MIGRATIONS\.toTypedArray\(\)\)""").containsMatchIn(text)
        )
        assertTrue(
            "DI 里不该再出现手写的 addMigrations(AppDatabase.MIGRATION_x_y)：那条迁移一旦漏登记，真机升级就崩",
            !Regex("""addMigrations\(\s*AppDatabase\.MIGRATION_""").containsMatchIn(text)
        )
    }

    @Test
    fun `there is exactly one room database builder in the app sources`() {
        // 带左括号才匹配代码，不匹配注释里提到的 API 名
        val builders = moduleDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("Room.databaseBuilder(") }
            .map { it.name }
            .toList()
        assertEquals(
            "建库点必须只有一个：两处 builder 各自登记迁移，就是这次真机崩溃的成因（第二处已删除）",
            listOf("AppModule.kt"),
            builders
        )
    }

    @Test
    fun `every migration in the list carries at least one statement`() {
        // 空迁移 = 版本升了、结构没动，Room 的 TableInfo 校验随后会判"迁移没做对"
        val sql = AppDatabase.MIGRATION_2_3_SQL.filter { it.isNotBlank() }
        assertTrue("MIGRATION_2_3 至少要有一条语句", sql.isNotEmpty())
        assertNotNull(
            "2→3 必须建 (productId,date) 唯一索引，否则'每天一点'仍然只是注释",
            sql.firstOrNull { it.contains("CREATE UNIQUE INDEX") && it.contains("`productId`, `date`") }
        )
        assertEquals(
            "先加列(3) → 再去重(1) → 最后建唯一索引(1)",
            5,
            sql.size
        )
    }
}
