package com.pricelens.data.backup

import com.pricelens.data.local.entity.PriceTargetEntity
import com.pricelens.data.local.entity.ProductEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §五 恢复合并「新者胜」与保留策略的纯逻辑回归（不碰 Room / 网络）。
 */
class BackupMergeTest {

    private fun localFavorite(cachedAt: Long, lastAccessedAt: Long) = ProductEntity(
        id = "jd:1",
        title = "本机版",
        currentPrice = 100.0,
        originalPrice = null,
        platform = "jd",
        imageUrl = "https://img.example.com/1.jpg",
        cachedAt = cachedAt,
        lastAccessedAt = lastAccessedAt,
        ttl = 1_800_000L,
        pinned = true
    )

    private fun remoteFavorite(updatedAt: Long) = FavoriteDto(
        id = "jd:1",
        title = "远端版",
        currentPrice = 90.0,
        originalPrice = 120.0,
        platform = "jd",
        imageUrl = "https://img.example.com/1.jpg",
        cachedAt = updatedAt,
        lastAccessedAt = updatedAt,
        ttl = 1_800_000L,
        updatedAt = updatedAt
    )

    private fun localTarget(createdAt: Long) = PriceTargetEntity(
        productId = "jd:1",
        title = "本机版",
        platform = "jd",
        targetPrice = 90.0,
        active = true,
        createdAt = createdAt
    )

    private fun remoteTarget(updatedAt: Long) = TargetDto(
        productId = "jd:1",
        title = "远端版",
        platform = "jd",
        targetPrice = 80.0,
        active = true,
        createdAt = updatedAt,
        updatedAt = updatedAt
    )

    @Test
    fun `favorite remote wins only when strictly newer`() {
        // 相等 = 谁都不算赢 → 保持本机（不写库）
        assertNull(BackupMerge.favorite(localFavorite(cachedAt = 100, lastAccessedAt = 200), remoteFavorite(200)))
        assertNull(BackupMerge.favorite(localFavorite(cachedAt = 100, lastAccessedAt = 200), remoteFavorite(199)))
        val adopted = BackupMerge.favorite(localFavorite(cachedAt = 100, lastAccessedAt = 200), remoteFavorite(201))
        assertEquals("远端版", adopted!!.title)
        assertTrue("收藏条目落库一律 pinned", adopted.pinned)
    }

    @Test
    fun `local favorite with newer access time beats the backup`() {
        // updatedAt 口径 = max(cachedAt, lastAccessedAt)：本机 lastAccessedAt 更新 → 远端旧 → 放弃
        assertNull(BackupMerge.favorite(localFavorite(cachedAt = 100, lastAccessedAt = 999), remoteFavorite(500)))
    }

    @Test
    fun `missing local favorite is adopted directly`() {
        val adopted = BackupMerge.favorite(null, remoteFavorite(1))
        assertEquals("远端版", adopted!!.title)
        assertEquals(90.0, adopted.currentPrice, 0.0001)
    }

    @Test
    fun `target remote wins only when strictly newer`() {
        assertNull(BackupMerge.target(localTarget(createdAt = 500), remoteTarget(500)))
        assertNull(BackupMerge.target(localTarget(createdAt = 500), remoteTarget(499)))
        val adopted = BackupMerge.target(localTarget(createdAt = 500), remoteTarget(501))
        assertEquals("远端版", adopted!!.title)
        assertEquals(80.0, adopted.targetPrice, 0.0001)
        val fresh = BackupMerge.target(null, remoteTarget(1))
        assertEquals("远端版", fresh!!.title)
    }

    @Test
    fun `retention deletes the oldest beyond the keep limit`() {
        val items = (0 until 12).map { i ->
            DavItem(href = "/dav/pricelens/pricelens-20261001-1200%02d.json".format(i), modified = 1_000L + i, size = 1)
        }
        val doomed = BackupRetention.toDelete(items, keep = 10)
        assertEquals(2, doomed.size)
        // 待删清单沿"新→旧"序：最旧的（modified=1000）排在最后
        assertEquals(1_001L, doomed[0].modified)
        assertEquals(1_000L, doomed[1].modified)
        // 最新的那一份永远在保留侧
        assertTrue(BackupRetention.newestFirst(items).take(10).none { it.modified == 1_000L })
    }

    @Test
    fun `retention falls back to name order when server omits modified`() {
        val older = DavItem("/dav/pricelens/pricelens-20261001-010101.json", 0L, 1)
        val newer = DavItem("/dav/pricelens/pricelens-20261002-010101.json", 0L, 1)
        val doomed = BackupRetention.toDelete(listOf(newer, older), keep = 1)
        assertEquals(1, doomed.size)
        assertEquals("modified 缺失时按文件名（内含时间戳）降序保留", older.href, doomed[0].href)
    }
}
