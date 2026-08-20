package com.neko7ina.syncclipboard.sync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.neko7ina.syncclipboard.data.SettingsRepository
import com.neko7ina.syncclipboard.data.SyncDirection
import com.neko7ina.syncclipboard.net.SyncClipboardClient
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream

class ClipboardTransferService(private val context: Context) {
    private val resolver = context.contentResolver

    fun uploadClipboard(): String {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val clip = clipboard.primaryClip ?: throw SyncException("剪贴板为空")
        if (clip.itemCount == 0) throw SyncException("剪贴板为空")
        return uploadPrepared(readClipItem(clip.getItemAt(0), clip.description.getMimeType(0)))
    }

    fun uploadTextIfChanged(text: String, previousHash: String?): String? {
        val prepared = PayloadFactory.text(text)
        if (prepared.payload.hash.equals(previousHash, ignoreCase = true)) return null
        uploadPrepared(prepared)
        return prepared.payload.hash
    }

    fun uploadShared(intent: Intent): String {
        val type = intent.type
        val stream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }
        val prepared = when {
            stream != null -> readUri(stream, type)
            intent.getStringExtra(Intent.EXTRA_TEXT) != null ->
                PayloadFactory.text(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
            else -> throw SyncException("分享内容为空或暂不支持")
        }
        return uploadPrepared(prepared)
    }

