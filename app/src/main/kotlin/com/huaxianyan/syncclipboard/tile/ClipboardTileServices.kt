package com.huaxianyan.syncclipboard.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import com.huaxianyan.syncclipboard.sync.ClipboardTransferService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

abstract class ClipboardTileService : TileService() {
    abstract val tileAction: String
    abstract val requestCode: Int

    override fun onStartListening() {
        super.onStartListening()
        showIdleState()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        Log.i(TAG, "Tile clicked: service=${javaClass.simpleName}, action=$tileAction")
        val intent = if (tileAction == TileActionActivity.ACTION_DOWNLOAD) {
            downloadDirectly()
            Intent(this, CollapsePanelActivity::class.java)
        } else {
            Intent(this, TileActionActivity::class.java).apply { action = tileAction }
        }.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        launchActivityAndCollapse(intent)
    }

    private fun downloadDirectly() {
        if (!downloadRunning.compareAndSet(false, true)) return
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            subtitle = "正在下载……"
            updateTile()
        }
        actionScope.launch {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    ClipboardTransferService(applicationContext).downloadClipboard()
                }
            }
            downloadRunning.set(false)
            result.onSuccess { message ->
                Log.i(TAG, "Direct download succeeded, elapsed=${android.os.SystemClock.elapsedRealtime() - startedAt}ms")
                qsTile?.apply {
                    state = Tile.STATE_INACTIVE
                    subtitle = "下载完成"
                    updateTile()
                }
                Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                Log.e(TAG, "Direct download failed, elapsed=${android.os.SystemClock.elapsedRealtime() - startedAt}ms", error)
                qsTile?.apply {
                    state = Tile.STATE_INACTIVE
                    subtitle = "下载失败"
                    updateTile()
                }
                Toast.makeText(
                    applicationContext,
                    error.message ?: "下载失败，请检查网络和服务器配置",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launchActivityAndCollapse(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun showIdleState() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            subtitle = if (tileAction == TileActionActivity.ACTION_DOWNLOAD) "点击下载" else "点击上传"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                stateDescription = "就绪"
            }
            updateTile()
        }
    }

    private companion object {
        const val TAG = "ClipboardTileService"
        val actionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val downloadRunning = AtomicBoolean(false)
    }
}

class UploadClipboardTileService : ClipboardTileService() {
    override val tileAction = TileActionActivity.ACTION_UPLOAD
    override val requestCode = 1
}

class DownloadClipboardTileService : ClipboardTileService() {
    override val tileAction = TileActionActivity.ACTION_DOWNLOAD
    override val requestCode = 2
}
