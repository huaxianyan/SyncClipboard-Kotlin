package com.neko7ina.syncclipboard.sync

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal enum class AutomaticSyncEventKind(val storageCode: String) {
    EXTENSION_CONNECTED("extension_connected"),
    EXTENSION_DISCONNECTED("extension_disconnected"),
    WAITING_FOR_NETWORK("waiting_for_network"),
    REALTIME_CONNECTED("realtime_connected"),
    REALTIME_FAILED("realtime_failed"),
    UPLOAD_SUCCEEDED("upload_succeeded"),
    UPLOAD_FAILED("upload_failed"),
    DOWNLOAD_SUCCEEDED("download_succeeded"),
    DOWNLOAD_FAILED("download_failed");

    companion object {
        fun fromStorageCode(value: String): AutomaticSyncEventKind? =
            entries.firstOrNull { it.storageCode == value }
    }
}

internal data class AutomaticSyncEvent(
    val timestampMillis: Long,
    val kind: AutomaticSyncEventKind,
    val failure: SyncFailureKind? = null,
    val contentType: ClipboardType? = null,
)

internal class AutomaticSyncEventStore(
    private val eventFile: File,
    private val clock: () -> Long,
) {
    constructor(context: Context) : this(
        File(context.filesDir, EVENT_FILE_NAME),
        System::currentTimeMillis,
    )

    fun read(): List<AutomaticSyncEvent> = withFileLock {
        readUnlocked().filter { it.timestampMillis >= clock() - RETENTION_MILLIS }
    }

    fun record(
        kind: AutomaticSyncEventKind,
        failure: SyncFailureKind? = null,
        contentType: ClipboardType? = null,
    ) = withFileLock {
        val now = clock()
        val current = readUnlocked()
            .filter { it.timestampMillis >= now - RETENTION_MILLIS }
            .takeLast(MAX_EVENTS)
        val event = AutomaticSyncEvent(now, kind, failure, contentType)
        if (kind !in REPEATABLE_KINDS && current.lastOrNull()?.sameStateAs(event) == true) {
            return@withFileLock
        }
        writeUnlocked((current + event).takeLast(MAX_EVENTS))
    }

    fun clear() = withFileLock {
        Files.deleteIfExists(eventFile.toPath())
    }

    private fun readUnlocked(): List<AutomaticSyncEvent> {
        if (!eventFile.isFile) return emptyList()
        return eventFile.useLines { lines -> lines.mapNotNull(::decode).toList() }
    }

    private fun writeUnlocked(events: List<AutomaticSyncEvent>) {
        eventFile.parentFile?.mkdirs()
        val temporary = File(eventFile.parentFile, "$EVENT_FILE_NAME.tmp")
        temporary.bufferedWriter().use { writer ->
            events.forEach { event ->
                writer.appendLine(encode(event))
            }
        }
        runCatching {
            Files.move(
                temporary.toPath(),
                eventFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            Files.move(
                temporary.toPath(),
                eventFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun <T> withFileLock(block: () -> T): T = synchronized(PROCESS_LOCK) {
        eventFile.parentFile?.mkdirs()
        val lockFile = File(eventFile.parentFile, "$EVENT_FILE_NAME.lock")
        RandomAccessFile(lockFile, "rw").channel.use { channel ->
            channel.lock().use { block() }
        }
    }

    private fun AutomaticSyncEvent.sameStateAs(other: AutomaticSyncEvent): Boolean =
        kind == other.kind && failure == other.failure && contentType == other.contentType

    private companion object {
        val PROCESS_LOCK = Any()
        const val EVENT_FILE_NAME = "automatic-sync-events-v1"
        const val MAX_EVENTS = 100
        const val RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1_000
        val REPEATABLE_KINDS = setOf(
            AutomaticSyncEventKind.UPLOAD_SUCCEEDED,
            AutomaticSyncEventKind.DOWNLOAD_SUCCEEDED,
        )

        fun encode(event: AutomaticSyncEvent): String = listOf(
            event.timestampMillis.toString(),
            event.kind.storageCode,
            event.failure?.storageCode().orEmpty(),
            event.contentType?.wireName.orEmpty(),
        ).joinToString("|")

        fun decode(line: String): AutomaticSyncEvent? {
            val fields = line.split('|')
            if (fields.size != 4) return null
            return AutomaticSyncEvent(
                timestampMillis = fields[0].toLongOrNull() ?: return null,
                kind = AutomaticSyncEventKind.fromStorageCode(fields[1]) ?: return null,
                failure = failureKindFromStorageCode(fields[2]),
                contentType = fields[3].takeIf(String::isNotEmpty)?.let { value ->
                    ClipboardType.entries.firstOrNull { it.wireName == value }
                },
            )
        }

        fun SyncFailureKind.storageCode(): String = when (this) {
            SyncFailureKind.AUTHENTICATION -> "authentication"
            SyncFailureKind.NETWORK -> "network"
            SyncFailureKind.TLS -> "tls"
            SyncFailureKind.SERVER -> "server"
            SyncFailureKind.STORAGE -> "storage"
            SyncFailureKind.CONTENT -> "content"
            SyncFailureKind.UNKNOWN -> "unknown"
        }

        fun failureKindFromStorageCode(value: String): SyncFailureKind? = when (value) {
            "authentication" -> SyncFailureKind.AUTHENTICATION
            "network" -> SyncFailureKind.NETWORK
            "tls" -> SyncFailureKind.TLS
            "server" -> SyncFailureKind.SERVER
            "storage" -> SyncFailureKind.STORAGE
            "content" -> SyncFailureKind.CONTENT
            "unknown" -> SyncFailureKind.UNKNOWN
            else -> null
        }
    }
}
