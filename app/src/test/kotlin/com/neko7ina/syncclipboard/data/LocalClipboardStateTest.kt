package com.neko7ina.syncclipboard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalClipboardStateTest {
    @Test
    fun `hash recorded on the current boot stays usable`() {
        assertEquals(
            "ABC",
            LocalClipboardState.usableHash(
                nowElapsedRealtimeMillis = 5_000_000L,
                recordedElapsedRealtimeMillis = 4_000_000L,
                recordedHash = "ABC",
            ),
        )
    }

    @Test
    fun `hash recorded in an earlier boot is discarded`() {
        // 设备重启后开机计时器归零：记录时刻比当前大，说明中间重启过，
        // 而系统剪贴板当时被清空了，这份记录不再代表剪贴板里有什么。
        assertNull(
            LocalClipboardState.usableHash(
                nowElapsedRealtimeMillis = 30_000L,
                recordedElapsedRealtimeMillis = 9_000_000L,
                recordedHash = "ABC",
            ),
        )
    }

    @Test
    fun `missing records are treated as unknown`() {
        assertNull(
            LocalClipboardState.usableHash(
                nowElapsedRealtimeMillis = 9_000_000L,
                recordedElapsedRealtimeMillis = 0L,
                recordedHash = "ABC",
            ),
        )
        assertNull(
            LocalClipboardState.usableHash(
                nowElapsedRealtimeMillis = 9_000_000L,
                recordedElapsedRealtimeMillis = 1_000L,
                recordedHash = null,
            ),
        )
        assertNull(
            LocalClipboardState.usableHash(
                nowElapsedRealtimeMillis = 9_000_000L,
                recordedElapsedRealtimeMillis = 1_000L,
                recordedHash = "   ",
            ),
        )
    }
}
