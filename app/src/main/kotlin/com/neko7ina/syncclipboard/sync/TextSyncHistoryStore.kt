package com.neko7ina.syncclipboard.sync

import android.content.Context
import android.util.Log
import com.neko7ina.syncclipboard.data.AesGcmStringCryptor
import com.neko7ina.syncclipboard.data.AndroidAesKeyStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID

internal enum class TextSyncHistorySource(val storageCode: String) {
    LOCAL("local"),
    REMOTE("remote");

    companion object {
        fun fromStorageCode(value: String): TextSyncHistorySource? =
            entries.firstOrNull { it.storageCode == value }
    }
}

internal data class TextSyncHistoryEntry(
    val id: String,
    val timestampMillis: Long,
    val source: TextSyncHistorySource,
    val serverId: String,
    val hash: String,
    val text: String,
    val appliedToClipboard: Boolean,
)

internal class TextSyncHistoryStore(
    private val historyFile: File,
    private val cryptor: AesGcmStringCryptor,
    private val clock: () -> Long,
) {
    constructor(context: Context) : this(
        historyFile = File(context.filesDir, HISTORY_FILE_NAME),
        cryptor = AesGcmStringCryptor(
            associatedData = ASSOCIATED_DATA,
            keyProvider = { AndroidAesKeyStore.getOrCreate(KEY_ALIAS) },
        ),
        clock = System::currentTimeMillis,
    )

    fun read(): List<TextSyncHistoryEntry> = withFileLock {
        val allEntries = readUnlocked()
        val retained = retainedEntries(allEntries)
        if (retained.size != allEntries.size) writeUnlocked(retained)
        retained
    }

    fun record(
        source: TextSyncHistorySource,
        serverId: String,
        hash: String,
        text: String,
        appliedToClipboard: Boolean,
    ) = withFileLock {
        val current = retainedEntries(readUnlocked()).toMutableList()
        val matchingIndex = current.indexOfLast {
            it.source == source && it.serverId == serverId && it.hash.equals(hash, ignoreCase = true)
        }
        if (matchingIndex == current.lastIndex && matchingIndex >= 0) {
            val existing = current[matchingIndex]
            current[matchingIndex] = existing.copy(
                appliedToClipboard = existing.appliedToClipboard || appliedToClipboard,
            )
        } else {
            current += TextSyncHistoryEntry(
                id = UUID.randomUUID().toString(),
                timestampMillis = clock(),
                source = source,
                serverId = serverId,
                hash = hash,
                text = text,
                appliedToClipboard = appliedToClipboard,
            )
        }
        writeUnlocked(current.takeLast(MAX_ENTRIES))
    }

    fun delete(entryId: String) = withFileLock {
        writeUnlocked(readUnlocked().filterNot { it.id == entryId })
    }

    fun clear() = withFileLock {
        Files.deleteIfExists(historyFile.toPath())
    }

    private fun retainedEntries(entries: List<TextSyncHistoryEntry>): List<TextSyncHistoryEntry> =
        entries
            .filter { it.timestampMillis >= clock() - RETENTION_MILLIS }
            .takeLast(MAX_ENTRIES)

    private fun readUnlocked(): List<TextSyncHistoryEntry> {
        if (!historyFile.isFile) return emptyList()
        return historyFile.useLines { lines ->
            lines.filter(String::isNotBlank).map { decode(cryptor.decrypt(it)) }.toList()
        }
    }

    private fun writeUnlocked(entries: List<TextSyncHistoryEntry>) {
        historyFile.parentFile?.mkdirs()
        val temporary = File(historyFile.parentFile, "$HISTORY_FILE_NAME.tmp")
        val encoded = entries.map { cryptor.encrypt(encode(it)) }.toMutableList()
        while (
            encoded.sumOf { it.toByteArray(Charsets.UTF_8).size + 1 } > MAX_FILE_BYTES &&
            encoded.isNotEmpty()
        ) {
            encoded.removeAt(0)
        }
        temporary.bufferedWriter().use { writer ->
            encoded.forEach(writer::appendLine)
        }
        runCatching {
            Files.move(
                temporary.toPath(),
                historyFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            Files.move(
                temporary.toPath(),
                historyFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun <T> withFileLock(block: () -> T): T = synchronized(PROCESS_LOCK) {
        historyFile.parentFile?.mkdirs()
        val lockFile = File(historyFile.parentFile, "$HISTORY_FILE_NAME.lock")
        RandomAccessFile(lockFile, "rw").channel.use { channel ->
            channel.lock().use { block() }
        }
    }

    private companion object {
        val PROCESS_LOCK = Any()
        const val HISTORY_FILE_NAME = "text-sync-history-v1"
        const val KEY_ALIAS = "syncclipboard_text_sync_history_v1"
        const val MAX_ENTRIES = 100
        const val MAX_FILE_BYTES = 10 * 1024 * 1024
        const val RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1_000
        val ASSOCIATED_DATA = "SyncClipboard text sync history v1".toByteArray(Charsets.UTF_8)

        fun encode(entry: TextSyncHistoryEntry): String {
            val bytes = ByteArrayOutputStream().use { output ->
                DataOutputStream(output).use { data ->
                    data.writeString(entry.id)
                    data.writeLong(entry.timestampMillis)
                    data.writeString(entry.source.storageCode)
                    data.writeString(entry.serverId)
                    data.writeString(entry.hash)
                    data.writeString(entry.text)
                    data.writeBoolean(entry.appliedToClipboard)
                }
                output.toByteArray()
            }
            return Base64.getEncoder().encodeToString(bytes)
        }

        fun decode(value: String): TextSyncHistoryEntry = DataInputStream(
            ByteArrayInputStream(Base64.getDecoder().decode(value)),
        ).use { data ->
            TextSyncHistoryEntry(
                id = data.readString(),
                timestampMillis = data.readLong(),
                source = TextSyncHistorySource.fromStorageCode(data.readString())
                    ?: error("Unsupported text history source"),
                serverId = data.readString(),
                hash = data.readString(),
                text = data.readString(),
                appliedToClipboard = data.readBoolean(),
            )
        }

        fun DataOutputStream.writeString(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            writeInt(bytes.size)
            write(bytes)
        }

        fun DataInputStream.readString(): String {
            val length = readInt()
            require(length >= 0) { "Invalid text history field length" }
            return ByteArray(length).also(::readFully).toString(Charsets.UTF_8)
        }
    }
}

internal object TextSyncHistory {
    private const val TAG = "TextSyncHistory"

    fun record(
        context: Context,
        enabled: Boolean,
        source: TextSyncHistorySource,
        serverId: String,
        hash: String,
        text: String,
        appliedToClipboard: Boolean,
    ) {
        if (!enabled) return
        runCatching {
            TextSyncHistoryStore(context).record(
                source = source,
                serverId = serverId,
                hash = hash,
                text = text,
                appliedToClipboard = appliedToClipboard,
            )
        }.onFailure {
            Log.w(TAG, "Unable to record text sync history", it)
        }
    }
}
