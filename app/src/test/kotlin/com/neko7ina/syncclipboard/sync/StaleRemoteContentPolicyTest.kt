package com.neko7ina.syncclipboard.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleRemoteContentPolicyTest {
    private val grace = 5_000L

    private fun staleAge(
        judgedAtMillis: Long = NOW,
        serverClockOffsetMillis: Long = 0L,
        contentCreatedAtMillis: Long,
        graceMillis: Long = grace,
    ) = StaleRemoteContentPolicy.staleAgeMillis(
        judgedAtMillis = judgedAtMillis,
        serverClockOffsetMillis = serverClockOffsetMillis,
        contentCreatedAtMillis = contentCreatedAtMillis,
        graceMillis = graceMillis,
    )

    @Test
    fun `早于判定时刻的内容给出陈旧年龄`() {
        // 对应真机那次异常：连接建立后补查到 81 秒前就已经在云端的内容。
        assertEquals(81_000L, staleAge(contentCreatedAtMillis = NOW - 81_000L))
    }

    @Test
    fun `长期停机后积压的内容同样判陈旧`() {
        // 15:32 产生、15:48 才被补查到，早了将近 16 分钟。
        assertEquals(930_000L, staleAge(contentCreatedAtMillis = NOW - 930_000L))
    }

    @Test
    fun `晚于判定时刻的内容正常接收`() {
        assertNull(staleAge(contentCreatedAtMillis = NOW + 500L))
    }

    @Test
    fun `恰好等于判定时刻的内容不判陈旧`() {
        assertNull(staleAge(contentCreatedAtMillis = NOW))
    }

    @Test
    fun `宽限之内的内容不判陈旧`() {
        assertNull(staleAge(contentCreatedAtMillis = NOW - grace))
    }

    @Test
    fun `超出宽限一毫秒即判陈旧`() {
        assertEquals(grace + 1L, staleAge(contentCreatedAtMillis = NOW - grace - 1L))
    }

    @Test
    fun `零宽限时只有严格更早的内容才判陈旧`() {
        assertNull(staleAge(contentCreatedAtMillis = NOW, graceMillis = 0L))
        assertEquals(1L, staleAge(contentCreatedAtMillis = NOW - 1L, graceMillis = 0L))
    }

    @Test
    fun `判定换算到服务器时钟轴后再比较`() {
        // 服务器时钟比本机快 10 秒：服务器时间轴上的「同期」内容不应判陈旧。
        assertNull(
            staleAge(
                serverClockOffsetMillis = 10_000L,
                contentCreatedAtMillis = NOW + 10_000L,
            ),
        )
        // 服务器时钟比本机慢 10 秒，同理。
        assertNull(
            staleAge(
                serverClockOffsetMillis = -10_000L,
                contentCreatedAtMillis = NOW - 10_000L,
            ),
        )
        // 偏移不影响「确实更早」的结论。
        assertEquals(
            81_000L,
            staleAge(
                serverClockOffsetMillis = 10_000L,
                contentCreatedAtMillis = NOW + 10_000L - 81_000L,
            ),
        )
    }

    /**
     * 记录一个必须靠「只在补查路径使用判据」来规避的固有局限。
     *
     * 服务端只在记录首次创建时写 `createTime`：`AddProfile` 命中已有 hash 时只刷
     * `LastAccessed`/`LastModified`/`Version`，`Update`（PATCH）与 `UpdateEntityFields`
     * 也都不碰它。所以「重新复制一条以前复制过的内容」在时间上看起来和一条积压内容
     * 完全一样——判据本身区分不了，只能靠调用时机区分（推送 = 刚刚，补查 = 可能有积压）。
     */
    @Test
    fun `重新复制一条旧内容在时间上与积压内容无法区分`() {
        val reCopiedNow = staleAge(contentCreatedAtMillis = NOW - 6 * 60 * 60 * 1_000L)
        assertEquals(6 * 60 * 60 * 1_000L, reCopiedNow)
    }

    @Test
    fun `负宽限是非法输入`() {
        val error = runCatching {
            staleAge(contentCreatedAtMillis = NOW, graceMillis = -1L)
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    private companion object {
        /** 任意一个固定时刻，只要各用例相对它的偏移一致即可。 */
        const val NOW = 1_000_000L
    }
}
