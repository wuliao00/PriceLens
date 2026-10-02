package com.pricelens.worker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 慢慢买 Cookie 到期提醒的判定（文档 §2.5）。
 *
 * 这条提醒的红线是"别吓人、别重复、别对没配的人说话"：
 *  - 没有 Cookie → 不提"到期"；
 *  - 没有抓取时间记录（老数据）→ 不提醒（宁可不提，也不凭空断言快过期）；
 *  - 同一次抓取只提醒一次；
 *  - 只有真的到了阈值才提醒。
 */
class CookieExpiryNoticeTest {

    private val day = 86_400_000L
    private val now = 1_800_000_000_000L

    @Test
    fun `due only after the threshold`() {
        assertFalse(CookieExpiryNotice.cookieExpiryDue(true, now - 24 * day, 0L, now))
        assertTrue(CookieExpiryNotice.cookieExpiryDue(true, now - 25 * day, 0L, now))
        assertTrue(CookieExpiryNotice.cookieExpiryDue(true, now - 40 * day, 0L, now))
    }

    @Test
    fun `never for people without a cookie or without a timestamp`() {
        assertFalse("没配 Cookie 的人不需要'到期'提醒", CookieExpiryNotice.cookieExpiryDue(false, now - 40 * day, 0L, now))
        assertFalse("老数据没有抓取时刻：不猜", CookieExpiryNotice.cookieExpiryDue(true, 0L, 0L, now))
    }

    @Test
    fun `the same fetch is only announced once`() {
        val fetchedAt = now - 30 * day
        assertTrue(CookieExpiryNotice.cookieExpiryDue(true, fetchedAt, 0L, now))
        assertFalse(
            "已经为这次抓取提醒过就不再打扰",
            CookieExpiryNotice.cookieExpiryDue(true, fetchedAt, fetchedAt, now)
        )
        assertTrue(
            "重新抓取后（fetchedAt 变了）应重新计时",
            CookieExpiryNotice.cookieExpiryDue(true, now - 26 * day, fetchedAt, now)
        )
    }

    @Test
    fun `a clock that went backwards does not fire`() {
        // 用户改了系统时间：age 为负，不该提醒
        assertFalse(CookieExpiryNotice.cookieExpiryDue(true, now + 5 * day, 0L, now))
    }
}
