package com.huaxianyan.syncclipboard.ui

import android.app.StatusBarManager
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.huaxianyan.syncclipboard.R
import com.huaxianyan.syncclipboard.data.LastSync
import com.huaxianyan.syncclipboard.data.ServerConfig
import com.huaxianyan.syncclipboard.data.SettingsRepository
import com.huaxianyan.syncclipboard.data.SyncDirection
import com.huaxianyan.syncclipboard.net.SyncClipboardClient
import com.huaxianyan.syncclipboard.tile.DownloadClipboardTileService
import com.huaxianyan.syncclipboard.tile.UploadClipboardTileService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SyncClipboardTheme {
                SyncClipboardApp(::requestTile)
            }
        }
    }

    private fun requestTile(
        service: Class<*>,
        label: String,
        icon: Int,
        onResult: (String) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            onResult("请从快速设置编辑页手动添加磁贴")
            return
        }
        getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(this, service),
            label,
            android.graphics.drawable.Icon.createWithResource(this, icon),
            mainExecutor,
        ) { result ->
            onResult(
                if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED) {
                    "磁贴已添加"
                } else {
                    "未添加磁贴，可从快速设置编辑页手动添加"
                },
            )
        }
    }
}

private enum class AppPage(val title: String) {
    HOME("首页"),
    SETTINGS("设置"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SyncClipboardApp(
    requestTile: (Class<*>, String, Int, (String) -> Unit) -> Unit,
) {
    var currentPage by rememberSaveable { mutableStateOf(AppPage.HOME) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun showMessage(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    Scaffold(
        topBar = { LargeTopAppBar(title = { Text(currentPage.title) }) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentPage == AppPage.HOME,
                    onClick = { currentPage = AppPage.HOME },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("首页") },
                )
                NavigationBarItem(
                    selected = currentPage == AppPage.SETTINGS,
                    onClick = { currentPage = AppPage.SETTINGS },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("设置") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { contentPadding ->
        when (currentPage) {
            AppPage.HOME -> DashboardPage(contentPadding)
            AppPage.SETTINGS -> SettingsPage(contentPadding, requestTile, ::showMessage)
        }
    }
}

private enum class ConnectionStatus {
    NOT_CONFIGURED,
    CHECKING,
    CONNECTED,
    FAILED,
}

@Composable
private fun DashboardPage(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val repository = remember { SettingsRepository(context.applicationContext) }
    val server = remember { repository.loadServer() }
    val lastSync = remember { repository.loadLastSync() }
    var checkRequest by remember { mutableIntStateOf(0) }
    var connectionStatus by remember {
        mutableStateOf(
            if (server == null) ConnectionStatus.NOT_CONFIGURED else ConnectionStatus.CHECKING,
        )
    }

    LaunchedEffect(server, checkRequest) {
        if (server == null) {
            connectionStatus = ConnectionStatus.NOT_CONFIGURED
            return@LaunchedEffect
        }
        connectionStatus = ConnectionStatus.CHECKING
        connectionStatus = runCatching {
            withContext(Dispatchers.IO) { SyncClipboardClient(server).testConnection() }
        }.fold(
            onSuccess = { ConnectionStatus.CONNECTED },
            onFailure = { ConnectionStatus.FAILED },
        )
    }

    PageColumn(contentPadding) {
        ConnectionCard(
            status = connectionStatus,
            server = server,
            onRetry = { checkRequest++ },
        )
        LastSyncCard(lastSync)
        Text(
            text = "更多同步状态将在自动同步功能加入后显示在这里。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun ConnectionCard(
    status: ConnectionStatus,
    server: ServerConfig?,
    onRetry: () -> Unit,
) {
    val statusColor = when (status) {
        ConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.tertiary
        ConnectionStatus.FAILED -> MaterialTheme.colorScheme.error
        ConnectionStatus.CHECKING -> MaterialTheme.colorScheme.primary
        ConnectionStatus.NOT_CONFIGURED -> MaterialTheme.colorScheme.outline
    }
    val title = when (status) {
        ConnectionStatus.CONNECTED -> "连接正常"
        ConnectionStatus.FAILED -> "无法连接服务器"
        ConnectionStatus.CHECKING -> "正在检查连接"
        ConnectionStatus.NOT_CONFIGURED -> "尚未配置服务器"
    }
    val detail = when (status) {
        ConnectionStatus.CONNECTED -> server?.normalizedUrl.orEmpty()
        ConnectionStatus.FAILED -> "请检查网络或服务器设置后重试。"
        ConnectionStatus.CHECKING -> "正在连接服务器……"
        ConnectionStatus.NOT_CONFIGURED -> "请先前往设置填写服务器信息。"
    }

    SectionCard(title = "服务器") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (status == ConnectionStatus.CHECKING) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
            } else {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .background(statusColor, CircleShape),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (server != null && status != ConnectionStatus.CHECKING) {
            OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text("重新检查")
            }
        }
    }
}

@Composable
private fun LastSyncCard(lastSync: LastSync?) {
    SectionCard(title = "上次同步") {
        if (lastSync == null) {
            Text(
                text = "还没有成功同步记录。",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val formattedTime = remember(lastSync.timestampMillis) {
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(lastSync.timestampMillis))
            }
            Text(
                text = when (lastSync.direction) {
                    SyncDirection.UPLOAD -> "上传成功"
                    SyncDirection.DOWNLOAD -> "下载成功"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = formattedTime,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsPage(
    contentPadding: PaddingValues,
    requestTile: (Class<*>, String, Int, (String) -> Unit) -> Unit,
    showMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val repository = remember { SettingsRepository(context.applicationContext) }
    val saved = remember { repository.loadServer() }
    val scope = rememberCoroutineScope()

    var serverUrl by rememberSaveable { mutableStateOf(saved?.url.orEmpty()) }
    var username by rememberSaveable { mutableStateOf(saved?.username.orEmpty()) }
    var password by rememberSaveable { mutableStateOf(saved?.password.orEmpty()) }
    var trustInsecure by rememberSaveable { mutableStateOf(saved?.trustInsecureCertificate ?: false) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var saving by rememberSaveable { mutableStateOf(false) }
    var testing by rememberSaveable { mutableStateOf(false) }

    fun currentConfig() = ServerConfig(
        url = serverUrl,
        username = username,
        password = password,
        trustInsecureCertificate = trustInsecure,
    ).also { it.validate() }

    PageColumn(contentPadding) {
        ServerCard(
            serverUrl = serverUrl,
            onServerUrlChange = { serverUrl = it },
            username = username,
            onUsernameChange = { username = it },
            password = password,
            onPasswordChange = { password = it },
            passwordVisible = passwordVisible,
            onPasswordVisibilityChange = { passwordVisible = !passwordVisible },
            trustInsecure = trustInsecure,
            onTrustInsecureChange = { trustInsecure = it },
            saving = saving,
            testing = testing,
            onSave = {
                val config = runCatching { currentConfig() }.getOrElse {
                    showMessage(it.message ?: "请检查服务器配置")
                    return@ServerCard
                }
                saving = true
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { repository.saveServer(config) }
                    }.onSuccess {
                        showMessage("服务器配置已保存")
                    }.onFailure {
                        showMessage(it.message ?: "保存失败，请检查填写内容")
                    }
                    saving = false
                }
            },
            onTest = {
                val config = runCatching { currentConfig() }.getOrElse {
                    showMessage(it.message ?: "请检查服务器配置")
                    return@ServerCard
                }
                testing = true
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { SyncClipboardClient(config).testConnection() }
                    }.onSuccess {
                        showMessage("连接成功")
                    }.onFailure {
                        showMessage(it.message ?: "连接失败，请检查网络和服务器配置")
                    }
                    testing = false
                }
            },
        )
        TileCard(
            onAddUpload = {
                requestTile(
                    UploadClipboardTileService::class.java,
                    "Kotlin 上传",
                    R.drawable.ic_tile_upload,
                    showMessage,
                )
            },
            onAddDownload = {
                requestTile(
                    DownloadClipboardTileService::class.java,
                    "Kotlin 下载",
                    R.drawable.ic_tile_download,
                    showMessage,
                )
            },
        )
        Text(
            text = "SyncClipboard Kotlin · 0.1.0",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 4.dp, bottom = 24.dp),
        )
    }
}

@Composable
private fun PageColumn(
    contentPadding: PaddingValues,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

@Composable
private fun ServerCard(
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    passwordVisible: Boolean,
    onPasswordVisibilityChange: () -> Unit,
    trustInsecure: Boolean,
    onTrustInsecureChange: (Boolean) -> Unit,
    saving: Boolean,
    testing: Boolean,
    onSave: () -> Unit,
    onTest: () -> Unit,
) {
    SectionCard(title = "服务器") {
        OutlinedTextField(
            value = serverUrl,
            onValueChange = onServerUrlChange,
            label = { Text("服务器地址") },
            placeholder = { Text("https://example.com/") },
            supportingText = { Text("填写 SyncClipboard 服务地址") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            label = { Text("用户名") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("密码") },
            singleLine = true,
            visualTransformation = if (passwordVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                TextButton(onClick = onPasswordVisibilityChange) {
                    Text(if (passwordVisible) "隐藏" else "显示")
                }
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("允许不受信任的 HTTPS 证书", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "仅在你信任的内网中启用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = trustInsecure, onCheckedChange = onTrustInsecureChange)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledTonalButton(
                onClick = onTest,
                enabled = !testing,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                modifier = Modifier.weight(1f),
            ) {
                if (testing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                Text(if (testing) "正在连接" else "测试连接")
            }
            Button(
                onClick = onSave,
                enabled = !saving,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                modifier = Modifier.weight(1f),
            ) {
                if (saving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                Text(if (saving) "正在保存" else "保存配置")
            }
        }
    }
}

@Composable
private fun TileCard(
    onAddUpload: () -> Unit,
    onAddDownload: () -> Unit,
) {
    SectionCard(title = "快速设置磁贴") {
        FilledTonalButton(onClick = onAddUpload, modifier = Modifier.fillMaxWidth()) {
            Text("添加上传磁贴")
        }
        FilledTonalButton(onClick = onAddDownload, modifier = Modifier.fillMaxWidth()) {
            Text("添加下载磁贴")
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            content()
        }
    }
}

@Composable
private fun SyncClipboardTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val dynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !LocalInspectionMode.current
    val colors = when {
        dynamic && dark -> dynamicDarkColorScheme(context)
        dynamic -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
