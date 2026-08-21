package com.neko7ina.syncclipboard.sync

import org.json.JSONObject
import java.io.IOException
import javax.net.ssl.SSLException

enum class ClipboardType(val wireName: String) {
    TEXT("Text"),
    IMAGE("Image"),
    FILE("File"),
    GROUP("Group");

    companion object {
        fun fromWireName(value: String): ClipboardType = entries.firstOrNull {
            it.wireName.equals(value, ignoreCase = true)
        } ?: throw SyncException(
            "服务器返回的内容类型不受支持，请确认其他设备和服务器版本一致",
            UnknownClipboardTypeException(value),
            SyncFailureKind.CONTENT,
        )
    }
}

data class ClipboardPayload(
    val type: ClipboardType,
    val hash: String?,
    val text: String,
    val hasData: Boolean,
    val dataName: String? = null,
    val size: Long? = null,
) {
    fun toJson(): String = JSONObject().apply {
        put("type", type.wireName)
        hash?.takeIf { it.isNotBlank() }?.let { put("hash", it) }
        put("text", text)
        put("hasData", hasData)
        dataName?.let { put("dataName", it) }
        size?.let { put("size", it) }
    }.toString()

    companion object {
        fun fromJson(value: String): ClipboardPayload {
            val json = JSONObject(value)
            return ClipboardPayload(
                type = ClipboardType.fromWireName(json.getString("type")),
                hash = json.optString("hash").takeIf { it.isNotBlank() },
                text = json.optString("text", ""),
                hasData = json.optBoolean("hasData", false),
                dataName = json.optString("dataName").takeIf { it.isNotBlank() },
                size = json.optLong("size", -1L).takeIf { it >= 0L },
            )
        }
    }
}

data class PreparedUpload(
    val payload: ClipboardPayload,
    val fileName: String? = null,
    val bytes: ByteArray? = null,
) {
    val hasFile: Boolean
        get() = !fileName.isNullOrBlank() && bytes != null
}

enum class SyncFailureKind {
    AUTHENTICATION,
    NETWORK,
    TLS,
    SERVER,
    STORAGE,
    CONTENT,
    UNKNOWN,
}

class SyncException(
    message: String,
    cause: Throwable? = null,
    val failureKind: SyncFailureKind = SyncFailureKind.UNKNOWN,
) : Exception(message, cause)

fun Throwable.toSyncFailureKind(): SyncFailureKind {
    val causes = causeChain()
    causes.filterIsInstance<SyncException>()
        .firstOrNull { it.failureKind != SyncFailureKind.UNKNOWN }
        ?.let { return it.failureKind }
    if (causes.any { it is SSLException }) return SyncFailureKind.TLS
    if (causes.any { it is IOException }) return SyncFailureKind.NETWORK
    return SyncFailureKind.UNKNOWN
}

fun Throwable.toSyncUserMessage(fallback: String): String = causeChain()
    .filterIsInstance<SyncException>()
    .mapNotNull { it.message?.takeIf(String::isNotBlank) }
    .firstOrNull()
    ?: fallback

private fun Throwable.causeChain(): List<Throwable> =
    generateSequence(this as Throwable?) { it.cause }.toList()

private class UnknownClipboardTypeException(value: String) :
    Exception("Unsupported clipboard type: $value")
