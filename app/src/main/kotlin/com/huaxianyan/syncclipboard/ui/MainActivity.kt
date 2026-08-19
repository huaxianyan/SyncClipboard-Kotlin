package com.huaxianyan.syncclipboard.ui

import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.huaxianyan.syncclipboard.R
import com.huaxianyan.syncclipboard.data.ServerConfig
import com.huaxianyan.syncclipboard.data.SettingsRepository
import com.huaxianyan.syncclipboard.net.SyncClipboardClient
import com.huaxianyan.syncclipboard.tile.DownloadClipboardTileService
import com.huaxianyan.syncclipboard.tile.UploadClipboardTileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var url: EditText
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var trustInsecure: CheckBox
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)
        setContentView(createContentView())
        loadSettings()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createContentView(): ScrollView {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(32))
        }
        content.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            text = "原生 Kotlin 客户端。磁贴使用轻量 Activity 直接同步，不启动 Flutter Engine。"
            textSize = 15f
            setPadding(0, dp(8), 0, dp(20))
        })

        url = field("服务器地址，例如 https://example.com/webdav/")
        username = field("用户名")
        password = field("密码").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        trustInsecure = CheckBox(this).apply {
            text = "信任不安全的 HTTPS 证书（仅限可信内网）"
        }
        content.addView(url)
        content.addView(username)
        content.addView(password)
        content.addView(trustInsecure)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        buttons.addView(Button(this).apply {
            text = "保存"
            setOnClickListener { saveSettings() }
        }, weightedParams())
        buttons.addView(Button(this).apply {
            text = "测试连接"
            setOnClickListener { testConnection() }
        }, weightedParams())
        content.addView(buttons)

        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(12), 0, dp(20))
        }
        content.addView(status)

        content.addView(TextView(this).apply {
            text = "快速设置磁贴"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            text = "添加“上传剪贴板”和“下载剪贴板”磁贴。文本、图片和单个文件均受支持，也可以从相册或文件管理器分享给本应用上传。"
            textSize = 15f
            setPadding(0, dp(8), 0, dp(12))
        })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            content.addView(Button(this).apply {
                text = "请求添加上传磁贴"
                setOnClickListener { requestTile(UploadClipboardTileService::class.java, "上传剪贴板", R.drawable.ic_tile_upload) }
            })
            content.addView(Button(this).apply {
                text = "请求添加下载磁贴"
                setOnClickListener { requestTile(DownloadClipboardTileService::class.java, "下载剪贴板", R.drawable.ic_tile_download) }
            })
        }

        return ScrollView(this).apply { addView(content) }
    }

    private fun loadSettings() {
        SettingsRepository(this).loadServer()?.let {
            url.setText(it.url)
            username.setText(it.username)
            password.setText(it.password)
            trustInsecure.isChecked = it.trustInsecureCertificate
        }
    }

    private fun currentConfig(): ServerConfig = ServerConfig(
        url = url.text.toString(),
        username = username.text.toString(),
        password = password.text.toString(),
        trustInsecureCertificate = trustInsecure.isChecked,
    ).also { it.validate() }

    private fun saveSettings() {
        runCatching {
            SettingsRepository(this).saveServer(currentConfig())
        }.onSuccess {
            status.text = "配置已保存"
        }.onFailure {
            status.text = it.message ?: "保存失败"
        }
    }

    private fun testConnection() {
        val config = runCatching { currentConfig() }.getOrElse {
            status.text = it.message
            return
        }
        status.text = "正在测试连接……"
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { SyncClipboardClient(config).testConnection() }
            }.onSuccess {
                status.text = "连接成功"
            }.onFailure {
                status.text = it.message ?: "连接失败"
            }
        }
    }

    private fun requestTile(service: Class<*>, label: String, icon: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val manager = getSystemService(StatusBarManager::class.java)
        manager.requestAddTileService(
            ComponentName(this, service),
            label,
            android.graphics.drawable.Icon.createWithResource(this, icon),
            mainExecutor,
        ) { result ->
            Toast.makeText(this, "系统返回：$result", Toast.LENGTH_SHORT).show()
        }
    }

    private fun field(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        textSize = 16f
        isSingleLine = true
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun weightedParams() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
