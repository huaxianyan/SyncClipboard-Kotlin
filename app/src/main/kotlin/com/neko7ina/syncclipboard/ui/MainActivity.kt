package com.neko7ina.syncclipboard.ui

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.neko7ina.syncclipboard.BuildConfig
import com.neko7ina.syncclipboard.R
import com.neko7ina.syncclipboard.bridge.BridgeContract
import com.neko7ina.syncclipboard.data.AdvancedSyncSettings
import com.neko7ina.syncclipboard.data.LastSync
import com.neko7ina.syncclipboard.data.ServerConfig
import com.neko7ina.syncclipboard.data.ServerProfiles
import com.neko7ina.syncclipboard.data.SettingsRepository
import com.neko7ina.syncclipboard.data.SyncDirection
import com.neko7ina.syncclipboard.extension.AutomaticSyncRuntimeState
import com.neko7ina.syncclipboard.extension.SystemExtensionController
import com.neko7ina.syncclipboard.extension.SystemExtensionState
import com.neko7ina.syncclipboard.extension.SystemExtensionStatus
import com.neko7ina.syncclipboard.net.SyncClipboardClient
import com.neko7ina.syncclipboard.sync.SyncFailureKind
import com.neko7ina.syncclipboard.sync.toSyncFailureKind
import com.neko7ina.syncclipboard.sync.toSyncUserMessage
import com.neko7ina.syncclipboard.tile.DownloadClipboardTileService
import com.neko7ina.syncclipboard.tile.UploadClipboardTileService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.UUID

