package com.pricelens.update

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仓库根目录 `update.json` 的**线上契约**闸门（与 CI 里那段 python 校验同口径）。
 *
 * 为什么要在本地把 CI 的检查再跑一遍：2026-10-03 发布 2.8.0.1 时，回填清单把
 * `"kind": "apk"` 写成了 `"APK"`。App 侧解析走的是 `equals("page", ignoreCase = true)`
 * （[UpdateManifest] 的 parseTargets），**真机照常工作**，所以真机走查抓不到；
 * 而 `.github/workflows/ci-cd.yml` 的「校验 update.json 自洽性」按字面量收 `apk`/`page`，
 * 于是 main 从发布那一刻起就是红的（`gh run list` 里 06:12 与 06:31 两条 failure 就是它）。
 * 教训一句话：**宽容的解析器会把写错的数据掩盖成"没问题"**，直到某个严格 consumer 才炸。
 *
 * 依赖约定：单测工作目录是 `app/` 模块目录（同 `BuiltinRuleTest`），仓库根用 `../update.json`。
 */
class UpdateManifestFileContractTest {

    private val file = File("../update.json")

    private fun root(): JSONObject {
        assertTrue("找不到仓库根的 update.json（工作目录应当是 app/ 模块目录）", file.isFile)
        return JSONObject(file.readText(Charsets.UTF_8))
    }

    @Test
    fun `apkUrls kind is exactly the lowercase wire vocabulary`() {
        val urls = root().getJSONArray("apkUrls")
        assertTrue("apkUrls 不能为空（客户端要求至少一个下载目标）", urls.length() > 0)
        var apkCount = 0
        for (i in 0 until urls.length()) {
            val target = urls.getJSONObject(i)
            val kind = target.optString("kind")
            assertTrue(
                "apkUrls[$i].kind 必须是字面量 apk / page —— 写成 APK/PAGE 真机不报错（解析器 ignoreCase），" +
                    "但 CI 的严格校验会把 main 判红：实际 \"$kind\"",
                kind == "apk" || kind == "page"
            )
            if (kind == "apk") apkCount++
            assertTrue(
                "apkUrls[$i].url 必须走 https（更新下载不能过明文）：${target.optString("url")}",
                target.optString("url").startsWith("https://")
            )
        }
        assertTrue("至少要有一个 kind=apk 的直链，否则应用内下载退化为打开网页", apkCount > 0)
    }

    @Test
    fun `version gates are internally consistent`() {
        val json = root()
        val code = json.getJSONObject("latest").getInt("versionCode")
        val minSupported = json.getInt("minSupportedVersionCode")
        val forceBelow = json.getInt("forceBelow")
        assertTrue(
            "latest.versionCode=$code 不能低于 minSupportedVersionCode=$minSupported（否则无人能升级）",
            code >= minSupported
        )
        assertTrue(
            "forceBelow=$forceBelow 应 <= minSupportedVersionCode=$minSupported，否则强提示层永远被阻断层吃掉",
            forceBelow <= minSupported
        )
        val percent = json.getInt("rolloutPercent")
        assertTrue("rolloutPercent 必须在 0..100，实际 $percent", percent in 0..100)
        assertEquals("schemaVersion 必须是 1（客户端只认 1）", 1, json.getInt("schemaVersion"))
    }

    @Test
    fun `sha256 is a real digest, never a placeholder`() {
        val sha = root().getString("sha256")
        assertTrue(
            "sha256 必须是 64 位小写十六进制；占位符或大写都等于「没回填」，应用内下载会跳过完整性校验",
            UpdateManifest.isSha256Hex(sha)
        )
    }

    @Test
    fun `the shipping client parser accepts the shipped manifest`() {
        val result = UpdateManifest.parse(root().toString())
        assertTrue("已出厂的解析器必须接受仓库里这份清单：$result", result is ManifestResult.Valid)
        val manifest = (result as ManifestResult.Valid).manifest
        // 清单即优先级：国内第一个目标必须是 Gitee 直链，应用内下载按 firstOrNull(kind==APK) 取
        assertTrue(
            "第一个下载目标必须是 Gitee 直链，实际 ${manifest.apkUrls.first().url}",
            manifest.apkUrls.first().url.startsWith("https://gitee.com/")
        )
        assertEquals(ApkTargetKind.APK, manifest.apkUrls.first().kind)
        assertTrue(
            "versionName「${manifest.latest.versionName}」不像三段以上的版本号（发布回填时写错过格式）",
            Regex("""^\d+\.\d+\.\d+(\.\d+)?$""").matches(manifest.latest.versionName)
        )
        assertTrue("更新说明不能为空：弹窗里会是一片空白", manifest.notes.isNotEmpty())
    }
}
