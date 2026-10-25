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
    fun `每次重试前回调一次，首次成功时不回调`() = runBlocking {
        val retriedAfter = mutableListOf<Long>()
        var attempts = 0
        val offset = retryUntilCalibrated(
            retryDelaysMillis = longArrayOf(1L, 2L),
            attempt = {
                attempts++
                if (attempts < 3) null else 5L
            },
            onRetry = { retriedAfter += it },
        )
        assertEquals(5L, offset)
        assertEquals(listOf(1L, 2L), retriedAfter)

        retriedAfter.clear()
        attempts = 0
        retryUntilCalibrated(
            retryDelaysMillis = longArrayOf(1L, 2L),
            attempt = {
                attempts++
                9L
            },
            onRetry = { retriedAfter += it },
        )
        assertEquals(emptyList<Long>(), retriedAfter)
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
