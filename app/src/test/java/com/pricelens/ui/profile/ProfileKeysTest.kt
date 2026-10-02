package com.pricelens.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 「我的」页同一个商品既是收藏又是盯价目标时，两段行 key 不许撞车。
 * 背景见 [ProfileKeys] 的注释：真机（OPPO PLB110）第一轮走查就是被这个崩溃拦下的。
 */
class ProfileKeysTest {

    @Test
    fun `同一商品的收藏行与目标行 key 不同`() {
        val id = "jd:100012043978"
        assertNotEquals(ProfileKeys.pin(id), ProfileKeys.target(id))
    }

    @Test
    fun `收藏与盯价混排时整页没有重复 key`() {
        val pinned = listOf("jd:100012043978", "tb:123")
        val targets = listOf("jd:100012043978", "pdd:999")
        val keys = pinned.map { ProfileKeys.pin(it) } + targets.map { ProfileKeys.target(it) }
        assertEquals(emptyList<String>(), ProfileKeys.duplicates(keys))
    }

    /** 判别例：把两段前缀写成同一个（就是真机上崩掉的那种写法），本用例必须变红 */
    @Test
    fun `前缀退化时能抓到重复`() {
        val id = "jd:100012043978"
        val keys = listOf(ProfileKeys.pin(id), ProfileKeys.pin(id))
        assertEquals(listOf("pin:$id"), ProfileKeys.duplicates(keys))
    }

    @Test
    fun `空列表没有重复项`() {
        assertEquals(emptyList<String>(), ProfileKeys.duplicates(emptyList()))
    }
}
