package com.pricelens.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「下次检查」估算：本进程没跑过 → 不猜；到点 → 改为"即将进行" */
class WatchCountdownTest {

    @Test
    fun `no round yet means no estimate`() {
        assertNull(WatchCountdown.nextCheckAt(null))
        assertFalse(WatchCountdown.isDue(null, 1_000_000L))
    }

    @Test
    fun `next check is one period after the last round`() {
        val last = 1_000_000L
        assertEquals(last + WatchCountdown.PERIOD_MS, WatchCountdown.nextCheckAt(last)!!)
        assertFalse(WatchCountdown.isDue(WatchCountdown.nextCheckAt(last), last + 1))
        assertTrue(WatchCountdown.isDue(WatchCountdown.nextCheckAt(last), last + WatchCountdown.PERIOD_MS))
    }
}
