package com.pricelens.update

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UpdateManifest] 清单解析基线（纯 JVM 单测：test 源集引了 org.json 参考实现）。
 *
 * 关注点全在"宁可当作没有更新，也不能误伤用户"：
 * 坏 JSON / 缺字段 / schema 未知 / 没有下载目标 → 一律 Rejected，由判定层静默处理。
 */
class UpdateManifestTest {

    @Test
    fun `full manifest parses all fields`() {
        val result = UpdateManifest.parse(manifestText())
        assertTrue("应解析成功，实际=${dump(result)}", result is ManifestResult.Valid)
        val m = (result as ManifestResult.Valid).manifest
        assertEquals(1, m.schemaVersion)
        assertEquals("stable", m.channel)
        assertEquals(14, m.latest.versionCode)
        assertEquals("2.6.0", m.latest.versionName)
        assertEquals("2026-09-26", m.latest.releaseDate)
        assertEquals(14, m.minSupportedVersionCode)
        assertEquals(13, m.forceBelow)
        assertEquals(100, m.rolloutPercent)
        assertEquals(SHA, m.sha256)
        assertEquals(18_000_000L, m.sizeBytes)
        assertEquals(listOf("比价更准", "盯价修复"), m.notes)
        assertEquals(24, m.cooldownHours)
        assertEquals(2, m.apkUrls.size)
        assertEquals(ApkTargetKind.APK, m.apkUrls[0].kind)
        assertEquals(ApkTargetKind.PAGE, m.apkUrls[1].kind)
        assertEquals(INSTANT_MS, m.generatedAtMs)
    }

    @Test
    fun `blank and null body are rejected not thrown`() {
        assertEquals(RejectReason.EMPTY_BODY, (UpdateManifest.parse(null) as ManifestResult.Rejected).reason)
        assertEquals(RejectReason.EMPTY_BODY, (UpdateManifest.parse("") as ManifestResult.Rejected).reason)
        assertEquals(RejectReason.EMPTY_BODY, (UpdateManifest.parse("   ") as ManifestResult.Rejected).reason)
    }

    @Test
    fun `broken json is rejected instead of crashing`() {
        val rejected = UpdateManifest.parse("{ this is not json") as ManifestResult.Rejected
        assertEquals(RejectReason.JSON_UNPARSEABLE, rejected.reason)
    }

    @Test
    fun `html error page body is rejected as unparseable`() {
        // Gitee raw 在某些异常路径会返回 HTML；不能因为"200 了"就当清单用
        val rejected = UpdateManifest.parse("<html><body>502 Bad Gateway</body></html>") as ManifestResult.Rejected
        assertEquals(RejectReason.JSON_UNPARSEABLE, rejected.reason)
    }

    @Test
    fun `each required field missing yields missing_fields with the field name`() {
        listOf("schemaVersion", "generatedAt", "minSupportedVersionCode", "latest").forEach { field ->
            val rejected = UpdateManifest.parse(manifestText(field to REMOVE)) as ManifestResult.Rejected
            assertTrue("$field 应报缺字段，实际=${rejected.reason}", rejected.reason.startsWith(RejectReason.MISSING_FIELDS))
            assertTrue(
                "$field 应出现在原因里，实际=${rejected.reason}",
                rejected.reason.contains(field)
            )
        }
    }

    @Test
    fun `latest block missing versionName is rejected`() {
        val latest = "{\"versionCode\":14}"
        val rejected = UpdateManifest.parse(manifestText("latest" to latest)) as ManifestResult.Rejected
        assertTrue(rejected.reason.contains("latest.versionName"))
    }

    @Test
    fun `unknown schema version is rejected so it can never block users`() {
        val rejected = UpdateManifest.parse(manifestText("schemaVersion" to "2")) as ManifestResult.Rejected
        assertEquals(RejectReason.UNKNOWN_SCHEMA, rejected.reason)
    }

    @Test
    fun `empty or absent download targets are rejected`() {
        assertEquals(
            RejectReason.NO_DOWNLOAD_TARGET,
            (UpdateManifest.parse(manifestText("apkUrls" to "[]")) as ManifestResult.Rejected).reason
        )
        assertEquals(
            RejectReason.NO_DOWNLOAD_TARGET,
            (UpdateManifest.parse(manifestText("apkUrls" to REMOVE)) as ManifestResult.Rejected).reason
        )
    }

    @Test
    fun `generatedAt accepts iso date-only and epoch millis and seconds`() {
        val iso = Instant.parse("2026-09-20T00:00:00Z").toEpochMilli()
        val dateOnly = Instant.parse("2026-09-20T00:00:00Z").toEpochMilli()
        assertEquals(INSTANT_MS, iso)
        assertEquals(dateOnly, generatedOf("\"2026-09-20T00:00:00Z\""))
        assertEquals(dateOnly, generatedOf("\"2026-09-20\""))
        assertEquals(dateOnly, generatedOf("$INSTANT_MS"))
        assertEquals(dateOnly, generatedOf("\"$INSTANT_MS\""))
        assertEquals(dateOnly, generatedOf("${INSTANT_MS / 1000}"))
        assertEquals(dateOnly, generatedOf("\"${INSTANT_MS / 1000}\""))
    }