    fun downloadClipboard(): String {
        val client = client()
        val result = applyDownloadedPayload(client, client.getClipboard()) { text ->
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                ClipData.newPlainText("SyncClipboard", text),
            )
        }
        SettingsRepository(context).recordSuccessfulSync(SyncDirection.DOWNLOAD)
        return result
    }

    fun downloadTextIfChanged(
        previousHash: String?,
        onText: (text: String, sourceHash: String) -> Unit,
    ): String? = applyRemoteTextIfChanged(client().getClipboard(), previousHash, onText)

    fun applyRemoteTextIfChanged(
        payload: ClipboardPayload,
        previousHash: String?,
        onText: (text: String, sourceHash: String) -> Unit,
    ): String? {
        val sourceHash = payload.hash ?: PayloadFactory.sha256(payload.toJson().toByteArray())
        if (sourceHash.equals(previousHash, ignoreCase = true)) return null
        if (payload.type != ClipboardType.TEXT) return sourceHash

        applyDownloadedPayload(client(), payload) { text -> onText(text, sourceHash) }
        SettingsRepository(context).recordSuccessfulSync(SyncDirection.DOWNLOAD)
        return sourceHash
    }

    private fun applyDownloadedPayload(
        client: SyncClipboardClient,
        payload: ClipboardPayload,
        onText: (String) -> Unit,
    ): String = when (payload.type) {
        ClipboardType.TEXT -> {
            val text = if (payload.hasData) {
                val name = payload.dataName ?: throw SyncException("文本数据缺少文件名")
                client.getFile(name).toString(Charsets.UTF_8)
            } else {
                payload.text
            }
            verifyTextHash(payload.hash, text)
            onText(text)
            "下载成功：文本已写入剪贴板"
        }

        ClipboardType.IMAGE, ClipboardType.FILE -> {
            val name = payload.dataName ?: throw SyncException("文件名为空")
            val bytes = client.getFile(name)
            verifyFileHash(payload.hash, name, bytes)
            saveDownload(name, bytes, guessMimeType(name))
            "下载成功：文件已保存到 Download/SyncClipboard/$name"
        }

        ClipboardType.GROUP -> {
            val name = payload.dataName ?: throw SyncException("文件组缺少文件名")
            val bytes = client.getFile(name)
            val folder = "SyncClipboard_${DATE_FORMAT.format(Date())}"
            val count = extractZip(bytes, folder)
            "下载成功：已解压 $count 个文件到 Download/SyncClipboard/$folder"
        }
    }

    private fun uploadPrepared(prepared: PreparedUpload): String {
        val client = client()
        if (prepared.hasFile) {
            client.putFile(prepared.fileName!!, prepared.bytes!!)
        }
        client.putClipboard(prepared.payload)
        SettingsRepository(context).recordSuccessfulSync(SyncDirection.UPLOAD)
        return when (prepared.payload.type) {
            ClipboardType.TEXT -> "上传成功：剪贴板文本已同步"
            ClipboardType.IMAGE -> "上传成功：剪贴板图片已同步"
            ClipboardType.FILE -> "上传成功：剪贴板文件已同步"
            ClipboardType.GROUP -> "上传成功：文件组已同步"
        }
    }

    private fun client(): SyncClipboardClient {
        val config = SettingsRepository(context).loadServer()
            ?: throw SyncException("尚未配置服务器")
        return SyncClipboardClient(config)
    }

    private fun readClipItem(item: ClipData.Item, mimeType: String?): PreparedUpload {
        item.uri?.let { return readUri(it, mimeType) }
        val text = item.text?.toString() ?: item.htmlText?.toString()
        if (!text.isNullOrBlank()) return PayloadFactory.text(text)
        item.intent?.data?.let { return readUri(it, mimeType) }
        throw SyncException("暂不支持当前剪贴板内容")
    }

    private fun readUri(uri: Uri, suppliedMimeType: String?): PreparedUpload {
        val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "clipboard_data.bin"
        val mimeType = suppliedMimeType ?: resolver.getType(uri)
        val bytes = try {
            resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw SyncException("无法读取文件")
        } catch (error: SecurityException) {
            throw SyncException("没有权限读取该文件，请改用系统分享功能", error)
        }
        return PayloadFactory.file(name, bytes, mimeType)
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun verifyTextHash(expected: String?, text: String) {
        if (expected.isNullOrBlank()) return
        val actual = PayloadFactory.sha256(text.toByteArray(Charsets.UTF_8))
        if (!expected.equals(actual, ignoreCase = true)) throw SyncException("文本校验失败，内容可能不完整")
    }

    private fun verifyFileHash(expected: String?, name: String, bytes: ByteArray) {
        if (expected.isNullOrBlank()) return
        val actual = PayloadFactory.file(name, bytes, null).payload.hash
        if (!expected.equals(actual, ignoreCase = true)) throw SyncException("文件校验失败，内容可能已损坏")
    }

    private fun saveDownload(name: String, bytes: ByteArray, mimeType: String) {
        val safeName = safePathSegment(name)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, safeName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, "$DOWNLOAD_ROOT/")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw SyncException("无法创建下载文件")
        try {
            resolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                ?: throw SyncException("无法写入下载文件")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun extractZip(bytes: ByteArray, folder: String): Int {
        var count = 0
        var totalBytes = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                if (++count > MAX_ZIP_ENTRIES) throw SyncException("压缩包文件数量过多")
                val normalized = entry.name.replace('\\', '/').trimStart('/')
                if (normalized.split('/').any { it == ".." }) throw SyncException("压缩包包含不安全路径")
                val parts = normalized.split('/').filter(String::isNotBlank)
                if (parts.isEmpty()) continue
                val entryBytes = zip.readBytes()
                totalBytes += entryBytes.size
                if (totalBytes > MAX_EXTRACTED_BYTES) throw SyncException("压缩包解压后过大")
                val parent = parts.dropLast(1).joinToString("/") { safePathSegment(it) }
                val relativePath = buildString {
                    append(DOWNLOAD_ROOT).append('/').append(folder)
                    if (parent.isNotEmpty()) append('/').append(parent)
                }
                saveDownloadAt(parts.last(), entryBytes, relativePath)
                zip.closeEntry()
            }
        }
        return count
    }

    private fun saveDownloadAt(name: String, bytes: ByteArray, relativePath: String) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, safePathSegment(name))
            put(MediaStore.Downloads.MIME_TYPE, guessMimeType(name))
            put(MediaStore.Downloads.RELATIVE_PATH, "$relativePath/")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw SyncException("无法创建解压文件")
        try {
            resolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                ?: throw SyncException("无法写入解压文件")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun safePathSegment(value: String): String = value
        .replace(Regex("[\\u0000-\\u001F/\\\\:*?\"<>|]"), "_")
        .trim()
        .ifBlank { "unnamed" }

    private fun guessMimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "txt" -> "text/plain"
        "json" -> "application/json"
        "zip" -> "application/zip"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }

    private companion object {
        const val DOWNLOAD_ROOT = "Download/SyncClipboard"
        const val MAX_ZIP_ENTRIES = 10_000
        const val MAX_EXTRACTED_BYTES = 1_073_741_824L
        val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    }
}
