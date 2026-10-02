package com.pricelens.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.lang.reflect.Proxy
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
    fun `every registered migration carries non-blank and well-formed statements`() {
        // 空迁移 = 版本升了、结构没动，Room 的 TableInfo 校验随后会判"迁移没做对"。
        // 语句从「只记录不执行的假库」收，不抄常量清单：MIGRATION_1_2 根本没导出常量，
        // 而且抄一份永远比真的慢一步 —— 将来加 v4 迁移自动进这道守卫，不必改本测试。
        val recorded = AppDatabase.MIGRATIONS.associateWith { it.recordedStatements() }
        for ((migration, statements) in recorded) {
            val label = "迁移 ${migration.startVersion}→${migration.endVersion}"
            assertTrue("$label 一条语句都没有", statements.isNotEmpty())
            for (statement in statements) {
                val s = statement.trim()
                assertTrue("$label 存在空白语句", s.isNotEmpty())
                // 96 = 反引号；成对出现才说明 DDL 里标识符闭合
                val ticks = s.count { it == 96.toChar() }
                assertEquals("$label 反引号未配对：$s", 0, ticks % 2)
                // 40/41 = 左右括号；不配对多为字符串拼接被截断
                assertEquals("$label 括号未闭合：$s", s.count { it == 40.toChar() }, s.count { it == 41.toChar() })
                assertNotNull(
                    "$label 存在不以 DDL/DML 动词开头的语句：$s",
                    STATEMENT_STARTS.firstOrNull { s.startsWith(it) }
                )
                val upper = s.uppercase()
                for (tail in TRUNCATION_TAILS) {
                    assertTrue("$label 存在以『$tail』悬空收尾的语句：$s", !upper.endsWith(tail))
                }
            }
        }
        // 2→3 是已发布历史，它的独有约束单独钉：
        val v23 = recorded.getValue(AppDatabase.MIGRATION_2_3)
        assertNotNull(
            "2→3 必须建 (productId,date) 唯一索引，否则'每天一点'仍然只是注释",
            v23.firstOrNull { it.contains("CREATE UNIQUE INDEX") && it.contains("`productId`, `date`") }
        )
        assertEquals(
            "先加列(3) → 再去重(1) → 最后建唯一索引(1)；迁移不可变，改结构请走 v3→v4，别动这条",
            5,
            v23.size
        )
    }

    /**
     * 给迁移喂一个只记录 execSQL(String) 的动态代理假库，收上来的就是真机将执行的语句。
     * 迁移若开始用事务/查询等别的库能力，会在这里抛带说明的断言失败，而不是静默漏检。
     */
    private fun Migration.recordedStatements(): List<String> {
        val recorded = mutableListOf<String>()
        val db = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { proxy, method, args ->
            when {
                method.name == "execSQL" && args != null && args.size == 1 && args[0] is String -> {
                    recorded += args[0] as String
                    null
                }
                method.name == "toString" -> "RecordingFakeDatabase($startVersion→$endVersion)"
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "equals" -> args?.getOrNull(0) === proxy
                else -> throw AssertionError(
                    "迁移 $startVersion→$endVersion 调用了 ${method.name} —— 本守卫只覆盖 execSQL(String)；" +
                        "若迁移开始使用事务/查询等库能力，请显式扩展这个假库，而不是把守卫改成跳过。"
                )
            }
        } as SupportSQLiteDatabase
        migrate(db)
        return recorded
    }

    private companion object {
        /** 合法语句的首动词（本项目迁移 SQL 全大写）。 */
        val STATEMENT_STARTS = listOf(
            "ALTER", "CREATE", "DELETE", "DROP", "INSERT", "PRAGMA", "REPLACE", "UPDATE", "WITH"
        )

        /** 拼接被截断时语句末尾会露出的连接形态。 */
        val TRUNCATION_TAILS = listOf(",", "+", "(", " AND", " OR", " NOT")
    }
}
