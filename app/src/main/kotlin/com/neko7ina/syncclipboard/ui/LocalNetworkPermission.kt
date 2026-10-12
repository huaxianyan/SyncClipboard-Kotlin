package com.neko7ina.syncclipboard.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.neko7ina.syncclipboard.data.ServerConfig
import com.neko7ina.syncclipboard.net.LocalNetworkAccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 仅在用户发起需要局域网的操作时请求，拒绝后仍允许访问公网服务器。 */
@Composable
internal fun rememberLocalNetworkPermissionRequester(
    onGranted: () -> Unit,
): suspend (ServerConfig) -> Boolean {
    val context = LocalContext.current
    val grantedCallback by rememberUpdatedState(onGranted)
    var pending by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    var showRationale by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pending?.complete(granted)
        pending = null
        if (granted) grantedCallback() else showSettings = true
    }
    if (showRationale) {
        AlertDialog(
            onDismissRequest = {
                showRationale = false
                pending?.complete(false)
                pending = null
            },
            title = { Text("允许连接局域网服务器") },
            text = { Text("Android 17 需要「附近设备」权限才能连接局域网中的 SyncClipboard Server。公网同步不需要这项权限。") },
            confirmButton = {
                TextButton(onClick = {
                    showRationale = false
                    launcher.launch(LocalNetworkAccess.PERMISSION)
                }) { Text("继续授权") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRationale = false
                    pending?.complete(false)
                    pending = null
                }) { Text("暂不授权") }
            },
        )
    }
    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("尚未允许访问局域网") },
            text = { Text("局域网同步暂时不可用。你可以在应用权限设置中允许「附近设备」，返回后重新检查连接。公网同步仍可使用。") },
            confirmButton = {
                TextButton(onClick = {
                    showSettings = false
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }) { Text("打开权限设置") }
            },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text("取消") } },
        )
    }
    return { config ->
        if (!withContext(Dispatchers.IO) { LocalNetworkAccess.needsPermission(context, config) }) {
            true
        } else {
            val result = pending ?: CompletableDeferred<Boolean>().also {
                pending = it
                showRationale = true
            }
            result.await()
        }
    }
}
