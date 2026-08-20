package com.neko7ina.syncclipboard.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns

internal fun resolveSafDirectoryLabel(context: Context, uri: Uri): String {
    val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
    if (uri.authority == EXTERNAL_STORAGE_AUTHORITY && documentId != null) {
        return formatExternalStorageTreeDocumentId(documentId)
    }

    val documentUri = documentId?.let {
        runCatching { DocumentsContract.buildDocumentUriUsingTree(uri, it) }.getOrNull()
    }
    val displayName = documentUri?.let { target ->
        runCatching {
            context.contentResolver.query(
                target,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
        }.getOrNull()
    }
    return displayName?.takeIf(String::isNotBlank) ?: "已选择目录"
}

internal fun formatExternalStorageTreeDocumentId(documentId: String): String {
    val volume = documentId.substringBefore(':')
    val relativePath = documentId.substringAfter(':', "").trim('/')
    val root = if (volume.equals("primary", ignoreCase = true)) "内部存储" else "存储设备"
    return if (relativePath.isEmpty()) root else "$root/$relativePath"
}

private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
