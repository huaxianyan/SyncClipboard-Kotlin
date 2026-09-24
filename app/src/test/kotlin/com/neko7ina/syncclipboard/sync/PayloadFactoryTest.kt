package com.neko7ina.syncclipboard.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PayloadFactoryTest {
    @Test
    fun `short text stays inline and uses compatible hash`() {
        val upload = PayloadFactory.text("  abc  ")

        assertEquals(ClipboardType.TEXT, upload.payload.type)
        assertEquals(
            "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD",
            upload.payload.hash,
        )
        assertEquals("abc", upload.payload.text)
        assertFalse(upload.payload.hasData)
        assertFalse(upload.hasFile)
    }

    @Test
    fun `text hash matches the hash used by uploads`() {
        // 本机剪贴板内容用 textHash 登记，再与远端 payload 的 hash 比对；
        // 两边算法必须完全同源，否则这份登记值永远比不中。
        assertEquals(
            PayloadFactory.text("  abc  ").payload.hash,
            PayloadFactory.textHash("abc"),
        )
        assertEquals(PayloadFactory.textHash("abc"), PayloadFactory.textHash("  abc\n"))
    }

    @Test
    fun `long text is transferred through a data file`() {
        val text = "x".repeat(PayloadFactory.TEXT_FILE_THRESHOLD + 1)
        val upload = PayloadFactory.text(text)

        assertTrue(upload.payload.hasData)
        assertEquals(PayloadFactory.TEXT_FILE_THRESHOLD, upload.payload.text.length)
        assertTrue(upload.fileName!!.startsWith("text_"))
        assertEquals(text, upload.bytes!!.toString(Charsets.UTF_8))
    }

    @Test
    fun `streamed file content hash produces compatible payload hash`() {
        assertEquals(
            "95BCEF73DDD6E7C8D23555943F91FA8600D10DDD1852E0069F5C24ADC91EC318",
            PayloadFactory.fileHash(
                "note.txt",
                "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD",
            ),
        )
    }

    @Test
    fun `image mime type produces image payload`() {
        val upload = PayloadFactory.file("photo.dat", byteArrayOf(1, 2, 3), "image/png")

        assertEquals(ClipboardType.IMAGE, upload.payload.type)
        assertEquals("photo.dat", upload.payload.dataName)
        assertTrue(upload.payload.hasData)
    }
}
