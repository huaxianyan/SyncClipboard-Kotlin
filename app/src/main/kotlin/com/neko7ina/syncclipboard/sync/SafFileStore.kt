package com.neko7ina.syncclipboard.sync

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest

class SafFileStore(private val context: Context) {
    private val resolver = context.contentResolver

    fun writeVerified(
        treeUriValue: String,
        fileName: String,
        mimeType: String,
        expectedHash: String?,
        input: InputStream,
    ) {
        val treeUri = Uri.parse(treeUriValue)
        val parentUri = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
        }.getOrElse {
            throw SyncException(
                "保存目录已失效，请重新选择",
                it,
                SyncFailureKind.STORAGE,
            )
        }
        val safeName = PayloadFactory.safeFileName(fileName)
        val targetUri = runCatching {
            DocumentsContract.createDocument(resolver, parentUri, mimeType, safeName)
        }.getOrNull() ?: throw SyncException(
            "无法在保存目录中创建文件，请重新选择目录",
            failureKind = SyncFailureKind.STORAGE,
        )

        try {
            val digest = MessageDigest.getInstance("SHA-256")
            resolver.openOutputStream(targetUri, "w")?.use { output ->
                DigestInputStream(input, digest).use { source -> source.copyTo(output) }
            } ?: throw SyncException(
                "无法写入保存目录，请重新选择目录",
                failureKind = SyncFailureKind.STORAGE,
            )

            if (!expectedHash.isNullOrBlank()) {
                val contentHash = digest.digest().joinToString("") { "%02X".format(it) }
                val actualHash = PayloadFactory.fileHash(safeName, contentHash)
                if (!expectedHash.equals(actualHash, ignoreCase = true)) {
                    throw SyncException(
                        "文件校验失败，请稍后重试",
                        failureKind = SyncFailureKind.CONTENT,
                    )
                }
            }
        } catch (error: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, targetUri) }
            if (error is SyncException) throw error
            throw SyncException(
                "写入保存目录失败，请检查目录授权和可用空间",
                error,
                SyncFailureKind.STORAGE,
            )
        }
    }
}