private const val PROJECT_URL = "https://github.com/huaxianyan/SyncClipboard-Kotlin"

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0061A4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1E4FF),
    onPrimaryContainer = Color(0xFF001D36),
    secondary = Color(0xFF535F70),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7E3F7),
    onSecondaryContainer = Color(0xFF101C2B),
    tertiary = Color(0xFF2E7D32),
    onTertiary = Color.White,
    background = Color(0xFFF8F9FF),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF8F9FF),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFDFE2EB),
    onSurfaceVariant = Color(0xFF43474E),
    outline = Color(0xFF73777F),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF9ECAFF),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF00497D),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFFBBC7DB),
    onSecondary = Color(0xFF253140),
    secondaryContainer = Color(0xFF3B4858),
    onSecondaryContainer = Color(0xFFD7E3F7),
    tertiary = Color(0xFF81C784),
    onTertiary = Color(0xFF003909),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C6CF),
    outline = Color(0xFF8D9199),
)

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
    val context = LocalContext.current
    var currentPage by rememberSaveable { mutableStateOf(AppPage.HOME) }
    var extensionState by remember { mutableStateOf(SystemExtensionState(SystemExtensionStatus.NOT_INSTALLED)) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val repository = remember { SettingsRepository(context.applicationContext) }
    var server by remember { mutableStateOf(repository.loadServer()) }
    var checkRequest by remember { mutableIntStateOf(0) }
    var connectionStatus by remember {
        mutableStateOf(
            if (server == null) ConnectionStatus.NOT_CONFIGURED else ConnectionStatus.CHECKING,
        )
    }
    var connectionFailure by remember { mutableStateOf<SyncFailureKind?>(null) }
    val extensionController = remember {
        SystemExtensionController(context.applicationContext) { extensionState = it }
    }

    DisposableEffect(extensionController) {
        extensionController.start()
        onDispose(extensionController::stop)
    }

    LaunchedEffect(server, checkRequest) {
        val checkedServer = server
        if (checkedServer == null) {
            connectionStatus = ConnectionStatus.NOT_CONFIGURED
            connectionFailure = null
            return@LaunchedEffect
        }
        connectionStatus = ConnectionStatus.CHECKING
        connectionFailure = null
        connectionStatus = runCatching {
            withContext(Dispatchers.IO) { SyncClipboardClient(checkedServer).testConnection() }
        }.fold(
            onSuccess = { ConnectionStatus.CONNECTED },
            onFailure = {
                connectionFailure = it.toSyncFailureKind()
                ConnectionStatus.FAILED
            },
        )
    }

    fun showMessage(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    fun updateServer(updatedServer: ServerConfig?) {
        server = updatedServer
        checkRequest++
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
            AppPage.HOME -> DashboardPage(
                contentPadding = contentPadding,
                extensionState = extensionState,
                server = server,
                connectionStatus = connectionStatus,
                connectionFailure = connectionFailure,
                onRetryConnection = { checkRequest++ },
            )
            AppPage.SETTINGS -> SettingsPage(
                contentPadding,
                requestTile,
                extensionState,
                extensionController,
                ::showMessage,
                ::updateServer,
            )
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
private fun DashboardPage(
    contentPadding: PaddingValues,
    extensionState: SystemExtensionState,
    server: ServerConfig?,
    connectionStatus: ConnectionStatus,
    connectionFailure: SyncFailureKind?,
    onRetryConnection: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember { SettingsRepository(context.applicationContext) }
    val lastSync = remember(extensionState.lastSuccessfulSyncTime) { repository.loadLastSync() }
    val advancedSync = remember(extensionState.status, extensionState.automaticSyncState) {
        repository.loadAdvancedSyncSettings()
    }

    PageColumn(contentPadding) {
        ConnectionCard(
            status = connectionStatus,
            server = server,
            failure = connectionFailure,
            onRetry = onRetryConnection,
        )
        AutomaticSyncCard(advancedSync, extensionState)
        LastSyncCard(lastSync)
    }
}

@Composable
private fun ConnectionCard(
    status: ConnectionStatus,
    server: ServerConfig?,
    failure: SyncFailureKind?,
    onRetry: () -> Unit,
) {
    val statusColor = when (status) {
        ConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.tertiary
        ConnectionStatus.FAILED -> MaterialTheme.colorScheme.error
        ConnectionStatus.CHECKING -> warningIndicatorColor()
        ConnectionStatus.NOT_CONFIGURED -> MaterialTheme.colorScheme.outline
    }
    val title = when (status) {
        ConnectionStatus.CONNECTED -> "连接正常"
        ConnectionStatus.FAILED -> syncFailureTitle(failure, "服务器检查失败")
        ConnectionStatus.CHECKING -> "正在检查连接"
        ConnectionStatus.NOT_CONFIGURED -> "尚未配置服务器"
    }
    val detail = when (status) {
        ConnectionStatus.CONNECTED,
        ConnectionStatus.CHECKING -> server?.normalizedUrl.orEmpty()
        ConnectionStatus.FAILED -> "${syncFailureAction(failure)}，然后重试。"
        ConnectionStatus.NOT_CONFIGURED -> "请先前往设置填写服务器信息。"
    }

    SectionCard(title = "服务器") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .background(statusColor, CircleShape),
            )
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
        if (server != null) {
            OutlinedButton(
                onClick = onRetry,
                enabled = status != ConnectionStatus.CHECKING,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (status == ConnectionStatus.CHECKING) "正在检查" else "重新检查")
            }
        }
    }
}

