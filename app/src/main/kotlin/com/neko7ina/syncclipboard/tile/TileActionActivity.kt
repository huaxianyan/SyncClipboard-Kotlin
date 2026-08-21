package com.neko7ina.syncclipboard.tile

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
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.neko7ina.syncclipboard.R
import com.neko7ina.syncclipboard.sync.ClipboardTransferService
import com.neko7ina.syncclipboard.sync.toSyncUserMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
    private lateinit var progress: ProgressBar
    private lateinit var title: TextView
    private lateinit var message: TextView
    private var actionJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
        createContentView()
        showRunningState(resolveAction(intent))
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
        val startedAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "Action started: $action")
        showRunningState(action)

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
                progress.visibility = ProgressBar.GONE
                message.text = it
                delay(SUCCESS_VISIBLE_DURATION_MS)
                finishAndRemoveTask()
            }.onFailure {
                Log.e(TAG, "Action failed: $action, elapsed=${SystemClock.elapsedRealtime() - startedAt}ms", it)
                progress.visibility = ProgressBar.GONE
                title.text = "操作失败"
                title.setTextColor(getColor(R.color.md_error))
                val userMessage = it.toSyncUserMessage(
                    "操作未完成，请检查服务器配置和网络后重试",
                )
                message.text = "$userMessage\n\n轻触卡片关闭"
                card.setOnClickListener { finishAndRemoveTask() }
            }
        }
    }

    private fun showRunningState(action: Action) {
        title.text = when (action) {
            Action.UPLOAD_CLIPBOARD -> "上传剪贴板"
            Action.UPLOAD_SHARED -> "上传分享内容"
            Action.DOWNLOAD_CLIPBOARD -> "下载剪贴板"
        }
        title.setTextColor(getColor(R.color.md_on_surface))
        card.setOnClickListener(null)
        message.text = when (action) {
            Action.UPLOAD_CLIPBOARD, Action.UPLOAD_SHARED -> "正在上传内容……"
            Action.DOWNLOAD_CLIPBOARD -> "正在获取最新内容……"
        }
        progress.visibility = ProgressBar.VISIBLE
    }

    private fun resolveAction(intent: Intent): Action {
        return when (intent.action) {
            Intent.ACTION_SEND -> Action.UPLOAD_SHARED
            ACTION_DOWNLOAD -> Action.DOWNLOAD_CLIPBOARD
            else -> Action.UPLOAD_CLIPBOARD
        }
    }

    private fun createContentView() {
        window.setDimAmount(0.32f)
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            elevation = dp(6).toFloat()
            setPadding(dp(28), dp(28), dp(28), dp(24))
            background = GradientDrawable().apply {
                setColor(getColor(R.color.md_surface_container_high))
                cornerRadius = dp(28).toFloat()
            }
        }
        progress = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(getColor(R.color.md_primary))
        }
        title = TextView(this).apply {
            setTextColor(getColor(R.color.md_on_surface))
            textSize = 22f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(20), 0, 0)
        }
        message = TextView(this).apply {
            setTextColor(getColor(R.color.md_on_surface_variant))
            textSize = 14f
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(8), 0, 0)
        }
        card.addView(progress, LinearLayout.LayoutParams(dp(44), dp(44)))
        card.addView(
            title,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        card.addView(
            message,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        root.addView(
            card,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ).apply { width = dp(328) },
        )
        setContentView(root)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private enum class Action { UPLOAD_CLIPBOARD, DOWNLOAD_CLIPBOARD, UPLOAD_SHARED }

    companion object {
        private const val TAG = "TileActionActivity"
        private const val SUCCESS_VISIBLE_DURATION_MS = 700L
        const val ACTION_UPLOAD = "com.neko7ina.syncclipboard.action.UPLOAD"
        const val ACTION_DOWNLOAD = "com.neko7ina.syncclipboard.action.DOWNLOAD"
    }
}
