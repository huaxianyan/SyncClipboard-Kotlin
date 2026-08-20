package com.neko7ina.syncclipboard.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SafDirectoryLabelTest {
    @Test
    fun `用户选择目录后显示可读存储位置`() {
        assertEquals(
            "内部存储/Pictures/SyncClipboard",
            formatExternalStorageTreeDocumentId("primary:Pictures/SyncClipboard"),
        )
        assertEquals(
            "存储设备/SyncClipboard",
            formatExternalStorageTreeDocumentId("1234-5678:SyncClipboard"),
        )
    }
}
