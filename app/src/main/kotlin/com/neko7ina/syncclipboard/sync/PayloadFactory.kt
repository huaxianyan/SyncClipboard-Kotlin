package com.neko7ina.syncclipboard.sync

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

object PayloadFactory {
    const val TEXT_FILE_THRESHOLD = 10_240

    fun text(text: String): PreparedUpload {
        val normalized = text.trim()
        if (normalized.isEmpty()) {
            throw SyncException("剪贴板中没有可上传的文本，请先复制内容")
        }

        val hash = textHash(text)
        if (normalized.length <= TEXT_FILE_THRESHOLD) {
            return PreparedUpload(
                ClipboardPayload(
                    type = ClipboardType.TEXT,
                    hash = hash,
                    text = normalized,
                    hasData = false,
                    size = normalized.length.toLong(),
                ),
            )
        }

        val fileName = "text_$hash.txt"
        val bytes = normalized.toByteArray(StandardCharsets.UTF_8)
        return PreparedUpload(
            payload = ClipboardPayload(
                type = ClipboardType.TEXT,
                hash = hash,
                text = normalized.substring(0, TEXT_FILE_THRESHOLD),
                hasData = true,
                dataName = fileName,
                size = normalized.length.toLong(),
            ),
            fileName = fileName,
            bytes = bytes,
        )
    }

    fun file(fileName: String, bytes: ByteArray, mimeType: String?): PreparedUpload {
        if (bytes.isEmpty()) {
            throw SyncException("所选文件没有内容，请选择其他文件")
        }
        val safeName = safeFileName(fileName)
        val contentHash = sha256(bytes)
        val hash = fileHash(safeName, contentHash)
        val type = if (isImage(safeName, mimeType)) ClipboardType.IMAGE else ClipboardType.FILE
        return PreparedUpload(
            payload = ClipboardPayload(
                type = type,
                hash = hash,
                text = safeName,
                hasData = true,
                dataName = safeName,
                size = bytes.size.toLong(),
            ),
            fileName = safeName,
            bytes = bytes,
        )
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { "%02X".format(it) }

    /**
     * 文本内容的哈希，与 [text] 使用完全相同的归一化方式（trim 后按 UTF-8 取 SHA-256）。
     *
     * 本机剪贴板的内容也用它登记，才能和远端 payload 的哈希直接比对——两边算法必须同源，
     * 否则比对永远不成立。
     */
    fun textHash(text: String): String =
        sha256(text.trim().toByteArray(StandardCharsets.UTF_8))

    fun fileHash(fileName: String, contentHash: String): String = sha256(
        "${safeFileName(fileName)}|$contentHash".toByteArray(StandardCharsets.UTF_8),
    )

    fun safeFileName(fileName: String): String = fileName
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .ifBlank { "clipboard_data.bin" }

    private fun isImage(fileName: String, mimeType: String?): Boolean {
        if (mimeType?.startsWith("image/") == true) return true
        val lower = fileName.lowercase(Locale.ROOT)
        return IMAGE_EXTENSIONS.any(lower::endsWith)
    }

    private val IMAGE_EXTENSIONS = setOf(
        ".jpg", ".jpeg", ".gif", ".bmp", ".png", ".heic", ".heif",
        ".webp", ".avif", ".tiff", ".tif",
    )
}
