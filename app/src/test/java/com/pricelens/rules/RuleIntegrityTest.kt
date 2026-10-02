package com.pricelens.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * sha256 完整性闸门（"校验失败必须拒绝"）：与 update.json / APK 同一把尺子。
 *
 * 拒绝方向是唯一重点：宁可用旧规则，也不能把校验不过的内容装上（规则会驱动浮窗内容，
 * 被投毒的选择器可能把别的商品的价格显示给用户，见防错配硬规则）。
 */
class RuleIntegrityTest {

    @Test
    fun `sha256 hex matches the published test vectors`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            RuleIntegrity.sha256Hex("abc".toByteArray())
        )
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            RuleIntegrity.sha256Hex(ByteArray(0))
        )
    }

    @Test
    fun `verify accepts exact match and rejects any tampering`() {
        val bytes = """{"id":"jd"}""".toByteArray()
        val digest = RuleIntegrity.sha256Hex(bytes)
        assertTrue(RuleIntegrity.verify(bytes, digest))
        // 单字节篡改必须拒绝
        val tampered = """{"id":"jx"}""".toByteArray()
        assertFalse(RuleIntegrity.verify(tampered, digest))
    }

    @Test
    fun `verify normalizes case and whitespace of the expected digest`() {
        val bytes = "rule".toByteArray()
        val digest = RuleIntegrity.sha256Hex(bytes)
        assertTrue(RuleIntegrity.verify(bytes, digest.uppercase()))
        assertTrue(RuleIntegrity.verify(bytes, "  $digest  "))
    }

    @Test
    fun `verify rejects unverifiable digests instead of guessing`() {
        val bytes = "rule".toByteArray()
        assertFalse("缺失摘要不可校验", RuleIntegrity.verify(bytes, null))
        assertFalse("占位符不可校验", RuleIntegrity.verify(bytes, "TODO"))
        assertFalse("长度不足不可校验", RuleIntegrity.verify(bytes, "a".repeat(63)))
        assertFalse("非十六进制不可校验", RuleIntegrity.verify(bytes, "z".repeat(64)))
        assertFalse("空串不可校验", RuleIntegrity.verify(bytes, ""))
    }
}
