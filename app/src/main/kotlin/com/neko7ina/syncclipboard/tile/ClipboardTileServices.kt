package com.neko7ina.syncclipboard.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

abstract class ClipboardTileService : TileService() {
    abstract val tileAction: String
    abstract val requestCode: Int

    override fun onCreate() {
        super.onCreate()
        logLifecycle("onCreate")
    }

    override fun onBind(intent: Intent): IBinder? {
        logLifecycle("onBind started")
        return super.onBind(intent).also { logLifecycle("onBind finished") }
    }

    override fun onStartListening() {
        logLifecycle("onStartListening started")
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            subtitle = if (tileAction == TileActionActivity.ACTION_DOWNLOAD) "点击下载" else "点击上传"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                stateDescription = "就绪"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                activityLaunchForClick = createActionPendingIntent()
            }
            updateTile()
        }
        logLifecycle("onStartListening finished")
    }

    override fun onStopListening() {
        logLifecycle("onStopListening")
        super.onStopListening()
    }

    override fun onDestroy() {
        logLifecycle("onDestroy")
        super.onDestroy()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        Log.i(TAG, "Tile clicked: service=${javaClass.simpleName}, action=$tileAction, elapsed=${SystemClock.elapsedRealtime()}ms")
        super.onClick()
        val intent = createActionIntent()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(createActionPendingIntent())
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun createActionIntent() = Intent(this, TileActionActivity::class.java).apply {
        action = tileAction
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    private fun createActionPendingIntent() = PendingIntent.getActivity(
        this,
        requestCode,
        createActionIntent(),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun logLifecycle(event: String) {
        Log.i(
            TAG,
            "Tile lifecycle: service=${javaClass.simpleName}, event=$event, elapsed=${SystemClock.elapsedRealtime()}ms",
        )
    }

    private companion object {
        const val TAG = "ClipboardTileService"
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
