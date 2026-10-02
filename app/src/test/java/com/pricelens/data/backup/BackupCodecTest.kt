package com.pricelens.data.backup

import com.pricelens.data.local.entity.PriceTargetEntity
import com.pricelens.data.local.entity.ProductEntity
import java.time.Instant
import java.time.ZoneOffset
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §五 备份 payload 编解码回归：
 *  - 往返一致（改数据库字段不破坏备份格式的前提是 DTO 独立）；
 *  - 结构是**显式白名单**（顶层与条目层都钉死 key 集合）；
 *  - JSON 注入类标题不能把凭证"变"成真字段；
 *  - 凭证串（模拟 SecretStore 值）绝不出现在产物里；
 *  - schema 不认识 / 别的 App 的 JSON / 坏 JSON → 拒绝并带原因。
 */
class BackupCodecTest {

    private val exportedAt = 1_700_000_000_000L

    private fun favorite(id: String = "jd:100012043978", title: String = "小米移动电源 20000mAh") = ProductEntity(
        id = id,
        title = title,
        currentPrice = 199.0,
        originalPrice = 249.0,
        platform = "jd",
        imageUrl = "https://img.example.com/1.jpg",
        cachedAt = 1_699_999_000_000L,
        lastAccessedAt = 1_699_999_500_000L,
        ttl = 1_800_000L,
        pinned = true
    )

    private fun target(productId: String = "jd:100012043978", title: String = "小米移动电源 20000mAh") = PriceTargetEntity(
        productId = productId,
        title = title,
        platform = "jd",
        targetPrice = 179.0,
        active = true,
        createdAt = 1_699_998_000_000L
    )

    @Test
    fun `encode decode round trips the payload`() {
        val payload = BackupCodec.capture(listOf(favorite()), listOf(target()), exportedAt)
        assertEquals(payload, BackupCodec.decode(BackupCodec.encode(payload)))
    }

    @Test
    fun `encoded payload has exactly the whitelisted keys at every level`() {
        val json = BackupCodec.encode(BackupCodec.capture(listOf(favorite()), listOf(target()), exportedAt))
        val root = JSONObject(json)
        assertEquals(setOf("app", "schema", "exportedAt", "favorites", "targets"), root.keys().asSequence().toSet())
        val favoriteKeys = setOf(
            "id",
            "title",
            "currentPrice",
            "originalPrice",
            "platform",
            "imageUrl",
            "cachedAt",
            "lastAccessedAt",
            "ttl",
            "updatedAt"
        )
        assertEquals(favoriteKeys, root.getJSONArray("favorites").getJSONObject(0).keys().asSequence().toSet())
        val targetKeys = setOf("productId", "title", "platform", "targetPrice", "active", "createdAt", "updatedAt")
        assertEquals(targetKeys, root.getJSONArray("targets").getJSONObject(0).keys().asSequence().toSet())
        assertEquals(BackupFormat.APP, root.getString("app"))
        assertEquals(BackupFormat.SCHEMA, root.getInt("schema"))
    }

    @Test
    fun `title that looks like json injection cannot smuggle a cookie key into payload`() {
        val fakeCookie = "FAKE-MMB-SESSION-c0ffee-DO-NOT-LEAK"
        // 手拼字符串的实现会让标题里的内容"长"成真字段（凭证借道标题混进制品）；
        // 走 JSONObject 的实现只把它当被转义的普通文本 —— 这条用例把这两种行为分开
        val sneaky = favorite(title = "标题\",\"cookie\":\"$fakeCookie")
        val json = BackupCodec.encode(BackupCodec.capture(listOf(sneaky), emptyList(), exportedAt))
        assertFalse("注入串变成了真字段", json.contains("\"cookie\""))
        val fav = JSONObject(json).getJSONArray("favorites").getJSONObject(0)
        assertEquals(10, fav.keys().asSequence().count())
        assertTrue(fav.getString("title").contains(fakeCookie))
    }

    @Test
    fun `encoded json never contains the app credential strings`() {
        // 仓库现实：收藏/盯价目标实体（ProductEntity / PriceTargetEntity）都没有凭证字段，
        // BackupCodec 的签名里也没有 SecretStore 入口（凭证连传入通道都不存在）。
        // 三个假凭证只活在模拟的 SecretStore 值里：无论怎么编码，产物都不得出现它们。
        val fakeCookie = "FAKE-MMB-COOKIE-6f1c-DO-NOT-LEAK"
        val fakeApiKey = "FAKE-XL-APIKEY-9b2e-DO-NOT-LEAK"
        val fakeDavPassword = "FAKE-WEBDAV-PASS-77aa-DO-NOT-LEAK"
        val json = BackupCodec.encode(BackupCodec.capture(listOf(favorite()), listOf(target()), exportedAt))
        assertFalse(json.contains(fakeCookie))
        assertFalse(json.contains(fakeApiKey))
        assertFalse(json.contains(fakeDavPassword))
        // 顶层也没有任何凭证字段的位置（白名单）
        val root = JSONObject(json)
        listOf("cookie", "apikey", "apiKey", "token", "password", "authorization").forEach {
            assertFalse("凭证字段 $it 不得出现在备份 JSON 里", root.has(it))
        }
    }

    @Test
    fun `decode rejects newer unknown schema with a reason`() {
        val e = assertThrows(BackupFormatException::class.java) {
            BackupCodec.decode("""{"app":"PriceLens","schema":2,"exportedAt":1,"favorites":[],"targets":[]}""")
        }
        assertTrue("原因里要带 schema 值", e.message!!.contains("schema=2"))
    }

    @Test
    fun `decode rejects json from another app and malformed text`() {
        val other = assertThrows(BackupFormatException::class.java) {
            BackupCodec.decode("""{"app":"OtherApp","schema":1}""")
        }
        assertTrue(other.message!!.contains("OtherApp"))
        val broken = assertThrows(BackupFormatException::class.java) { BackupCodec.decode("{not json") }
        assertTrue(broken.message!!.contains("JSON"))
    }

    @Test
    fun `decode tolerates missing arrays and skips blank primary keys`() {
        val payload = BackupCodec.decode("""{"app":"PriceLens","schema":1,"exportedAt":7}""")
        assertEquals(7L, payload.exportedAt)
        assertTrue(payload.favorites.isEmpty())
        assertTrue(payload.targets.isEmpty())

        val withBlank = BackupCodec.decode("""{"app":"PriceLens","schema":1,"favorites":[{"id":"","title":"坏条目"}],"targets":[]}""")
        assertTrue("主键为空的条目不能落库，直接跳过", withBlank.favorites.isEmpty())
    }

    @Test
    fun `file names embed sortable timestamps`() {
        val ms = Instant.parse("2026-10-05T07:08:09Z").toEpochMilli()
        assertEquals("pricelens-20261005-070809.json", BackupFormat.fileName(ms, ZoneOffset.UTC))
        assertEquals("pricelens-backup-20261005.json", BackupFormat.safExportFileName(ms, ZoneOffset.UTC))
        assertTrue(BackupFormat.isBackupFileName("pricelens-20261005-070809.json"))
        assertFalse(BackupFormat.isBackupFileName("someone-elses-file.json"))
    }
}