    @Test
    fun `unparsable generatedAt is rejected as missing field`() {
        val rejected = UpdateManifest.parse(manifestText("generatedAt" to "\"not-a-date\"")) as ManifestResult.Rejected
        assertTrue(rejected.reason.contains("generatedAt"))
    }

    @Test
    fun `optional fields fall back to safe defaults`() {
        val m = (
            UpdateManifest.parse(
                manifestText(
                    "forceBelow" to REMOVE,
                    "rolloutPercent" to REMOVE,
                    "cooldownHours" to REMOVE,
                    "sizeBytes" to REMOVE,
                    "notes" to REMOVE,
                    "channel" to REMOVE,
                    "sha256" to REMOVE
                )
            ) as ManifestResult.Valid
            ).manifest
        // forceBelow 缺省= minSupported（不额外制造强提示人群）
        assertEquals(m.minSupportedVersionCode, m.forceBelow)
        assertEquals(100, m.rolloutPercent)
        assertEquals(UpdateManifest.DEFAULT_COOLDOWN_HOURS, m.cooldownHours)
        assertEquals(0L, m.sizeBytes)
        assertTrue(m.notes.isEmpty())
        assertEquals("stable", m.channel)
        assertTrue(m.sha256.isEmpty())
    }

    @Test
    fun `rollout and cooldown are clamped into sane ranges`() {
        val m = (
            UpdateManifest.parse(
                manifestText("rolloutPercent" to "5000", "cooldownHours" to "-10")
            ) as ManifestResult.Valid
            ).manifest
        assertEquals(100, m.rolloutPercent)
        assertEquals(1, m.cooldownHours)
    }

    @Test
    fun `sha256 is normalised to lowercase`() {
        val m = (UpdateManifest.parse(manifestText("sha256" to "\"${SHA.uppercase()}\"")) as ManifestResult.Valid).manifest
        assertEquals(SHA, m.sha256)
    }

    @Test
    fun `isSha256Hex accepts only 64 hex chars`() {
        assertTrue(UpdateManifest.isSha256Hex(SHA))
        assertFalse("占位符不是可信校验值", UpdateManifest.isSha256Hex("TO_FILL_64_HEX_LOWERCASE_SHA256_OF_RELEASE_APK"))
        assertFalse(UpdateManifest.isSha256Hex(""))
        assertFalse(UpdateManifest.isSha256Hex(SHA.drop(1)))
        assertFalse(UpdateManifest.isSha256Hex(SHA.replace('a', 'g')))
        assertFalse(UpdateManifest.isSha256Hex(SHA.uppercase()))
    }

    @Test
    fun `unknown target kind defaults to apk and blank url entries are dropped`() {
        val urls = "[{\"label\":\"x\",\"kind\":\"mirror\",\"url\":\"https://gitee.com/a/b.apk\"}," +
            "{\"label\":\"y\",\"kind\":\"apk\"},{\"url\":\"  \"}]"
        val m = (UpdateManifest.parse(manifestText("apkUrls" to urls)) as ManifestResult.Valid).manifest
        assertEquals(1, m.apkUrls.size)
        assertEquals(ApkTargetKind.APK, m.apkUrls[0].kind)
    }

    private fun generatedOf(generatedAtValue: String): Long =
        (UpdateManifest.parse(manifestText("generatedAt" to generatedAtValue)) as ManifestResult.Valid)
            .manifest.generatedAtMs

    private fun dump(result: ManifestResult): String = when (result) {
        is ManifestResult.Valid -> "valid"
        is ManifestResult.Rejected -> result.reason
    }

    companion object {
        /** 固定时刻：清单新鲜度与解析结果都必须可预测 */
        val INSTANT_MS: Long = Instant.parse("2026-09-20T00:00:00Z").toEpochMilli()

        private const val SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

        /** 用于从字段表中删除某个键（测"缺字段"分支） */
        const val REMOVE = "__REMOVE__"

        private val DEFAULT_TARGETS = "[{\"label\":\"Gitee 发行版\",\"kind\":\"apk\"," +
            "\"url\":\"https://gitee.com/wuliao11541/PriceLens/releases/download/v2.6.0/a.apk\"}," +
            "{\"label\":\"手动下载页\",\"kind\":\"page\",\"url\":\"https://example.com/dl\"}]"

        fun manifestText(vararg overrides: Pair<String, String>): String {
            val base = linkedMapOf(
                "schemaVersion" to "1",
                "generatedAt" to "\"2026-09-20T00:00:00Z\"",
                "channel" to "\"stable\"",
                "latest" to "{\"versionCode\":14,\"versionName\":\"2.6.0\",\"releaseDate\":\"2026-09-26\"}",
                "minSupportedVersionCode" to "14",
                "forceBelow" to "13",
                "rolloutPercent" to "100",
                "apkUrls" to DEFAULT_TARGETS,
                "sha256" to "\"$SHA\"",
                "sizeBytes" to "18000000",
                "notes" to "[\"比价更准\",\"盯价修复\"]",
                "cooldownHours" to "24"
            )
            val merged = LinkedHashMap(base)
            overrides.forEach { (key, value) ->
                if (value == REMOVE) merged.remove(key) else merged[key] = value
            }
            return merged.entries.joinToString(prefix = "{", postfix = "}") { "\"${it.key}\":${it.value}" }
        }
    }
}
