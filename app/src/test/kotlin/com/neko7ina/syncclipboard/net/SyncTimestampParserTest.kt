package com.neko7ina.syncclipboard.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncTimestampParserTest {

    private fun assertParsed(expected: Long, raw: String?) {
        assertEquals(expected, SyncTimestampParser.parse(raw) ?: -1L)
    }

    /**
     * `/api/time` 的真实响应（服务器 2026-09-16T12:00:39Z，实测值）。
     * 注意格式：7 位小数秒 + 显式 `+00:00` 偏移。
     */
    @Test
    fun `解析服务器时间接口返回的带偏移时间串`() {
        assertParsed(1789560039425L, "\"2026-09-16T12:00:39.4257175+00:00\"")
        assertParsed(1789560039425L, "2026-09-16T12:00:39.4257175+00:00")
    }

    /**
     * `GET /api/history/Text-{hash}` 的真实响应（实测值），时间字段与正文混在同一个对象里。
     */
    @Test
    fun `解析历史记录响应里的 createTime`() {
        val body = """
            {"hash":"EAF7F79F4B2BC857CA01E4339873C5821226E0F55D2B6D2AA8B907378257A0E8",
             "text":"19:25:36  Local ADB server ready.",
             "type":"Text",
             "createTime":"2026-09-16T11:26:06.6904181+00:00",
             "lastModified":"2026-09-16T11:26:07.1539943+00:00",
             "lastAccessed":"2026-09-16T11:26:07.1539944+00:00",
             "starred":false,"pinned":false,"size":238,"hasData":false,"version":1,"isDeleted":false}
        """.trimIndent()
        assertParsed(1789557966690L, body)
    }

    @Test
    fun `同一时刻的 Z 结尾写法解析结果一致`() {
        assertParsed(1789557966690L, "2026-09-16T11:26:06.6904181Z")
        assertParsed(1789557966690L, "2026-09-16T19:26:06.6904181+08:00")
        assertParsed(1789557966000L, "2026-09-16T11:26:06+00:00")
    }

    @Test
    fun `接受 epoch 毫秒的数字形式`() {
        assertParsed(1789557966690L, "1789557966690")
        assertParsed(1789557966690L, "\"1789557966690\"")
        assertParsed(1789557966690L, """{"createTime":1789557966690}""")
    }

    @Test
    fun `字段名大写时同样识别`() {
        assertParsed(1789557966690L, """{"CreateTime":"2026-09-16T11:26:06.6904181+00:00"}""")
    }

    @Test
    fun `只有 lastModified 时退而取之`() {
        assertParsed(1789557966690L, """{"lastModified":"2026-09-16T11:26:06.6904181+00:00"}""")
    }

    /**
     * 剪贴板正文本身可能是一段 JSON。正文里的引号在响应中必然被转义
     * （`\"createTime\":\"`），不应被误认为外层记录的时间字段。
     */
    @Test
    fun `正文中出现的伪时间字段不会覆盖真实字段`() {
        val body = """{"hash":"X","text":"{\"createTime\":\"2000-01-01T00:00:00Z\"}",""" +
            """"type":"Text","createTime":"2026-09-16T11:26:06.6904181+00:00"}"""
        assertParsed(1789557966690L, body)
    }

    /**
     * `GET /SyncClipboard.json` 自 `v3.3.2` 起带 `syncedAt`，取自自建服务器上的真实值。
     */
    @Test
    fun `从剪贴板 JSON 提取 syncedAt`() {
        val body = """{"type":"Text","hash":"ABC","text":"hello","hasData":false,""" +
            """"syncedAt":"2026-10-25T09:21:42.3251447+00:00"}"""
        assertEquals(1792920102325L, SyncTimestampParser.parseSyncedAt(body) ?: -1L)
    }

    @Test
    fun `syncedAt 支持大写字段名与数字形式`() {
        assertEquals(
            1792920102325L,
            SyncTimestampParser.parseSyncedAt("""{"SyncedAt":"2026-10-25T09:21:42.3251447+00:00"}""") ?: -1L,
        )
        assertEquals(
            1792920102325L,
            SyncTimestampParser.parseSyncedAt("""{"syncedAt":1792920102325}""") ?: -1L,
        )
    }

    /**
     * 正文里出现的伪字段在 JSON 中是转义过的（`\"syncedAt\":`），`:` 后面紧跟反斜杠，
     * 匹配不到要求纯引号的模式。
     */
    @Test
    fun `正文里的伪 syncedAt 不会被当成内容时间`() {
        val body = """{"type":"Text","text":"{\"syncedAt\":\"2000-01-01T00:00:00Z\"}",""" +
            """"hasData":false}"""
        assertNull(SyncTimestampParser.parseSyncedAt(body))
    }

    @Test
    fun `没有 syncedAt 时返回 null`() {
        assertNull(SyncTimestampParser.parseSyncedAt(null))
        assertNull(SyncTimestampParser.parseSyncedAt(""))
        assertNull(SyncTimestampParser.parseSyncedAt("""{"type":"Text","hash":"X"}"""))
        assertNull(SyncTimestampParser.parseSyncedAt("""{"syncedAt":"not a time"}"""))
    }

    @Test
    fun `无法识别的输入返回 null`() {
        assertNull(SyncTimestampParser.parse(null))
        assertNull(SyncTimestampParser.parse(""))
        assertNull(SyncTimestampParser.parse("   "))
        assertNull(SyncTimestampParser.parse("not a timestamp"))
        assertNull(SyncTimestampParser.parse("""{"hash":"X","type":"Text"}"""))
        assertNull(SyncTimestampParser.parse("""{"createTime":"0000-00-00 00:00"}"""))
    }
}
