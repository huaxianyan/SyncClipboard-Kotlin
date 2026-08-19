package com.huaxianyan.syncclipboard.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast

abstract class ClipboardTileService : TileService() {
    abstract val tileAction: String
    abstract val requestCode: Int

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            subtitle = if (tileAction == TileActionActivity.ACTION_DOWNLOAD) "点击下载" else "点击上传"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                stateDescription = "就绪"
            }
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        Log.i(TAG, "Tile clicked: service=${javaClass.simpleName}, action=$tileAction")
        val feedback = if (tileAction == TileActionActivity.ACTION_DOWNLOAD) {
            "正在下载剪贴板……"
        } else {
            "正在上传剪贴板……"
        }
        TileFeedback.show(this, feedback)
        val intent = Intent(this, TileActionActivity::class.java).apply {
            action = tileAction
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
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

    private companion object {
        const val TAG = "ClipboardTileService"
    }
}

internal object TileFeedback {
    private var toast: Toast? = null

    fun show(context: Context, message: String) {
        toast?.cancel()
        toast = Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).also { it.show() }
    }

    fun dismiss() {
        toast?.cancel()
        toast = null
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
