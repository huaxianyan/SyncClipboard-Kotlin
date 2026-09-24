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
import com.neko7ina.syncclipboard.data.AdvancedSyncSettings
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
    private val repository = SettingsRepository(context)

    fun uploadClipboard(): String {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val clip = clipboard.primaryClip ?: throw SyncException(
            "剪贴板中没有可上传的内容，请先复制文本、图片或文件",
        )
        if (clip.itemCount == 0) {
            throw SyncException("剪贴板中没有可上传的内容，请先复制文本、图片或文件")
        }
        return uploadPrepared(readClipItem(clip.getItemAt(0), clip.description.getMimeType(0)))
    }

    fun uploadTextIfChanged(
        text: String,
        previousHash: String?,
        recordHistory: Boolean = true,
    ): String? {
        val prepared = PayloadFactory.text(text)
        if (prepared.payload.hash.equals(previousHash, ignoreCase = true)) return null
        uploadPrepared(prepared, recordHistory)
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
            else -> throw SyncException(
                "没有收到可上传的分享内容，请重新选择一个文本、图片或文件",
            )
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
        repository.recordSuccessfulSync(SyncDirection.DOWNLOAD)
        return result
    }

    fun getRemoteClipboard(): ClipboardPayload = client().getClipboard()

    /** 远端内容的服务器生成时间；服务器不提供时为 null，调用方据此保持原有行为。 */
    fun getRemoteContentTimestamp(payload: ClipboardPayload): Long? =
        runCatching { client().getRemoteContentTimestamp(payload) }.getOrNull()

    /** 服务器时钟相对本机时钟的偏移；无法获取时为 null。 */
    fun getServerTimeOffsetMillis(): Long? = runCatching {
        val serverNow = client().getServerTimeMillis() ?: return@runCatching null
        serverNow - System.currentTimeMillis()
    }.getOrNull()

    /**
     * 把远端内容落到本机。调用方负责先排除「本机已经有了、不必再处理」的情况
     * （见 `SystemBridgeService.remoteContentAlreadyPresent`）——那个判断依赖本机剪贴板
     * 登记值还是上次同步点，语义不同，由调用方按路径决定，不在这里混为一谈。
     */
    fun applyRemoteAutomatically(
        payload: ClipboardPayload,
        settings: AdvancedSyncSettings,
        onText: (text: String, sourceHash: String) -> Unit,
    ): String? {
        val sourceHash = remoteHash(payload)

        return when (payload.type) {
            ClipboardType.TEXT -> if (settings.downloadText) {
                applyRemoteText(payload, onText)
            } else {
                null
            }

            ClipboardType.IMAGE -> if (settings.downloadImage) {
                downloadRemoteFile(payload, settings.imageSaveTreeUri, sourceHash)
            } else {
                null
            }

            ClipboardType.FILE, ClipboardType.GROUP -> if (settings.downloadFile) {
                downloadRemoteFile(payload, settings.fileSaveTreeUri, sourceHash)
            } else {
                null
            }
        }
    }

    fun applyRemoteText(
        payload: ClipboardPayload,
        onText: (text: String, sourceHash: String) -> Unit,
    ): String? {
        val sourceHash = remoteHash(payload)
        if (payload.type != ClipboardType.TEXT) return sourceHash

        applyDownloadedPayload(client(), payload) { text -> onText(text, sourceHash) }
        repository.recordSuccessfulSync(SyncDirection.DOWNLOAD)
        return sourceHash
    }

    fun captureRemoteTextForHistory(payload: ClipboardPayload): String {
        if (payload.type != ClipboardType.TEXT) {
            throw SyncException(
                "服务器返回的内容类型不受支持，请确认其他设备和服务器版本一致",
                failureKind = SyncFailureKind.CONTENT,
            )
        }
        val sourceHash = remoteHash(payload)
        val text = readRemoteText(client(), payload)
        recordTextHistory(
            source = TextSyncHistorySource.REMOTE,
            sourceHash = sourceHash,
            text = text,
            appliedToClipboard = false,
        )
        return sourceHash
    }

    fun remoteHash(payload: ClipboardPayload): String =
        payload.hash ?: PayloadFactory.sha256(payload.toJson().toByteArray())

    private fun downloadRemoteFile(
        payload: ClipboardPayload,
        treeUri: String?,
        sourceHash: String,
    ): String {
        val destination = treeUri ?: throw SyncException(
            "请先选择对应的保存目录",
            failureKind = SyncFailureKind.STORAGE,
        )
        val name = payload.dataName ?: throw SyncException(
            "服务器返回的文件信息不完整，请在发送设备上重新同步后重试",
            failureKind = SyncFailureKind.CONTENT,
        )
        client().readFile(name) { input ->
            SafFileStore(context).writeVerified(
                treeUriValue = destination,
                fileName = name,
                mimeType = guessMimeType(name),
                expectedHash = payload.hash,
                input = input,
            )
        }
        repository.recordSuccessfulSync(SyncDirection.DOWNLOAD)
        return sourceHash
    }

    private fun applyDownloadedPayload(
        client: SyncClipboardClient,
        payload: ClipboardPayload,
        onText: (String) -> Unit,
    ): String = when (payload.type) {
        ClipboardType.TEXT -> {
            val text = readRemoteText(client, payload)
            val sourceHash = remoteHash(payload)
            recordTextHistory(
                source = TextSyncHistorySource.REMOTE,
                sourceHash = sourceHash,
                text = text,
                appliedToClipboard = false,
            )
            onText(text)
            recordTextHistory(
                source = TextSyncHistorySource.REMOTE,
                sourceHash = sourceHash,
                text = text,
                appliedToClipboard = true,
            )
            "下载成功：文本已写入剪贴板"
        }

        ClipboardType.IMAGE, ClipboardType.FILE -> {
            val name = payload.dataName ?: throw SyncException(
                "服务器返回的文件信息不完整，请在发送设备上重新同步后重试",
                failureKind = SyncFailureKind.CONTENT,
            )
            val bytes = client.getFile(name)
            verifyFileHash(payload.hash, name, bytes)
            saveDownload(name, bytes, guessMimeType(name))
            "下载成功：文件已保存到 Download/SyncClipboard/$name"
        }

        ClipboardType.GROUP -> {
            val name = payload.dataName ?: throw SyncException(
                "服务器返回的文件组信息不完整，请在发送设备上重新同步后重试",
                failureKind = SyncFailureKind.CONTENT,
            )
            val bytes = client.getFile(name)
            val folder = "SyncClipboard_${DATE_FORMAT.format(Date())}"
            val count = extractZip(bytes, folder)
            "下载成功：已解压 $count 个文件到 Download/SyncClipboard/$folder"
        }
    }

    private fun uploadPrepared(
        prepared: PreparedUpload,
        recordHistory: Boolean = true,
    ): String {
        val client = client()
        if (prepared.hasFile) {
            client.putFile(prepared.fileName!!, prepared.bytes!!)
        }
        client.putClipboard(prepared.payload)
        repository.recordSuccessfulSync(SyncDirection.UPLOAD)
        if (prepared.payload.type == ClipboardType.TEXT && recordHistory) {
            val text = if (prepared.payload.hasData) {
                prepared.bytes?.toString(Charsets.UTF_8).orEmpty()
            } else {
                prepared.payload.text
            }
            recordTextHistory(
                source = TextSyncHistorySource.LOCAL,
                sourceHash = prepared.payload.hash ?: PayloadFactory.sha256(text.toByteArray()),
                text = text,
                appliedToClipboard = true,
            )
        }
        return when (prepared.payload.type) {
            ClipboardType.TEXT -> "上传成功：剪贴板文本已同步"
            ClipboardType.IMAGE -> "上传成功：剪贴板图片已同步"
            ClipboardType.FILE -> "上传成功：剪贴板文件已同步"
            ClipboardType.GROUP -> "上传成功：文件组已同步"
        }
    }

    private fun readRemoteText(
        client: SyncClipboardClient,
        payload: ClipboardPayload,
    ): String {
        val text = if (payload.hasData) {
            val name = payload.dataName ?: throw SyncException(
                "服务器返回的文本信息不完整，请在发送设备上重新同步后重试",
                failureKind = SyncFailureKind.CONTENT,
            )
            client.getFile(name).toString(Charsets.UTF_8)
        } else {
            payload.text
        }
        verifyTextHash(payload.hash, text)
        return text
    }

    private fun recordTextHistory(
        source: TextSyncHistorySource,
        sourceHash: String,
        text: String,
        appliedToClipboard: Boolean,
    ) {
        val settings = repository.loadAdvancedSyncSettings()
        val serverId = repository.loadServer()?.id ?: return
        TextSyncHistory.record(
            context = context,
            enabled = settings.textHistoryEnabled,
            source = source,
            serverId = serverId,
            hash = sourceHash,
            text = text,
            appliedToClipboard = appliedToClipboard,
        )
    }

    private fun client(): SyncClipboardClient {
        val profiles = repository.loadServerProfilesResult()
        if (profiles.credentialsUnavailable) {
            throw SyncException(
                "服务器凭据无法读取，请在应用设置中重新添加服务器方案",
                failureKind = SyncFailureKind.SERVER,
            )
        }
        val config = profiles.profiles.activeServer ?: throw SyncException(
            "尚未配置服务器，请先在应用设置中添加服务器",
            failureKind = SyncFailureKind.SERVER,
        )
        return SyncClipboardClient(config)
    }

    private fun readClipItem(item: ClipData.Item, mimeType: String?): PreparedUpload {
        item.uri?.let { return readUri(it, mimeType) }
        val text = item.text?.toString() ?: item.htmlText?.toString()
        if (!text.isNullOrBlank()) return PayloadFactory.text(text)
        item.intent?.data?.let { return readUri(it, mimeType) }
        throw SyncException("当前剪贴板内容无法上传，请改用 Android 分享发送")
    }

    private fun readUri(uri: Uri, suppliedMimeType: String?): PreparedUpload {
        val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "clipboard_data.bin"
        val mimeType = suppliedMimeType ?: resolver.getType(uri)
        val bytes = try {
            resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw SyncException("无法读取所选文件，请重新选择后再试")
        } catch (error: SecurityException) {
            throw SyncException("没有权限读取该文件，请重新选择并分享", error)
        } catch (error: SyncException) {
            throw error
        } catch (error: Exception) {
            throw SyncException(
                "无法读取所选文件，请确认文件仍在原位置后重试",
                error,
                SyncFailureKind.STORAGE,
            )
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
        if (!expected.equals(actual, ignoreCase = true)) {
            throw SyncException(
                "收到的文本内容不完整，请在发送设备上重新同步后重试",
                failureKind = SyncFailureKind.CONTENT,
            )
        }
    }

    private fun verifyFileHash(expected: String?, name: String, bytes: ByteArray) {
        if (expected.isNullOrBlank()) return
        val actual = PayloadFactory.file(name, bytes, null).payload.hash
        if (!expected.equals(actual, ignoreCase = true)) {
            throw SyncException(
                "收到的文件内容不完整，请在发送设备上重新同步后重试",
                failureKind = SyncFailureKind.CONTENT,
            )
        }
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
            ?: throw SyncException(
                "无法保存下载内容，请检查存储空间后重试",
                failureKind = SyncFailureKind.STORAGE,
            )
        try {
            resolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                ?: throw SyncException(
                    "无法写入下载内容，请检查存储空间后重试",
                    failureKind = SyncFailureKind.STORAGE,
                )
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            if (error is SyncException) throw error
            throw SyncException(
                "保存下载内容失败，请检查存储空间后重试",
                error,
                SyncFailureKind.STORAGE,
            )
        }
    }

    private fun extractZip(bytes: ByteArray, folder: String): Int = try {
        var count = 0
        var totalBytes = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                if (++count > MAX_ZIP_ENTRIES) {
                    throw SyncException(
                        "压缩包包含过多文件，请减少文件数量后重新发送",
                        failureKind = SyncFailureKind.CONTENT,
                    )
                }
                val normalized = entry.name.replace('\\', '/').trimStart('/')
                if (normalized.split('/').any { it == ".." }) {
                    throw SyncException(
                        "压缩包包含无法安全保存的文件，请检查后重新发送",
                        failureKind = SyncFailureKind.CONTENT,
                    )
                }
                val parts = normalized.split('/').filter(String::isNotBlank)
                if (parts.isEmpty()) continue
                val entryBytes = zip.readBytes()
                totalBytes += entryBytes.size
                if (totalBytes > MAX_EXTRACTED_BYTES) {
                    throw SyncException(
                        "压缩包展开后超出支持范围，请减少内容后重新发送",
                        failureKind = SyncFailureKind.CONTENT,
                    )
                }
                val parent = parts.dropLast(1).joinToString("/") { safePathSegment(it) }
                val relativePath = buildString {
                    append(DOWNLOAD_ROOT).append('/').append(folder)
                    if (parent.isNotEmpty()) append('/').append(parent)
                }
                saveDownloadAt(parts.last(), entryBytes, relativePath)
                zip.closeEntry()
            }
        }
        count
    } catch (error: SyncException) {
        throw error
    } catch (error: Exception) {
        throw SyncException(
            "无法读取收到的压缩包，请在发送设备上重新同步后重试",
            error,
            SyncFailureKind.CONTENT,
        )
    }

    private fun saveDownloadAt(name: String, bytes: ByteArray, relativePath: String) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, safePathSegment(name))
            put(MediaStore.Downloads.MIME_TYPE, guessMimeType(name))
            put(MediaStore.Downloads.RELATIVE_PATH, "$relativePath/")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw SyncException(
                "无法保存解压内容，请检查存储空间后重试",
                failureKind = SyncFailureKind.STORAGE,
            )
        try {
            resolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                ?: throw SyncException(
                    "无法写入解压内容，请检查存储空间后重试",
                    failureKind = SyncFailureKind.STORAGE,
                )
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            if (error is SyncException) throw error
            throw SyncException(
                "保存解压内容失败，请检查存储空间后重试",
                error,
                SyncFailureKind.STORAGE,
            )
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
