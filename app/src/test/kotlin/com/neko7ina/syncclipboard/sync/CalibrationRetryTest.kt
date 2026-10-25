package com.neko7ina.syncclipboard.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalibrationRetryTest {

    @Test
    fun `首次成功不进入重试`() = runBlocking {
        var attempts = 0
        val offset = retryUntilCalibrated(
            retryDelaysMillis = longArrayOf(1L, 1L),
            attempt = {
                attempts++
                120L
            },
        )
        assertEquals(120L, offset)
        assertEquals(1, attempts)
    }

    @Test
    fun `失败后重试直到成功`() = runBlocking {
        var attempts = 0
        val offset = retryUntilCalibrated(
            retryDelaysMillis = longArrayOf(1L, 1L, 1L),
            attempt = {
                attempts++
                if (attempts < 3) null else 75L
            },
        )
        assertEquals(75L, offset)
        assertEquals(3, attempts)
    }

    @Test
    fun `重试次数用尽后返回 null`() = runBlocking {
        var attempts = 0
        val offset = retryUntilCalibrated(
            retryDelaysMillis = longArrayOf(1L, 1L),
            attempt = {
                attempts++
                null
            },
        )
        assertNull(offset)
        // 首次尝试加两次重试。
        assertEquals(3, attempts)
    }

    @Test
    fun `没有重试间隔时只尝试一次`() = runBlocking {
        var attempts = 0
        val offset = retryUntilCalibrated(
            retryDelaysMillis = LongArray(0),
            attempt = {
                attempts++
                null
            },
        )
        assertNull(offset)
        assertEquals(1, attempts)
    }

    @Test
    fun `条件不再成立时立即停止重试`() = runBlocking {
        var attempts = 0
        var active = true
        val offset = retryUntilCalibrated(
            retryDelaysMillis = longArrayOf(1L, 1L, 1L),
            attempt = {
                attempts++
                active = false
                null
            },
            shouldContinue = { active },
        )
        assertNull(offset)
        assertEquals(1, attempts)
    }
}