@Composable
private fun AutomaticSyncCard(
    settings: AdvancedSyncSettings,
    extensionState: SystemExtensionState,
) {
    val extensionReady = extensionState.status == SystemExtensionStatus.READY
    val runtimeState = extensionState.automaticSyncState
    val running = settings.enabled && extensionReady && runtimeState == AutomaticSyncRuntimeState.RUNNING
    val warning = settings.enabled && extensionReady && runtimeState in setOf(
        AutomaticSyncRuntimeState.UNKNOWN,
        AutomaticSyncRuntimeState.WAITING_FOR_WIFI,
        AutomaticSyncRuntimeState.WAITING_FOR_NETWORK,
        AutomaticSyncRuntimeState.WAITING_FOR_UNLOCK,
        AutomaticSyncRuntimeState.CONNECTING,
    )
    val failed = settings.enabled && (
        !extensionReady ||
            runtimeState == AutomaticSyncRuntimeState.ERROR ||
            runtimeState == AutomaticSyncRuntimeState.SERVER_NOT_CONFIGURED
        )
    val title = when {
        !settings.enabled -> "当前使用手动同步"
        extensionState.status == SystemExtensionStatus.NOT_INSTALLED -> "自动同步不可用"
        extensionState.status == SystemExtensionStatus.INSTALLED_NOT_CONNECTED -> "自动同步未连接"
        extensionState.status == SystemExtensionStatus.INCOMPATIBLE -> "自动同步需要更新扩展"
        runtimeState == AutomaticSyncRuntimeState.RUNNING -> "自动同步运行中"
        runtimeState == AutomaticSyncRuntimeState.WAITING_FOR_WIFI -> "等待 Wi-Fi"
        runtimeState == AutomaticSyncRuntimeState.WAITING_FOR_NETWORK -> "等待网络连接"
        runtimeState == AutomaticSyncRuntimeState.WAITING_FOR_UNLOCK -> "等待设备解锁"
        runtimeState == AutomaticSyncRuntimeState.CONNECTING -> "正在连接自动同步"
        runtimeState == AutomaticSyncRuntimeState.SERVER_NOT_CONFIGURED -> "尚未配置同步服务器"
        runtimeState == AutomaticSyncRuntimeState.ERROR ->
            syncFailureTitle(extensionState.automaticSyncFailure, "自动同步遇到问题")
        else -> "正在读取自动同步状态"
    }
    val detail = when {
        !settings.enabled -> "磁贴、分享和手动同步可继续使用。"
        extensionState.status == SystemExtensionStatus.NOT_INSTALLED ->
            "请先安装与主体应用匹配的系统扩展。"
        extensionState.status == SystemExtensionStatus.INSTALLED_NOT_CONNECTED ->
            "请在模块管理器中启用系统扩展并重新启动设备。"
        extensionState.status == SystemExtensionStatus.INCOMPATIBLE ->
            "请安装与当前应用兼容的系统扩展。"
        runtimeState == AutomaticSyncRuntimeState.RUNNING -> buildList {
            if (settings.uploadText) add("自动上传文本")
            if (settings.downloadText) add("自动接收文本")
            if (settings.downloadImage) add("自动接收图片")
            if (settings.downloadFile) add("自动接收文件")
        }.joinToString(" · ").ifEmpty { "自动同步已就绪" }
        runtimeState == AutomaticSyncRuntimeState.WAITING_FOR_WIFI ->
            "连接 Wi-Fi 后将自动继续。"
        runtimeState == AutomaticSyncRuntimeState.WAITING_FOR_NETWORK ->
            "网络恢复后将自动继续。"
        runtimeState == AutomaticSyncRuntimeState.WAITING_FOR_UNLOCK ->
            "设备解锁后将自动继续。"
        runtimeState == AutomaticSyncRuntimeState.CONNECTING ->
            "正在连接服务器，成功后将自动继续。"
        runtimeState == AutomaticSyncRuntimeState.SERVER_NOT_CONFIGURED ->
            "请先在设置中添加并选择服务器。"
        runtimeState == AutomaticSyncRuntimeState.ERROR ->
            "${syncFailureAction(extensionState.automaticSyncFailure)}。自动同步会继续重试。"
        else -> "请稍候。"
    }
    val statusColor = when {
        running -> MaterialTheme.colorScheme.tertiary
        warning -> warningIndicatorColor()
        failed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }

    SectionCard(title = "自动同步") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .background(statusColor, CircleShape),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (running && extensionState.lastClipboardEventTime > 0L) {
            val eventTime = remember(extensionState.lastClipboardEventTime) {
                DateFormat.getTimeInstance(DateFormat.SHORT)
                    .format(Date(extensionState.lastClipboardEventTime))
            }
            Text(
                "最近检测到剪贴板变化：$eventTime",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun syncFailureTitle(failure: SyncFailureKind?, fallbackTitle: String): String = when (failure) {
    SyncFailureKind.AUTHENTICATION -> "服务器认证失败"
    SyncFailureKind.NETWORK -> "无法连接服务器"
    SyncFailureKind.TLS -> "HTTPS 证书验证失败"
    SyncFailureKind.SERVER -> "服务器响应异常"
    SyncFailureKind.STORAGE -> "文件保存失败"
    SyncFailureKind.CONTENT -> "同步内容异常"
    SyncFailureKind.UNKNOWN,
    null -> fallbackTitle
}

private fun syncFailureAction(failure: SyncFailureKind?): String = when (failure) {
    SyncFailureKind.AUTHENTICATION -> "请在设置中检查用户名和密码"
    SyncFailureKind.NETWORK -> "请检查网络连接和服务器地址"
    SyncFailureKind.TLS -> "请检查 HTTPS 证书或自签名证书设置"
    SyncFailureKind.SERVER -> "请确认服务正在运行且版本兼容"
    SyncFailureKind.STORAGE -> "请重新选择保存目录并检查可用空间"
    SyncFailureKind.CONTENT -> "请检查其他设备和服务器版本"
    SyncFailureKind.UNKNOWN,
    null -> "请重新检查服务器设置"
}

@Composable
private fun warningIndicatorColor(): Color =
    if (isSystemInDarkTheme()) Color(0xFFFFD54F) else Color(0xFFF9A825)

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

private enum class ServerEditorMode {
    ADD,
    EDIT,
}

private enum class SaveDirectoryType {
    IMAGE,
    FILE,
}

@Composable
private fun SettingsPage(
    contentPadding: PaddingValues,
    requestTile: (Class<*>, String, Int, (String) -> Unit) -> Unit,
    extensionState: SystemExtensionState,
    extensionController: SystemExtensionController,
    showMessage: (String) -> Unit,
    onServerChanged: (ServerConfig?) -> Unit,
) {
    val context = LocalContext.current
    val repository = remember { SettingsRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()

    var profiles by remember { mutableStateOf(repository.loadServerProfiles()) }
    var editorMode by rememberSaveable { mutableStateOf<ServerEditorMode?>(null) }
    var displayedEditorMode by rememberSaveable { mutableStateOf<ServerEditorMode?>(null) }
    var serverId by rememberSaveable { mutableStateOf("") }
    var serverName by rememberSaveable { mutableStateOf("") }
    var serverUrl by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var trustInsecure by rememberSaveable { mutableStateOf(false) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var saving by rememberSaveable { mutableStateOf(false) }
    var testing by rememberSaveable { mutableStateOf(false) }
    var advancedSync by remember { mutableStateOf(repository.loadAdvancedSyncSettings()) }
    var showUninstallConfirmation by rememberSaveable { mutableStateOf(false) }
    var serverPendingDeletion by remember { mutableStateOf<ServerConfig?>(null) }
    var showLicense by rememberSaveable { mutableStateOf(false) }

    fun openEditor(mode: ServerEditorMode) {
        val source = profiles.activeServer.takeIf { mode == ServerEditorMode.EDIT }
        serverId = source?.id ?: UUID.randomUUID().toString()
        serverName = source?.name.orEmpty()
        serverUrl = source?.url.orEmpty()
        username = source?.username.orEmpty()
        password = source?.password.orEmpty()
        trustInsecure = source?.trustInsecureCertificate ?: false
        passwordVisible = false
        displayedEditorMode = mode
        editorMode = mode
    }

    fun currentConfig() = ServerConfig(
        id = serverId,
        name = serverName,
        url = serverUrl,
        username = username,
        password = password,
        trustInsecureCertificate = trustInsecure,
    ).also { it.validate() }

    fun applyServerProfiles(updatedProfiles: ServerProfiles) {
        profiles = updatedProfiles
        onServerChanged(updatedProfiles.activeServer)
        extensionController.reloadConfiguration()
    }

    fun saveAdvancedSync(
        newSettings: AdvancedSyncSettings,
        onSaved: () -> Unit = {},
    ) {
        if (
            newSettings.enabled &&
            !advancedSync.enabled &&
            extensionState.status != SystemExtensionStatus.READY
        ) {
            showMessage("系统扩展连接后才能开启高级自动同步")
            return
        }
        if (newSettings.downloadImage && newSettings.imageSaveTreeUri == null) {
            showMessage("请先选择图片保存目录")
            return
        }
        if (newSettings.downloadFile && newSettings.fileSaveTreeUri == null) {
            showMessage("请先选择文件保存目录")
            return
        }
        advancedSync = newSettings
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { repository.saveAdvancedSyncSettings(newSettings) }
            }.onSuccess {
                extensionController.reloadConfiguration()
                onSaved()
            }.onFailure {
                advancedSync = repository.loadAdvancedSyncSettings()
                showMessage(it.message ?: "保存自动同步设置失败")
            }
        }
    }

    fun saveDirectory(uri: Uri, type: SaveDirectoryType) {
        val permissionFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, permissionFlags)
        }.onSuccess {
            val previousUri = when (type) {
                SaveDirectoryType.IMAGE -> advancedSync.imageSaveTreeUri
                SaveDirectoryType.FILE -> advancedSync.fileSaveTreeUri
            }
            val otherUri = when (type) {
                SaveDirectoryType.IMAGE -> advancedSync.fileSaveTreeUri
                SaveDirectoryType.FILE -> advancedSync.imageSaveTreeUri
            }
            val updated = when (type) {
                SaveDirectoryType.IMAGE -> advancedSync.copy(imageSaveTreeUri = uri.toString())
                SaveDirectoryType.FILE -> advancedSync.copy(fileSaveTreeUri = uri.toString())
            }
            saveAdvancedSync(updated) {
                if (previousUri != null && previousUri != uri.toString() && previousUri != otherUri) {
                    runCatching {
                        context.contentResolver.releasePersistableUriPermission(
                            Uri.parse(previousUri),
                            permissionFlags,
                        )
                    }
                }
            }
        }.onFailure {
            showMessage("无法使用所选目录，请重新选择")
        }
    }

    val imageDirectoryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let { saveDirectory(it, SaveDirectoryType.IMAGE) } }
    val fileDirectoryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let { saveDirectory(it, SaveDirectoryType.FILE) } }

    if (showUninstallConfirmation) {
        AlertDialog(
            onDismissRequest = { showUninstallConfirmation = false },
            title = { Text("卸载系统扩展？") },
            text = { Text("卸载后，后台自动同步将停止。磁贴、分享和手动同步继续可用。") },
            confirmButton = {
                FilledTonalButton(
                    onClick = {
                        showUninstallConfirmation = false
                        context.startActivity(
                            Intent(
                                Intent.ACTION_DELETE,
                                Uri.parse("package:${BridgeContract.EXTENSION_PACKAGE}"),
                            ),
                        )
                    },
                ) { Text("继续卸载") }
            },
            dismissButton = {
                FilledTonalButton(onClick = { showUninstallConfirmation = false }) {
                    Text("取消")
                }
            },
        )
    }
    serverPendingDeletion?.let { server ->
        AlertDialog(
            onDismissRequest = { serverPendingDeletion = null },
            title = { Text("删除服务器方案？") },
            text = {
                Text(
                    if (profiles.servers.size == 1) {
                        "「${server.displayName}」将从此设备移除。删除后需要重新添加服务器才能同步。"
                    } else {
                        "「${server.displayName}」将从此设备移除，随后会使用列表中的其他服务器。"
                    },
                )
            },
            confirmButton = {
                FilledTonalButton(
                    onClick = {
                        serverPendingDeletion = null
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    repository.deleteServer(server.id)
                                }
                            }.onSuccess {
                                applyServerProfiles(it)
                                editorMode = null
                                showMessage("服务器方案已删除")
                            }.onFailure {
                                showMessage(it.message ?: "删除服务器方案失败，请重试")
                            }
                        }
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                FilledTonalButton(onClick = { serverPendingDeletion = null }) {
                    Text("取消")
                }
            },
        )
    }
    if (showLicense) {
        LicenseDialog(onDismiss = { showLicense = false })
    }

    PageColumn(contentPadding) {
        AdvancedSyncSettingsCard(
            settings = advancedSync,
            extensionStatus = extensionState.status,
            onSettingsChange = { saveAdvancedSync(it) },
        )
        AutomaticSyncStorageCard(
            settings = advancedSync,
            onChooseImageDirectory = {
                imageDirectoryLauncher.launch(advancedSync.imageSaveTreeUri?.let(Uri::parse))
            },
            onChooseFileDirectory = {
                fileDirectoryLauncher.launch(advancedSync.fileSaveTreeUri?.let(Uri::parse))
            },
        )
        SystemExtensionCard(
            status = extensionState.status,
            onRefresh = extensionController::refresh,
            onUninstall = { showUninstallConfirmation = true },
        )
        ServerProfilesCard(
            profiles = profiles,
            editorOpen = editorMode != null,
            onSelect = { server ->
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { repository.selectServer(server.id) }
                    }.onSuccess(::applyServerProfiles)
                        .onFailure { showMessage(it.message ?: "切换服务器失败，请重试") }
                }
            },
            onAdd = { openEditor(ServerEditorMode.ADD) },
            onEdit = { openEditor(ServerEditorMode.EDIT) },
        )
        AnimatedVisibility(
            visible = editorMode != null,
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
        ) {
            displayedEditorMode?.let { mode ->
                ServerEditorCard(
                    title = if (mode == ServerEditorMode.ADD) "新增服务器" else "编辑服务器",
                    serverName = serverName,
                    onServerNameChange = { serverName = it },
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
                    onCancel = { editorMode = null },
                    onDelete = if (mode == ServerEditorMode.EDIT) {
                        { profiles.activeServer?.let { serverPendingDeletion = it } }
                    } else {
                        null
                    },
                    onSave = {
                        val config = runCatching { currentConfig() }.getOrElse {
                            showMessage(it.message ?: "请检查服务器配置")
                            return@ServerEditorCard
                        }
                        saving = true
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) { repository.saveServer(config) }
                            }.onSuccess {
                                applyServerProfiles(it)
                                editorMode = null
                                showMessage(
                                    if (mode == ServerEditorMode.ADD) {
                                        "服务器已添加"
                                    } else {
                                        "服务器配置已保存"
                                    },
                                )
                            }.onFailure {
                                showMessage(it.message ?: "保存失败，请检查填写内容")
                            }
                            saving = false
                        }
                    },
                    onTest = {
                        val config = runCatching { currentConfig() }.getOrElse {
                            showMessage(it.message ?: "请检查服务器配置")
                            return@ServerEditorCard
                        }
                        testing = true
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    SyncClipboardClient(config).testConnection()
                                }
                            }.onSuccess {
                                showMessage("连接成功")
                            }.onFailure {
                                showMessage(
                                    it.toSyncUserMessage(
                                        "连接未完成，请检查服务器配置和网络后重试",
                                    ),
                                )
                            }
                            testing = false
                        }
                    },
                )
            }
        }
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
        AboutCard(
            onShowLicense = { showLicense = true },
            onOpenProjectPage = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL)))
                }.onFailure {
                    showMessage("无法打开项目主页，请检查浏览器设置")
                }
            },
        )
        Spacer(Modifier.size(8.dp))
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
private fun ServerProfilesCard(
    profiles: ServerProfiles,
    editorOpen: Boolean,
    onSelect: (ServerConfig) -> Unit,
    onAdd: () -> Unit,
    onEdit: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val activeServer = profiles.activeServer

    SectionCard(title = "服务器方案") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = { menuExpanded = true },
                    enabled = profiles.servers.isNotEmpty() && !editorOpen,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = activeServer?.displayName ?: "暂无方案",
                        maxLines = 1,
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    profiles.servers.forEach { server ->
                        DropdownMenuItem(
                            text = {
                                Text(if (server.id == activeServer?.id) "✓ ${server.displayName}" else server.displayName)
                            },
                            onClick = {
                                menuExpanded = false
                                onSelect(server)
                            },
                        )
                    }
                }
            }
            FilledTonalButton(onClick = onAdd, enabled = !editorOpen) {
                Text("新增")
            }
            FilledTonalButton(
                onClick = onEdit,
                enabled = activeServer != null && !editorOpen,
            ) {
                Text("编辑")
            }
        }
    }
}

