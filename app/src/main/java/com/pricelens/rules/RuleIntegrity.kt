package com.pricelens.rules

import com.pricelens.update.UpdateManifest
import java.security.MessageDigest

/**
 * 规则文件完整性校验（与 update.json / APK 的 sha256 同一套信任模型）。
 *
 * 信任链条：Gitee raw 直链 + HTTPS 做传输信任 → `rules/manifest.json` 是信任根
 * （与 update.json 一样，它自身没有 sha256）→ 每个规则文件按清单里的 sha256 校验，
 * 校验失败一律丢弃并保留旧规则。仓库侧由 `tools/gen_rules_manifest.py` 自动重算，
 * 不给人手写错的机会。
 */
object RuleIntegrity {

    private const val HEX = "0123456789abcdef"

    /** 小写十六进制 sha256（与清单/update.json 的写法一致） */
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val builder = StringBuilder(digest.size * 2)
        for (b in digest) {
            val value = b.toInt()
            builder.append(HEX[(value shr 4) and 0xF])
            builder.append(HEX[value and 0xF])
        }
        return builder.toString()
    }

    /**
     * 校验 bytes 与期望摘要是否一致：[expectedSha256] 先按 update.json 的尺子验形
     * （64 位小写十六进制），形态不对 = 不可校验 = 直接拒绝（绝不"就当你对了"）。
     */
    fun verify(bytes: ByteArray, expectedSha256: String?): Boolean {
        val expected = expectedSha256?.trim()?.lowercase() ?: return false
        if (!UpdateManifest.isSha256Hex(expected)) return false
        return sha256Hex(bytes) == expected
    }
}
