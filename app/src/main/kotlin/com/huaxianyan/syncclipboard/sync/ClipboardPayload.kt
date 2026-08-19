package com.huaxianyan.syncclipboard.sync

import org.json.JSONObject

enum class ClipboardType(val wireName: String) {
    TEXT("Text"),
    IMAGE("Image"),
    FILE("File"),
    GROUP("Group");

    companion object {
        fun fromWireName(value: String): ClipboardType = entries.firstOrNull {
            it.wireName.equals(value, ignoreCase = true)
        } ?: throw SyncException("服务器返回了未知内容类型：$value")
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

class SyncException(message: String, cause: Throwable? = null) : Exception(message, cause)