@Composable
private fun ServerEditorCard(
    title: String,
    serverName: String,
    onServerNameChange: (String) -> Unit,
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
    onCancel: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: () -> Unit,
    onTest: () -> Unit,
) {
    SectionCard(title = title) {
        OutlinedTextField(
            value = serverName,
            onValueChange = onServerNameChange,
            label = { Text("方案名称") },
            supportingText = { Text("留空时显示服务器地址") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
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
                IconButton(onClick = onPasswordVisibilityChange) {
                    Icon(
                        painter = painterResource(
                            if (passwordVisible) R.drawable.ic_visibility_off else R.drawable.ic_visibility,
                        ),
                        contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                    )
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
            Switch(
                checked = trustInsecure,
                colors = tonalSwitchColors(),
                onCheckedChange = onTrustInsecureChange,
            )
        }
        FilledTonalButton(
            onClick = onTest,
            enabled = !testing && !saving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (testing) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
            }
            Text(if (testing) "正在连接" else "测试连接")
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onCancel,
                enabled = !saving && !testing,
                modifier = Modifier.weight(1f),
            ) {
                Text("取消")
            }
            FilledTonalButton(
                onClick = onSave,
                enabled = !saving && !testing,
                modifier = Modifier.weight(1f),
            ) {
                if (saving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                Text(if (saving) "正在保存" else "保存")
            }
        }
        onDelete?.let {
            TextButton(
                onClick = it,
                enabled = !saving && !testing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("删除服务器方案", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun AdvancedSyncSettingsCard(
    settings: AdvancedSyncSettings,
    extensionStatus: SystemExtensionStatus,
    onSettingsChange: (AdvancedSyncSettings) -> Unit,
) {
    val extensionReady = extensionStatus == SystemExtensionStatus.READY
    SectionCard(title = "高级自动同步") {
        SettingSwitchRow(
            title = "后台自动同步",
            detail = if (extensionReady) {
                "实时检测本地剪贴板，并定期接收远端文本。"
            } else {
                "系统扩展连接后可开启。"
            },
            checked = settings.enabled,
            enabled = extensionReady || settings.enabled,
            onCheckedChange = { onSettingsChange(settings.copy(enabled = it)) },
        )
        SettingSwitchRow(
            title = "仅通过 Wi-Fi 自动同步",
            detail = "使用移动网络时暂停，连接 Wi-Fi 后继续。",
            checked = settings.wifiOnly,
            enabled = settings.enabled,
            onCheckedChange = { onSettingsChange(settings.copy(wifiOnly = it)) },
        )
        SettingSwitchRow(
            title = "自动上传文本",
            detail = "解锁期间自动上传，断网后恢复重试。",
            checked = settings.uploadText,
            enabled = settings.enabled,
            onCheckedChange = { onSettingsChange(settings.copy(uploadText = it)) },
        )
        SettingSwitchRow(
            title = "自动接收文本",
            detail = "解锁期间实时接收，锁屏后暂停。",
            checked = settings.downloadText,
            enabled = settings.enabled,
            onCheckedChange = { onSettingsChange(settings.copy(downloadText = it)) },
        )
        SettingSwitchRow(
            title = "自动接收图片",
            detail = if (settings.imageSaveTreeUri == null) {
                "请先选择图片保存目录。"
            } else {
                "收到远端图片后保存到所选目录。"
            },
            checked = settings.downloadImage,
            enabled = settings.enabled,
            onCheckedChange = { onSettingsChange(settings.copy(downloadImage = it)) },
        )
        SettingSwitchRow(
            title = "自动接收文件",
            detail = if (settings.fileSaveTreeUri == null) {
                "请先选择文件保存目录。"
            } else {
                "收到远端文件后保存到所选目录。"
            },
            checked = settings.downloadFile,
            enabled = settings.enabled,
            onCheckedChange = { onSettingsChange(settings.copy(downloadFile = it)) },
        )
        SettingSwitchRow(
            title = "忽略敏感内容",
            detail = "密码管理器等应用标记的敏感内容不参与自动上传。",
            checked = settings.ignoreSensitiveContent,
            enabled = settings.enabled,
            onCheckedChange = { onSettingsChange(settings.copy(ignoreSensitiveContent = it)) },
        )
    }
}

@Composable
private fun AutomaticSyncStorageCard(
    settings: AdvancedSyncSettings,
    onChooseImageDirectory: () -> Unit,
    onChooseFileDirectory: () -> Unit,
) {
    SectionCard(title = "自动接收保存位置") {
        SaveDirectoryRow(
            title = "图片保存目录",
            treeUri = settings.imageSaveTreeUri,
            onChoose = onChooseImageDirectory,
        )
        SaveDirectoryRow(
            title = "文件保存目录",
            treeUri = settings.fileSaveTreeUri,
            onChoose = onChooseFileDirectory,
        )
    }
}

@Composable
private fun SaveDirectoryRow(
    title: String,
    treeUri: String?,
    onChoose: () -> Unit,
) {
    val context = LocalContext.current
    val detail by produceState(
        initialValue = if (treeUri == null) "尚未选择" else "正在读取目录",
        key1 = treeUri,
    ) {
        value = treeUri?.let { storedUri ->
            withContext(Dispatchers.IO) {
                resolveSafDirectoryLabel(context, Uri.parse(storedUri))
            }
        } ?: "尚未选择"
    }
    val selected = treeUri != null

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onChoose) {
            Text(if (selected) "更改" else "选择")
        }
    }
}

@Composable
private fun SystemExtensionCard(
    status: SystemExtensionStatus,
    onRefresh: () -> Unit,
    onUninstall: () -> Unit,
) {
    val installed = status != SystemExtensionStatus.NOT_INSTALLED
    val title = when (status) {
        SystemExtensionStatus.NOT_INSTALLED -> "尚未安装"
        SystemExtensionStatus.INSTALLED_NOT_CONNECTED -> "已安装，等待启用"
        SystemExtensionStatus.INCOMPATIBLE -> "需要更新"
        SystemExtensionStatus.READY -> "运行正常"
    }
    val detail = when (status) {
        SystemExtensionStatus.NOT_INSTALLED -> "安装与主体应用匹配的系统扩展后，可使用后台自动同步。"
        SystemExtensionStatus.INSTALLED_NOT_CONNECTED -> "请在模块管理器中启用系统扩展并重新启动设备。"
        SystemExtensionStatus.INCOMPATIBLE -> "当前扩展版本与主体应用不兼容。"
        SystemExtensionStatus.READY -> "后台剪贴板能力已经就绪。"
    }

    SectionCard(title = "系统扩展") {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
            Text("重新检查")
        }
        if (installed) {
            TextButton(onClick = onUninstall, modifier = Modifier.fillMaxWidth()) {
                Text("卸载系统扩展", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            colors = tonalSwitchColors(),
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun tonalSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = MaterialTheme.colorScheme.onSecondaryContainer,
    checkedTrackColor = MaterialTheme.colorScheme.secondaryContainer,
    disabledCheckedThumbColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.38f),
    disabledCheckedTrackColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.38f),
)

@Composable
private fun LicenseDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val licenseText by produceState(initialValue = "正在读取许可证……") {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open("LICENSE").bufferedReader().use { it.readText() }
            }.getOrElse {
                "许可证文件无法读取，请前往项目主页查看。"
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("GNU General Public License v3.0") },
        text = {
            Text(
                text = licenseText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            FilledTonalButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

@Composable
private fun AboutCard(
    onShowLicense: () -> Unit,
    onOpenProjectPage: () -> Unit,
) {
    SectionCard(title = "关于") {
        Text(
            "SyncClipboard Kotlin ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "本软件依照 GNU GPL v3.0 发布，不附带任何担保。你可以查看、修改和分发源代码。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(onClick = onShowLicense, modifier = Modifier.fillMaxWidth()) {
            Text("查看开源许可证")
        }
        OutlinedButton(onClick = onOpenProjectPage, modifier = Modifier.fillMaxWidth()) {
            Text("项目主页与致谢")
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
    val colors = if (isSystemInDarkTheme()) DarkColorScheme else LightColorScheme
    MaterialTheme(colorScheme = colors, content = content)
}
