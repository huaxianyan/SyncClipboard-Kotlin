package com.huaxianyan.syncclipboard.tile

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.huaxianyan.syncclipboard.sync.ClipboardTransferService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 磁贴与系统分享的轻量入口。
 *
 * Android 10+ 只允许获得焦点的应用读取剪贴板，因此磁贴仍需打开 Activity；这里刻意不使用
 * Compose、Flutter 或任何全局异步初始化，在窗口首次获得焦点后直接执行操作。
 */
class TileActionActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var progress: ProgressBar
    private lateinit var message: TextView
    private var actionJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
        createContentView()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && actionJob == null) startAction(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (actionJob?.isActive != true) startAction(intent)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startAction(intent: Intent) {
        val action = resolveAction(intent)
        message.text = when (action) {
            Action.UPLOAD_CLIPBOARD, Action.UPLOAD_SHARED -> "正在上传……"
            Action.DOWNLOAD_CLIPBOARD -> "正在下载……"
        }
        progress.visibility = ProgressBar.VISIBLE

        actionJob = scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val transfer = ClipboardTransferService(applicationContext)
                    when (action) {
                        Action.UPLOAD_CLIPBOARD -> transfer.uploadClipboard()
                        Action.DOWNLOAD_CLIPBOARD -> transfer.downloadClipboard()
                        Action.UPLOAD_SHARED -> transfer.uploadShared(intent)
                    }
                }
            }
            result.onSuccess {
                Toast.makeText(this@TileActionActivity, it, Toast.LENGTH_SHORT).show()
                finishAndRemoveTask()
            }.onFailure {
                progress.visibility = ProgressBar.GONE
                message.text = it.message ?: "操作失败"
                message.setOnClickListener { finishAndRemoveTask() }
            }
        }
    }

    private fun resolveAction(intent: Intent): Action {
        if (intent.action == Intent.ACTION_SEND) return Action.UPLOAD_SHARED
        return when (intent.getStringExtra(EXTRA_ACTION)) {
            ACTION_DOWNLOAD -> Action.DOWNLOAD_CLIPBOARD
            else -> Action.UPLOAD_CLIPBOARD
        }
    }

    private fun createContentView() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(24), dp(32), dp(24))
            background = GradientDrawable().apply {
                setColor(Color.rgb(38, 38, 38))
                cornerRadius = dp(18).toFloat()
            }
        }
        progress = ProgressBar(this).apply {
            isIndeterminate = true
        }
        message = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }
        card.addView(progress, LinearLayout.LayoutParams(dp(48), dp(48)))
        card.addView(
            message,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        root.addView(
            card,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ).apply { width = dp(320) },
        )
        setContentView(root)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private enum class Action { UPLOAD_CLIPBOARD, DOWNLOAD_CLIPBOARD, UPLOAD_SHARED }

    companion object {
        const val EXTRA_ACTION = "sync_action"
        const val ACTION_UPLOAD = "upload"
        const val ACTION_DOWNLOAD = "download"
    }
}
