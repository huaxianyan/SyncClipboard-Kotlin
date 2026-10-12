package com.neko7ina.syncclipboard.tile

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.net.Uri
import android.view.WindowInsets
import android.widget.ScrollView
import com.neko7ina.syncclipboard.data.SettingsRepository
import com.neko7ina.syncclipboard.net.LocalNetworkAccess
import kotlinx.coroutines.CompletableDeferred
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
import com.neko7ina.syncclipboard.sync.toSyncFailureKind
import com.neko7ina.syncclipboard.sync.SyncFailureKind
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
    private var pendingPermission: CompletableDeferred<Boolean>? = null

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
                if (!ensureLocalNetworkPermission()) throw LocalNetworkAccess.denied()
                // 系统权限窗口关闭后，等当前窗口真正拿回焦点才能读取剪贴板。等不到就继续，
                // 由实际操作给出结果，避免在这里死等。
                var waitedMillis = 0L
                while (!hasWindowFocus() && waitedMillis < FOCUS_WAIT_TIMEOUT_MILLIS) {
                    delay(FOCUS_POLL_INTERVAL_MILLIS)
                    waitedMillis += FOCUS_POLL_INTERVAL_MILLIS
                }
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
                val permissionDenied = it.toSyncFailureKind() == SyncFailureKind.PERMISSION
                message.text = "$userMessage\n\n${if (permissionDenied) "轻触卡片打开权限设置" else "轻触卡片关闭"}"
                card.setOnClickListener {
                    if (permissionDenied) {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    }
                    finishAndRemoveTask()
                }
            }
        }
    }

    private suspend fun ensureLocalNetworkPermission(): Boolean {
        val needed = withContext(Dispatchers.IO) {
            SettingsRepository(applicationContext).loadServer()?.let {
                LocalNetworkAccess.needsPermission(this@TileActionActivity, it)
            } ?: false
        }
        if (!needed) return true
        val result = CompletableDeferred<Boolean>()
        pendingPermission = result
        AlertDialog.Builder(this)
            .setTitle("允许连接局域网服务器")
            .setMessage("Android 17 需要「附近设备」权限才能访问局域网中的 SyncClipboard Server。")
            .setPositiveButton("继续授权") { _, _ ->
                requestPermissions(arrayOf(LocalNetworkAccess.PERMISSION), LOCAL_NETWORK_REQUEST)
            }
            .setNegativeButton("取消") { _, _ -> result.complete(false) }
            .setOnCancelListener { result.complete(false) }
            .show()
        return result.await().also { pendingPermission = null }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCAL_NETWORK_REQUEST) {
            pendingPermission?.complete(grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
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
        val scroll = ScrollView(this).apply {
            addView(card, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, FrameLayout.LayoutParams(dp(328), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.setOnApplyWindowInsetsListener { view, insets ->
            val left: Int
            val top: Int
            val right: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                left = safe.left; top = safe.top; right = safe.right; bottom = safe.bottom
            } else {
                @Suppress("DEPRECATION")
                val safe = android.graphics.Rect(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                left = safe.left; top = safe.top; right = safe.right; bottom = safe.bottom
            }
            view.setPadding(dp(24) + left, dp(24) + top, dp(24) + right, dp(24) + bottom)
            insets
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val width = minOf(dp(328), (root.width - root.paddingLeft - root.paddingRight).coerceAtLeast(1))
            val height = (root.height - root.paddingTop - root.paddingBottom).coerceAtLeast(1)
            val targetHeight = if (card.measuredHeight > height) height else ViewGroup.LayoutParams.WRAP_CONTENT
            if (scroll.layoutParams.width != width || scroll.layoutParams.height != targetHeight) {
                scroll.layoutParams = FrameLayout.LayoutParams(width, targetHeight, Gravity.CENTER)
            }
        }
        setContentView(root)
        root.requestApplyInsets()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private enum class Action { UPLOAD_CLIPBOARD, DOWNLOAD_CLIPBOARD, UPLOAD_SHARED }

    companion object {
        private const val TAG = "TileActionActivity"
        private const val SUCCESS_VISIBLE_DURATION_MS = 700L
        private const val LOCAL_NETWORK_REQUEST = 1
        private const val FOCUS_POLL_INTERVAL_MILLIS = 50L
        private const val FOCUS_WAIT_TIMEOUT_MILLIS = 1_500L
        const val ACTION_UPLOAD = "com.neko7ina.syncclipboard.action.UPLOAD"
        const val ACTION_DOWNLOAD = "com.neko7ina.syncclipboard.action.DOWNLOAD"
    }
}
