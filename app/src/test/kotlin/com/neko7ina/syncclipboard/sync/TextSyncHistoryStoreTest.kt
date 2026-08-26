package com.neko7ina.syncclipboard.sync

import com.neko7ina.syncclipboard.data.AesGcmStringCryptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class TextSyncHistoryStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `同步文本加密保存并可在重新打开后读取`() {
        val file = temporaryFolder.newFile("history")
        val cryptor = testCryptor()
        val store = TextSyncHistoryStore(file, cryptor) { 1_000L }

        store.record(
            source = TextSyncHistorySource.REMOTE,
            serverId = "server-a",
            hash = "hash-a",
            text = "仅用于测试的同步文本",
            appliedToClipboard = false,
        )

        val stored = file.readText()
        assertFalse(stored.contains("仅用于测试的同步文本"))
        assertEquals(
            listOf("仅用于测试的同步文本"),
            TextSyncHistoryStore(file, cryptor) { 1_000L }.read().map { it.text },
        )
    }

    @Test
    fun `同一条云端文本写入剪贴板后更新原历史`() {
        val file = temporaryFolder.newFile("history")
        val store = TextSyncHistoryStore(file, testCryptor()) { 1_000L }

        store.record(
            TextSyncHistorySource.REMOTE,
            "server-a",
            "hash-a",
            "远端文本",
            appliedToClipboard = false,
        )
        store.record(
            TextSyncHistorySource.REMOTE,
            "server-a",
            "hash-a",
            "远端文本",
            appliedToClipboard = true,
        )

        val entries = store.read()
        assertEquals(1, entries.size)
        assertTrue(entries.single().appliedToClipboard)
    }

    @Test
    fun `文本历史只保留最近七天内的一百条`() {
        val file = temporaryFolder.newFile("history")
        var now = 8L * 24 * 60 * 60 * 1_000
        val store = TextSyncHistoryStore(file, testCryptor()) { now }
        store.record(TextSyncHistorySource.LOCAL, "server-a", "expired", "过期文本", true)

        now += 8L * 24 * 60 * 60 * 1_000
        repeat(101) { index ->
            store.record(
                TextSyncHistorySource.LOCAL,
                "server-a",
                "hash-$index",
                "文本 $index",
                true,
            )
        }

        val entries = store.read()
        assertEquals(100, entries.size)
        assertEquals("文本 1", entries.first().text)
        assertEquals("文本 100", entries.last().text)
    }

    private fun testCryptor(): AesGcmStringCryptor {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return AesGcmStringCryptor("test history".toByteArray()) { key }
    }
}
