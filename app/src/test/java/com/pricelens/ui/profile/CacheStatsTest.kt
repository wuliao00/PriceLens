package com.pricelens.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 个人页统计行的取值形状（真机 2026-10-07 截图复核）。
 *
 * 改前的形状是 ViewModel 预拼一句 `"内存 0 KB · 图片 3 MB"`，UI 再给它套一个标签「缓存」，
 * 于是屏上读起来是「缓存 内存 0 KB · 图片 …」——**标签套标签**，而且单行放不下，
 * 最后一个字被省略号吃掉（截图里就是「图片 …」）。
 *
 * 所以这里钉两件事：
 *  1. 每一格的**值里不许再出现自己的标签**（双标签回归的钉子）；
 *  2. 未算出来时是一句"计算中…"，算出来之后两格各自是纯数值 + 单位。
 */
class CacheStatsTest {

    @Test
    fun `pending state is one honest placeholder in both cells`() {
        val s = CacheStats(memoryKb = null, imageMb = null)
        assertEquals("计算中…", s.memoryText)
        assertEquals("计算中…", s.imageText)
        assertEquals("计算中…", s.asSentence)
    }

    @Test
    fun `computed cells carry value and unit only, never their own label`() {
        val s = CacheStats(memoryKb = 12, imageMb = 3)
        // 「内存」/「图片」这两个词归 UI 的标签列管，值里再写一遍就是"标签套标签"
        assertFalse("值里不许重复标签：${s.memoryText}", s.memoryText.contains("内存"))
        assertFalse("值里不许重复标签：${s.imageText}", s.imageText.contains("图片"))
        assertEquals("12 KB", s.memoryText)
        assertEquals("3 MB", s.imageText)
    }

    @Test
    fun `sentence form is kept for the settings row which has a whole line`() {
        // 设置页那一行有整行宽度，用一句话更省事；个人页才需要拆成两格
        assertEquals("内存 12 KB · 图片 3 MB", CacheStats(12, 3).asSentence)
    }

    @Test
    fun `half computed state does not pretend to know the other half`() {
        val s = CacheStats(memoryKb = 0, imageMb = null)
        assertEquals("0 KB", s.memoryText)
        assertEquals("计算中…", s.imageText)
        assertTrue("一句话形式在没算全时不许只报一半", s.asSentence.contains("计算中"))
    }
}
