package com.pricelens.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「忽略」降价通知的记忆核心：
 * 忽略后同价/高价不再提醒；价格创新低自动解除；坏 JSON 一律当"没有忽略记录"（绝不永久掐死通知）。
 */
class NotifyIgnoreTest {

    @Test
    fun `ignored price suppresses equal or higher and allows lower`() {
        val ignored = mapOf("jd:100" to 99.0)
        assertTrue("同价仍抑制", NotifyIgnoreCore.suppress(ignored, "jd:100", 99.0))
        assertTrue("更高价抑制", NotifyIgnoreCore.suppress(ignored, "jd:100", 120.0))
        assertFalse("更低价放行", NotifyIgnoreCore.suppress(ignored, "jd:100", 98.9))
        assertFalse("没记录不抑制", NotifyIgnoreCore.suppress(ignored, "jd:200", 50.0))
    }

    @Test
    fun `notify with lower price clears the ignore memory`() {
        val ignored = mapOf("jd:100" to 99.0)
        val cleared = NotifyIgnoreCore.afterNotify(ignored, "jd:100", 90.0)
        assertEquals(emptyMap<String, Double>(), cleared)
        val kept = NotifyIgnoreCore.afterNotify(ignored, "jd:100", 99.0)
        assertEquals(ignored, kept)
        val untouched = NotifyIgnoreCore.afterNotify(ignored, "jd:200", 10.0)
        assertEquals(ignored, untouched)
    }

    @Test
    fun `ignore writes the current price and overwrite keeps the latest`() {
        var ignored = NotifyIgnoreCore.afterIgnore(emptyMap(), "jd:100", 99.0)
        assertEquals(mapOf("jd:100" to 99.0), ignored)
        ignored = NotifyIgnoreCore.afterIgnore(ignored, "jd:100", 95.0)
        assertEquals(mapOf("jd:100" to 95.0), ignored)
    }

    @Test
    fun `codec round trips and malformed json degrades to empty`() {
        val map = mapOf("jd:100" to 99.0, "jd:200" to 12.5)
        assertEquals(map, NotifyIgnoreCore.decode(NotifyIgnoreCore.encode(map)))
        assertEquals(emptyMap<String, Double>(), NotifyIgnoreCore.decode(null))
        assertEquals(emptyMap<String, Double>(), NotifyIgnoreCore.decode(""))
        assertEquals(emptyMap<String, Double>(), NotifyIgnoreCore.decode("{ not json"))
        // 值非法（负数/非数字）的键被丢弃，其余保留 —— 坏数据不许把整份记忆弄没
        assertEquals(
            mapOf("jd:100" to 9.0),
            NotifyIgnoreCore.decode("""{"jd:100":9.0,"jd:200":-5,"jd:300":"abc"}""")
        )
    }
}
