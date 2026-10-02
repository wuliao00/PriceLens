package com.pricelens.rules

import java.io.File
import org.junit.Assert.assertTrue

/**
 * 规则侧测试夹具读取（沿用 DatabaseMigrationWiringTest 的约定：单测工作目录是 app/ 模块目录，
 * 直接读仓库里的文件；文件不在就必须红，禁止静默跳过 —— 跳过等于没有守卫）。
 */

/** App 内置规则（随 APK 分发，离线兜底的唯一快照点） */
internal val assetJdRuleFile: File get() = File("src/main/assets/rules/jd.json")

/** 仓库侧规则源文件（远端订阅的同源文件，由 tools/gen_rules_manifest.py 同步到 assets） */
internal val repoJdRuleFile: File get() = File("../rules/jd.json")

/** 仓库侧清单（远端信任根，客户端按其中 sha256 校验规则文件） */
internal val repoManifestFile: File get() = File("../rules/manifest.json")

internal fun readRequiredFile(file: File): ByteArray {
    assertTrue(
        "找不到文件 $file —— 单测工作目录应是 app/ 模块目录（Gradle 默认如此）",
        file.exists()
    )
    return file.readBytes()
}

/** assets 里那份内置 JD 规则（校验必需：它解析不了等于离线兜底是空的） */
internal fun loadedBuiltinJdRule(): PlatformRule {
    val raw = readRequiredFile(assetJdRuleFile).toString(Charsets.UTF_8)
    val parsed = RuleJson.parsePlatformRule(raw, expectedId = "jd")
    assertTrue("内置 assets/rules/jd.json 必须能解析：$parsed", parsed is PlatformRuleResult.Valid)
    return (parsed as PlatformRuleResult.Valid).rule
}
