package com.huaxianyan.syncclipboard.tile

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
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
    private lateinit var card: LinearLayout
    private lateinit var title: TextView
    private lateinit var progress: ProgressBar
    private lateinit var message: TextView
    private lateinit var hint: TextView
    private var actionJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
        createContentView(resolveAction(intent))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && actionJob == null) startAction(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (actionJob?.isActive != true) {
            showPreparing(resolveAction(intent))
            startAction(intent)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startAction(intent: Intent) {
        val action = resolveAction(intent)
        val startedAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "Action started: $action")
        title.text = action.title
        message.text = action.runningMessage
        message.setOnClickListener(null)
        hint.visibility = View.GONE
        progress.visibility = View.VISIBLE

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
                Log.i(TAG, "Action succeeded: $action, elapsed=${SystemClock.elapsedRealtime() - startedAt}ms")
                Toast.makeText(this@TileActionActivity, it, Toast.LENGTH_SHORT).show()
                finishAndRemoveTask()
            }.onFailure {
                Log.e(TAG, "Action failed: $action, elapsed=${SystemClock.elapsedRealtime() - startedAt}ms", it)
                title.text = action.failureTitle
                progress.visibility = View.GONE
                message.text = it.message ?: "操作没有完成，请检查网络和服务器配置"
                hint.visibility = View.VISIBLE
                card.setOnClickListener { finishAndRemoveTask() }
            }
        }
    }

    private fun resolveAction(intent: Intent): Action {
        return when (intent.action) {
            Intent.ACTION_SEND -> Action.UPLOAD_SHARED
            ACTION_DOWNLOAD -> Action.DOWNLOAD_CLIPBOARD
            else -> Action.UPLOAD_CLIPBOARD
        }
    }

    private fun createContentView(action: Action) {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(24), dp(28), dp(22))
            background = GradientDrawable().apply {
                setColor(Color.rgb(35, 35, 42))
                setStroke(dp(1), Color.argb(45, 255, 255, 255))
                cornerRadius = dp(22).toFloat()
            }
            elevation = dp(12).toFloat()
        }
        title = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
        }
        progress = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
        }
        message = TextView(this).apply {
            setTextColor(Color.argb(220, 255, 255, 255))
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        }
        hint = TextView(this).apply {
            text = "检查网络和服务器配置后，轻触卡片关闭"
            setTextColor(Color.argb(150, 255, 255, 255))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
            visibility = View.GONE
        }
        card.addView(
            title,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        card.addView(
            progress,
            LinearLayout.LayoutParams(dp(42), dp(42)).apply { topMargin = dp(20) },
        )
        card.addView(
            message,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        card.addView(
            hint,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        root.addView(
            card,
            FrameLayout.LayoutParams(dp(320), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        setContentView(root)
        showPreparing(action)
    }

    private fun showPreparing(action: Action) {
        title.text = action.title
        message.text = action.preparingMessage
        progress.visibility = View.VISIBLE
        hint.visibility = View.GONE
        card.setOnClickListener(null)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private enum class Action(
        val title: String,
        val preparingMessage: String,
        val runningMessage: String,
        val failureTitle: String,
    ) {
        UPLOAD_CLIPBOARD("上传剪贴板", "正在准备上传……", "正在上传剪贴板……", "上传失败"),
        DOWNLOAD_CLIPBOARD("下载剪贴板", "正在准备下载……", "正在下载剪贴板……", "下载失败"),
        UPLOAD_SHARED("上传分享内容", "正在准备上传……", "正在上传分享内容……", "上传失败"),
    }

    companion object {
        private const val TAG = "TileActionActivity"
        const val ACTION_UPLOAD = "com.huaxianyan.syncclipboard.action.UPLOAD"
        const val ACTION_DOWNLOAD = "com.huaxianyan.syncclipboard.action.DOWNLOAD"
    }
}
