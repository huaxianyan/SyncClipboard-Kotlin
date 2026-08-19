package com.huaxianyan.syncclipboard.sync

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
    fun `long text is transferred through a data file`() {
        val text = "x".repeat(PayloadFactory.TEXT_FILE_THRESHOLD + 1)
        val upload = PayloadFactory.text(text)

        assertTrue(upload.payload.hasData)
        assertEquals(PayloadFactory.TEXT_FILE_THRESHOLD, upload.payload.text.length)
        assertTrue(upload.fileName!!.startsWith("text_"))
        assertEquals(text, upload.bytes!!.toString(Charsets.UTF_8))
    }

    @Test
    fun `image mime type produces image payload`() {
        val upload = PayloadFactory.file("photo.dat", byteArrayOf(1, 2, 3), "image/png")

        assertEquals(ClipboardType.IMAGE, upload.payload.type)
        assertEquals("photo.dat", upload.payload.dataName)
        assertTrue(upload.payload.hasData)
    }
}
