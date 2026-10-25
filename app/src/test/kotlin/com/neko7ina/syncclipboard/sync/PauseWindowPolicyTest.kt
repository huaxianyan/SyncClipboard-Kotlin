package com.neko7ina.syncclipboard.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseWindowPolicyTest {
    private val tolerance = 1_000L

    private fun judge(
        syncedAtMillis: Long? = WINDOW_START - 60_000L,
        windowStartMillis: Long? = WINDOW_START,
        serverClockOffsetMillis: Long? = 0L,
        toleranceMillis: Long = tolerance,
    ) = PauseWindowPolicy.judge(
        syncedAtMillis = syncedAtMillis,
        windowStartMillis = windowStartMillis,
        serverClockOffsetMillis = serverClockOffsetMillis,
        toleranceMillis = toleranceMillis,
    )

    private fun skipAge(verdict: PauseWindowPolicy.Verdict): Long {
        assertTrue("expected Skip but was $verdict", verdict is PauseWindowPolicy.Verdict.Skip)
        return (verdict as PauseWindowPolicy.Verdict.Skip).ageMillis
    }

    @Test
    fun `锁屏期间提交的内容判为暂停窗口内的积压`() {
        // 解锁前 10 分钟 PC 上复制的内容。
        assertEquals(10 * 60_000L - tolerance, skipAge(judge(syncedAtMillis = WINDOW_START - 10 * 60_000L)))
    }

    @Test
    fun `恢复之后提交的内容正常写入`() {
        assertEquals(PauseWindowPolicy.Verdict.Fresh, judge(syncedAtMillis = WINDOW_START + 1L))
    }

    @Test
    fun `恰好落在窗口起点上不算暂停期间`() {
        assertEquals(PauseWindowPolicy.Verdict.Fresh, judge(syncedAtMillis = WINDOW_START))
    }

    @Test
    fun `容差之内早于窗口起点仍正常写入`() {
        assertEquals(PauseWindowPolicy.Verdict.Fresh, judge(syncedAtMillis = WINDOW_START - tolerance))
    }

    @Test
    fun `超出容差一毫秒即判为暂停期间`() {
        assertEquals(1L, skipAge(judge(syncedAtMillis = WINDOW_START - tolerance - 1L)))
    }

    @Test
    fun `缺少提交时刻时不作判定`() {
        assertEquals(PauseWindowPolicy.Verdict.Unavailable, judge(syncedAtMillis = null))
    }

    @Test
    fun `缺少窗口起点时不作判定`() {
        assertEquals(PauseWindowPolicy.Verdict.Unavailable, judge(windowStartMillis = null))
    }

    @Test
    fun `时钟偏移未知时不作判定`() {
        assertEquals(PauseWindowPolicy.Verdict.Unavailable, judge(serverClockOffsetMillis = null))
    }

    @Test
    fun `提交时刻换算到本机时钟轴后再比较`() {
        // 服务器时钟比本机快 10 秒：服务器时间轴上的「恢复时刻」要减 10 秒才与本机对齐。
        assertEquals(
            PauseWindowPolicy.Verdict.Fresh,
            judge(
                syncedAtMillis = WINDOW_START + 40_000L,
                serverClockOffsetMillis = 40_000L,
            ),
        )
        assertEquals(
            60_000L,
            skipAge(
                judge(
                    syncedAtMillis = WINDOW_START + 40_000L - tolerance - 60_000L,
                    serverClockOffsetMillis = 40_000L,
                ),
            ),
        )
    }

    @Test
    fun `负容差是非法输入`() {
        val error = runCatching { judge(toleranceMillis = -1L) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    private companion object {
        /** 任意一个固定时刻，只要各用例相对它的偏移一致即可。 */
        const val WINDOW_START = 2_000_000L
    }
}
