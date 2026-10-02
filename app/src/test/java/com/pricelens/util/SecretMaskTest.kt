package com.pricelens.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 凭证掩码（文档 §2.3）。
 *
 * 场景：设置页默认掩码显示，避免"截图发群 = 会话凭证泄露"（2026-10-02 的真事）。
 * 掩码的三个要求：露得少、能核对、太短别装样子。
 */
class SecretMaskTest {

    @Test
    fun `long secrets show only the first and last four`() {
        val cookie = "mmbuser_ext=abcdefghijklmnopqrstuvwxyz0123456789"
        val masked = SecretMask.mask(cookie)
        assertTrue(masked.startsWith("mmbu"))
        assertTrue(masked.endsWith("（共 ${cookie.length} 字符）"))
        assertTrue("中段必须被遮住", masked.contains("……"))
        assertFalse("原串的中段不许出现在掩码里", masked.contains("ijklmnop"))
    }

    @Test
    fun `short secrets are fully hidden instead of half-revealed`() {
        assertEquals("•••", SecretMask.mask("abc"))
        assertEquals("••••••••", SecretMask.mask("12345678"))
        assertFalse(SecretMask.mask("12345678").contains("1"))
    }

    @Test
    fun `blank stays blank`() {
        assertEquals("", SecretMask.mask(""))
        assertEquals("", SecretMask.mask("   ".trim()))
    }

    @Test
    fun `a nine-char secret still hides its middle`() {
        val masked = SecretMask.mask("123456789")
        assertTrue(masked.contains("……"))
        assertFalse("中间那位不能被露出来", masked.contains("5"))
    }
}
