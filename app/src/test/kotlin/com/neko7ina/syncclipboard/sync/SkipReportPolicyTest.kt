package com.neko7ina.syncclipboard.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipReportPolicyTest {

    @Test
    fun `从未记录过时照常记录`() {
        assertTrue(SkipReportPolicy.shouldReport("ABC123", null))
    }

    @Test
    fun `同一条内容不重复记录`() {
        assertFalse(SkipReportPolicy.shouldReport("ABC123", "ABC123"))
    }

    @Test
    fun `哈希大小写不一致仍视为同一条`() {
        assertFalse(SkipReportPolicy.shouldReport("abc123", "ABC123"))
        assertFalse(SkipReportPolicy.shouldReport("ABC123", "abc123"))
    }

    @Test
    fun `换成另一份内容时重新记录`() {
        assertTrue(SkipReportPolicy.shouldReport("DEF456", "ABC123"))
    }
}
