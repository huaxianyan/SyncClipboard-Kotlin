package com.neko7ina.syncclipboard.sync

import android.app.KeyguardManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import com.neko7ina.syncclipboard.bridge.BridgeContract
import com.neko7ina.syncclipboard.bridge.ISyncBridgeService
import com.neko7ina.syncclipboard.bridge.ISystemClipboardBridge
import com.neko7ina.syncclipboard.data.AdvancedSyncSettings
import com.neko7ina.syncclipboard.data.SettingsRepository
import com.neko7ina.syncclipboard.data.SyncDirection
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private data class PendingClipboardWrite(
    val text: String,
    val sourceHash: String,
)

private class RemoteClipboardWritePausedException : Exception()

class SystemBridgeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val transferMutex = Mutex()
    private val remoteSyncLifecycleMutex = Mutex()

    @Volatile
    private var systemBridge: ISystemClipboardBridge? = null

    @Volatile
    private var lastClipboardEventTime = 0L

    @Volatile
    private var incompatibleBridgeDetected = false

    @Volatile
    private lateinit var repository: SettingsRepository

    private val automaticSyncEvents by lazy { AutomaticSyncEventStore(this) }

    @Volatile
    private var deviceUnlocked = false

    @Volatile
    private var networkAvailable = false

    @Volatile
    private var signalRConnected = false

    @Volatile
    private var signalRFailure: SyncFailureKind? = null

    @Volatile
    private var pendingUploadFailure: SyncFailureKind? = null

    @Volatile
    private var remoteTransferFailure: SyncFailureKind? = null

    @Volatile
    private var pendingClipboardText: String? = null

    @Volatile
    private var pendingClipboardTextSensitive = false

    @Volatile
    private var pendingClipboardWrite: PendingClipboardWrite? = null

    /** 服务器时钟相对本机时钟的偏移；为 null 时不做陈旧内容判定。 */
    @Volatile
    private var remoteClockOffsetMillis: Long? = null

    private val remoteResumePolicy = RemoteSyncResumePolicy()
    private var pendingUploadJob: Job? = null
    private var clipboardWriteRetryJob: Job? = null
    private var remoteSyncJob: Job? = null
    private val bridgeDeathRecipient = IBinder.DeathRecipient(::disconnectSystemBridge)
    private val connectivityManager by lazy { getSystemService(ConnectivityManager::class.java) }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                updateDeviceUnlockedState(false)
            } else {
                refreshDeviceUnlockedState()
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshNetworkAvailability()
        override fun onLost(network: Network) = refreshNetworkAvailability()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            refreshNetworkAvailability()
    }

    private val binder = object : ISyncBridgeService.Stub() {
        override fun registerSystemBridge(
            protocolVersion: Int,
            bridge: ISystemClipboardBridge,
        ): Int {
            enforceSystemUiCaller()
            if (protocolVersion != BridgeContract.PROTOCOL_VERSION) {
                Log.w(TAG, "System bridge protocol mismatch: $protocolVersion")
                incompatibleBridgeDetected = true
                return BridgeContract.INCOMPATIBLE
            }
            val bridgeBinder = bridge.asBinder()
            if (systemBridge?.asBinder() == bridgeBinder && bridgeBinder.isBinderAlive) {
                requestClipboardWriteRetry()
                return BridgeContract.REGISTERED
            }
            Log.i(TAG, "System bridge registered with protocol $protocolVersion")
            incompatibleBridgeDetected = false
            disconnectSystemBridge(restartRemoteSync = false)
            systemBridge = bridge
            runCatching { bridge.asBinder().linkToDeath(bridgeDeathRecipient, 0) }
                .onFailure {
                    systemBridge = null
                    return BridgeContract.INCOMPATIBLE
                }
            scope.launch {
                recordAutomaticSyncEvent(AutomaticSyncEventKind.EXTENSION_CONNECTED)
            }
            requestPendingTextUpload()
            requestClipboardWriteRetry()
            requestRemoteSyncRestart()
            return BridgeContract.REGISTERED
        }

        override fun unregisterSystemBridge() {
            enforceSystemUiCaller()
            disconnectSystemBridge()
        }

        override fun onClipboardText(text: String, sensitive: Boolean) {
            enforceSystemUiCaller()
            if (text.isBlank()) {
                if (pendingClipboardText?.isBlank() == true) pendingClipboardText = null
                return
            }
            this@SystemBridgeService.lastClipboardEventTime = System.currentTimeMillis()
            // 登记本机剪贴板的事实。它与同步开关无关：开关关着的时候剪贴板照样会被本地复制改写，
            // 而这份登记值决定「云端推来的内容还要不要再写一次」。
            repository.saveLocalClipboardHash(PayloadFactory.textHash(text))
            val settings = repository.loadAdvancedSyncSettings()
            if (!settings.enabled || !settings.uploadText) return
            if (sensitive && settings.ignoreSensitiveContent) return
            storePendingText(text, persist = !sensitive)
            requestPendingTextUpload()
        }

        override fun onDeviceLockStateChanged(locked: Boolean) {
            enforceSystemUiCaller()
            val interactive = getSystemService(PowerManager::class.java).isInteractive
            Log.i(TAG, "System lock state changed: locked=$locked, interactive=$interactive")
            updateDeviceUnlockedState(!locked && interactive)
        }

        override fun getConnectionState(): Int {
            enforceHostCaller()
            return when {
                systemBridge?.asBinder()?.isBinderAlive == true -> BridgeContract.CONNECTION_READY
                incompatibleBridgeDetected -> BridgeContract.CONNECTION_INCOMPATIBLE
                else -> BridgeContract.CONNECTION_DISCONNECTED
            }
        }

        override fun getLastClipboardEventTime(): Long {
            enforceHostCaller()
            return this@SystemBridgeService.lastClipboardEventTime
        }

        override fun getLastSuccessfulSyncTime(): Long {
            enforceHostCaller()
            return repository.loadLastSync()?.timestampMillis ?: 0L
        }

        override fun reloadConfiguration() {
            enforceHostCaller()
            repository = SettingsRepository(
                this@SystemBridgeService,
                syncProcess = true,
            )
            val settings = repository.loadAdvancedSyncSettings()
            remoteResumePolicy.onSettingsChanged(
                receivePausedRemoteChanges = settings.receivePausedRemoteChanges,
                remoteSyncEnabled = settings.enabled && (
                    settings.downloadText || settings.downloadImage || settings.downloadFile
                ),
            )
            if (!settings.receivePausedRemoteChanges) pendingClipboardWrite = null
            if (!settings.enabled || !settings.uploadText) {
                clearPendingText()
            } else if (pendingClipboardText == null) {
                pendingClipboardText = repository.loadPendingAutomaticText()
            }
            signalRFailure = null
            pendingUploadFailure = null
            remoteTransferFailure = null
            refreshNetworkAvailability()
            requestPendingTextUpload()
            requestClipboardWriteRetry()
            requestRemoteSyncRestart()
        }

        override fun updateExtensionAvailability(installed: Boolean) {
            enforceHostCaller()
            if (!installed) disconnectSystemBridge()
        }

        override fun getAutomaticSyncState(): Int {
            enforceHostCaller()
            return resolveAutomaticSyncState()
        }

        override fun getAutomaticSyncError(): Int {
            enforceHostCaller()
            return resolveAutomaticSyncError()
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = SettingsRepository(this, syncProcess = true)
        val settings = repository.loadAdvancedSyncSettings()
        remoteResumePolicy.initialize(
            receivePausedRemoteChanges = settings.receivePausedRemoteChanges,
            remoteSyncEnabled = settings.enabled && (
                settings.downloadText || settings.downloadImage || settings.downloadFile
            ),
        )
        pendingClipboardText = if (settings.enabled && settings.uploadText) {
            repository.loadPendingAutomaticText()
        } else {
            repository.clearPendingAutomaticText()
            null
        }
        deviceUnlocked = isDeviceUnlocked()
        networkAvailable = isNetworkAvailable()
        if (settings.enabled && !networkAvailable) {
            scope.launch {
                recordAutomaticSyncEvent(AutomaticSyncEventKind.WAITING_FOR_NETWORK)
            }
        }
        registerReceiver(
            screenStateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            Context.RECEIVER_EXPORTED,
        )
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenStateReceiver) }
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        disconnectSystemBridge(restartRemoteSync = false)
        scope.cancel()
        super.onDestroy()
    }

    @Synchronized
    private fun storePendingText(text: String, persist: Boolean) {
        if (persist) {
            repository.savePendingAutomaticText(text)
        } else {
            repository.clearPendingAutomaticText()
        }
        pendingClipboardText = text
        pendingClipboardTextSensitive = !persist
    }

    @Synchronized
    private fun clearPendingText(expectedText: String? = null) {
        if (expectedText != null && pendingClipboardText != expectedText) return
        if (expectedText == null) {
            repository.clearPendingAutomaticText()
        } else {
            repository.clearPendingAutomaticTextIfMatches(expectedText)
        }
        pendingClipboardText = null
        pendingClipboardTextSensitive = false
    }

    @Synchronized
    private fun pendingTextSnapshot(): Pair<String, Boolean>? =
        pendingClipboardText?.let { it to pendingClipboardTextSensitive }

    @Synchronized
    private fun requestPendingTextUpload() {
        if (!deviceUnlocked || !networkAvailable) {
            pendingUploadJob?.cancel()
            pendingUploadJob = null
            return
        }
        if (pendingUploadJob?.isActive == true || pendingClipboardText == null) return
        pendingUploadJob = scope.launch {
            var failureIndex = 0
            while (currentCoroutineContext().isActive && deviceUnlocked && networkAvailable) {
                val succeeded = transferMutex.withLock { uploadPendingTextOnce() }
                if (succeeded && pendingClipboardText == null) return@launch
                if (succeeded) {
                    failureIndex = 0
                    continue
                }
                delay(RECONNECT_DELAYS_MILLIS[failureIndex])
                failureIndex = (failureIndex + 1).coerceAtMost(RECONNECT_DELAYS_MILLIS.lastIndex)
            }
        }
    }

    private fun uploadPendingTextOnce(): Boolean {
        val (text, sensitive) = pendingTextSnapshot() ?: return true
        val settings = repository.loadAdvancedSyncSettings()
        if (!settings.enabled || !settings.uploadText) {
            clearPendingText()
            return true
        }
        val previousHash = repository.loadLastAutomaticRemoteHash()
        return runCatching {
            ClipboardTransferService(this, repository).uploadTextIfChanged(
                text = text,
                previousHash = previousHash,
                recordHistory = !sensitive,
            )
        }.fold(
            onSuccess = { hash ->
                pendingUploadFailure = null
                if (hash != null) {
                    repository.saveLastAutomaticRemoteHash(hash)
                    recordAutomaticSyncEvent(
                        AutomaticSyncEventKind.UPLOAD_SUCCEEDED,
                        contentType = ClipboardType.TEXT,
                    )
                }
                clearPendingText(text)
                true
            },
            onFailure = {
                pendingUploadFailure = it.toSyncFailureKind()
                recordAutomaticSyncEvent(
                    AutomaticSyncEventKind.UPLOAD_FAILED,
                    failure = pendingUploadFailure,
                    contentType = ClipboardType.TEXT,
                )
                Log.w(TAG, "Automatic text upload failed", it)
                false
            },
        )
    }

    private fun requestRemoteSyncRestart() {
        scope.launch {
            remoteSyncLifecycleMutex.withLock {
                val wasRunning = remoteSyncJob?.isActive == true
                if (wasRunning) Log.i(TAG, "Remote sync stopping")
                remoteSyncJob?.cancel()
                remoteSyncJob = null
                signalRConnected = false
                signalRFailure = null
                if (shouldRunRemoteSync()) {
                    if (!wasRunning) {
                        // 循环从停摆状态重新启动。不论停摆原因是什么，这段空窗期云端都可能
                        // 已经产生新内容，一律先要求重建基线，避免把旧内容当成新内容写下去。
                        remoteResumePolicy.onAutomaticConditionLost(
                            repository.loadAdvancedSyncSettings().receivePausedRemoteChanges,
                        )
                    }
                    Log.i(TAG, "Remote sync starting")
                    remoteSyncJob = scope.launch { runRemoteSyncLoop() }
                }
            }
        }
    }

    private suspend fun runRemoteSyncLoop() {
        var failureIndex = 0
        var lastFallbackPollAt = 0L
        // 独立校准服务器时钟。时间判据不能挂在基线上：基线一旦漏判，
        // 判据会跟着一起失效，两道防线就变成串联而不是并联。
        remoteClockOffsetMillis = calibrateServerClock()
        while (currentCoroutineContext().isActive && shouldRunRemoteSync()) {
            val server = repository.loadServer() ?: return
            val client = SignalRSyncClient(server, ::handleRemoteProfile)
            try {
                val settings = repository.loadAdvancedSyncSettings()
                if (
                    remoteResumePolicy.shouldEstablishBaseline(
                        receivePausedRemoteChanges = settings.receivePausedRemoteChanges,
                        lastRemoteHash = repository.loadLastAutomaticRemoteHash(),
                        canJudgeByContentTime = canJudgeByContentTime(settings),
                    )
                ) {
                    transferMutex.withLock { establishRemoteBaseline(settings) }
                }
                client.start()
                if (!shouldRunRemoteSync()) return
                failureIndex = 0
                signalRConnected = true
                signalRFailure = null
                recordAutomaticSyncEvent(AutomaticSyncEventKind.REALTIME_CONNECTED)
                Log.i(TAG, "SignalR connected")
                transferMutex.withLock { pollRemoteClipboard() }
                lastFallbackPollAt = SystemClock.elapsedRealtime()
                val closeError = client.awaitClosed()
                throw closeError ?: IllegalStateException("SignalR connection closed")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                signalRConnected = false
                signalRFailure = error.toSyncFailureKind()
                recordAutomaticSyncEvent(
                    AutomaticSyncEventKind.REALTIME_FAILED,
                    failure = signalRFailure,
                )
                Log.w(TAG, "SignalR unavailable", error)
                val now = SystemClock.elapsedRealtime()
                if (now - lastFallbackPollAt >= FALLBACK_POLL_INTERVAL_MILLIS) {
                    runCatching {
                        transferMutex.withLock {
                            val settings = repository.loadAdvancedSyncSettings()
                            if (
                                remoteResumePolicy.shouldEstablishBaseline(
                                    receivePausedRemoteChanges = settings.receivePausedRemoteChanges,
                                    lastRemoteHash = repository.loadLastAutomaticRemoteHash(),
                                    canJudgeByContentTime = canJudgeByContentTime(settings),
                                )
                            ) {
                                establishRemoteBaseline(settings)
                            } else {
                                pollRemoteClipboard()
                            }
                        }
                    }.onFailure { fallbackError ->
                        if (fallbackError is CancellationException) throw fallbackError
                        remoteTransferFailure = fallbackError.toSyncFailureKind()
                        Log.w(TAG, "Automatic fallback download failed", fallbackError)
                    }
                    lastFallbackPollAt = now
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { client.stop() }
            }
            delay(RECONNECT_DELAYS_MILLIS[failureIndex])
            failureIndex = (failureIndex + 1).coerceAtMost(RECONNECT_DELAYS_MILLIS.lastIndex)
        }
    }

    private fun establishRemoteBaseline(settings: AdvancedSyncSettings) {
        // 取远端内容是阻塞网络请求，先记下代际；若期间又发生了新的暂停，
        // 这次「陈旧完成」不应清掉刚置上的标志。
        val generation = remoteResumePolicy.baselineGeneration()
        val transferService = ClipboardTransferService(this, repository)
        val payload = transferService.getRemoteClipboard()
        val sourceHash = if (
            payload.type == ClipboardType.TEXT &&
            settings.downloadText &&
            settings.textHistoryEnabled
        ) {
            transferService.captureRemoteTextForHistory(payload)
        } else {
            transferService.remoteHash(payload)
        }
        repository.saveLastAutomaticRemoteHash(sourceHash)
        if (!remoteResumePolicy.markBaselineEstablished(generation)) {
            Log.i(TAG, "Baseline superseded by a newer pause window, will realign")
        }
        recordAutomaticSyncEvent(AutomaticSyncEventKind.BASELINE_ESTABLISHED)
        remoteTransferFailure = null
    }

    private fun handleRemoteProfile(payload: ClipboardPayload) {
        scope.launch {
            if (!shouldRunRemoteSync()) return@launch
            transferMutex.withLock {
                if (!shouldRunRemoteSync()) return@withLock
                val callback = systemBridge ?: return@withLock
                val previousHash = repository.loadLastAutomaticRemoteHash()
                val settings = repository.loadAdvancedSyncSettings()
                val transferService = ClipboardTransferService(this@SystemBridgeService, repository)
                runCatching {
                    // 推送只由「别的设备刚改完云端」触发，它本身就是内容「刚刚」到达的证据，
                    // 因此不走陈旧判定。判据只属于补查路径：那里本机刚经历了一段看不到
                    // 云端的窗口。套在推送上会把「重新复制一条以前复制过的内容」当成积压：
                    // 那条内容的 createTime 同样很老，但它确实是用户此刻主动复制的。
                    //
                    // 抑制只认本机剪贴板登记值：剪贴板里已经是它，写入才是多余的。
                    if (remoteContentAlreadyPresent(payload, previousHash, transferService, useLocalClipboardForText = true)) {
                        null
                    } else {
                        transferService.applyRemoteAutomatically(payload, settings) { text, hash ->
                            writeClipboardText(callback, text, hash)
                        }
                    }
                }.onSuccess { newHash ->
                    remoteTransferFailure = null
                    if (newHash != null) {
                        repository.saveLastAutomaticRemoteHash(newHash)
                        recordAutomaticSyncEvent(
                            AutomaticSyncEventKind.DOWNLOAD_SUCCEEDED,
                            contentType = payload.type,
                        )
                    }
                }.onFailure {
                    if (it is RemoteClipboardWritePausedException) return@onFailure
                    remoteTransferFailure = it.toSyncFailureKind()
                    recordAutomaticSyncEvent(
                        AutomaticSyncEventKind.DOWNLOAD_FAILED,
                        failure = remoteTransferFailure,
                        contentType = payload.type,
                    )
                    Log.w(TAG, "Automatic pushed content download failed", it)
                }
            }
        }
    }

    private fun pollRemoteClipboard() {
        if (!shouldRunRemoteSync()) return
        val callback = systemBridge ?: return
        val settings = repository.loadAdvancedSyncSettings()
        if (!settings.enabled) return
        val previousHash = repository.loadLastAutomaticRemoteHash()
        val transferService = ClipboardTransferService(this, repository)
        runCatching {
            val payload = transferService.getRemoteClipboard()
            val sourceHash = transferService.remoteHash(payload)
            // 顺序不能颠倒：先看本机是不是已经有这份内容（有则什么都不做，省掉一次内容时间
            // 查询，也避免把刚同步过的正常内容反复归档），再判它是不是窗口里的积压。
            val alreadyPresent = remoteContentAlreadyPresent(
                payload,
                previousHash,
                transferService,
                useLocalClipboardForText = !needsBaselineSuppression(settings),
            )
            val staleAge = if (alreadyPresent) null else staleRemoteContentAgeMillis(transferService, payload)
            when {
                alreadyPresent -> null
                staleAge != null -> {
                    // 归档进本地历史后跳过写入，并把同步点记成它，避免同一份旧内容被反复判定。
                    archiveStaleRemoteText(transferService, payload)
                    repository.saveLastAutomaticRemoteHash(sourceHash)
                    recordAutomaticSyncEvent(
                        AutomaticSyncEventKind.STALE_WRITE_SKIPPED,
                        contentType = payload.type,
                        ageMillis = staleAge,
                    )
                    null
                }
                else -> transferService.applyRemoteAutomatically(payload, settings) { text, hash ->
                    writeClipboardText(callback, text, hash)
                }
            }
        }.onSuccess { newHash ->
            remoteTransferFailure = null
            if (newHash != null) {
                repository.saveLastAutomaticRemoteHash(newHash)
                recordAutomaticSyncEvent(AutomaticSyncEventKind.DOWNLOAD_SUCCEEDED)
            }
        }.onFailure {
            if (it is RemoteClipboardWritePausedException) return@onFailure
            remoteTransferFailure = it.toSyncFailureKind()
            recordAutomaticSyncEvent(
                AutomaticSyncEventKind.DOWNLOAD_FAILED,
                failure = remoteTransferFailure,
            )
            Log.w(TAG, "Automatic content download failed", it)
        }
    }

    private fun writeClipboardText(
        callback: ISystemClipboardBridge,
        text: String,
        sourceHash: String,
    ) {
        if (!deviceUnlocked || !isDeviceUnlocked()) {
            throw RemoteClipboardWritePausedException()
        }
        try {
            callback.setClipboardText(text, sourceHash)
            // 剪贴板里现在是它，登记下来，下一次拿同一份内容来时才知道不必再写。
            repository.saveLocalClipboardHash(sourceHash)
            pendingClipboardWrite = null
        } catch (error: RemoteException) {
            if (!isDeviceUnlocked()) {
                pendingClipboardWrite = null
                throw RemoteClipboardWritePausedException()
            }
            pendingClipboardWrite = PendingClipboardWrite(text, sourceHash)
            requestClipboardWriteRetry()
            throw SyncException(
                "系统扩展暂时无法写入剪贴板，请保持设备解锁",
                error,
                SyncFailureKind.BRIDGE,
            )
        }
    }

    /**
     * 本机是不是已经有这份远端内容。是则不必再处理，也不该再写一次。
     *
     * 文本看**本机剪贴板登记值**：这是本机此刻的事实，能区分两种情况。内容确实已经躺在剪贴板里
     * （重复写入多余），和内容只是被同步过、后来剪贴板被清空或被别的内容覆盖（必须再写一次）。
     *
     * 历史版本只看「上次同步点」（`lastAutomaticRemoteHash`），而那个字段被上传、下载、归档三条
     * 路径共用，表达的是「这个哈希我处理过」，不表达「剪贴板里是它」。归档一条停摆期间到达的旧
     * 内容后这个字段会被改成那条内容，于是同一份内容再被推送时就被静默丢弃。重启后剪贴板被清空，
     * 粘不到它。
     *
     * 登记值拿不到（从未登记，或距上次登记之间设备重启过）时按「没有」处理：未知一律不抑制，
     * 因为把应该出现的内容写进剪贴板总是安全的，而误抑制会让用户拿不到内容。
     *
     * [useLocalClipboardForText] 为 false 时退回上次同步点。那是内容时间判据不可用时的基线降级
     * 路径（老服务端、纯 WebDAV、时钟不可信），窗口内的积压只能靠它阻断。图片与文件没有可读回的
     * 本机状态，始终看上次同步点。
     */
    private fun remoteContentAlreadyPresent(
        payload: ClipboardPayload,
        previousHash: String?,
        transferService: ClipboardTransferService,
        useLocalClipboardForText: Boolean,
    ): Boolean {
        val useLocalClipboard = payload.type == ClipboardType.TEXT && useLocalClipboardForText
        val reference = if (useLocalClipboard) {
            repository.loadLocalClipboardHash()
        } else {
            previousHash
        } ?: return false
        val sourceHash = transferService.remoteHash(payload)
        if (!sourceHash.equals(reference, ignoreCase = true)) return false
        if (useLocalClipboard) {
            // 静默跳过必须在事件表里留痕，否则「明明推来了却没写入」事后完全无法排查。
            recordAutomaticSyncEvent(
                AutomaticSyncEventKind.REMOTE_DEDUPED,
                contentType = payload.type,
            )
        }
        return true
    }

    /**
     * 主判据：远端内容是不是「本机看不到云端的那段时间里冒出来的」旧内容。
     *
     * 返回内容早于判定时刻的毫秒数，非 null 即表示应当跳过写入。判定只用服务端给出的
     * `createTime` 与服务器时钟偏移，不依赖任何存活条件的上报，所以循环因为什么原因
     * 停摆都不影响它：停机不需要谁来枚举，也不需要基线先建好。
     *
     * **只用于「补查」这条路径**（连接建立后的那次 HTTP 查询、以及实时通道不通时的
     * 定时补查），不要用在实时推送路径上。补查时本机刚刚经历了一段看不到云端的窗口，
     * 此时云端躺着的内容都可能是窗口里积压的；而推送只会在别的设备刚改完云端时触发，
     * 它本身就是「刚刚」的证据。把判据套到推送上会把「重新复制一条以前复制过的内容」
     * 也误判成积压。那条内容的 createTime 同样很老，但它是用户此刻主动复制的。
     *
     * 任一环节拿不到数据（非文本内容、服务端不支持历史接口、时钟不可信）都返回
     * null，此时由基线逻辑降级兜底。
     *
     * 调用方必须**先**排除「本机已经是这份内容」的情况：[remoteContentAlreadyPresent]。
     * 历史版本在这里用 `previousHash` 做豁免（「同一个哈希处理过就别再判」），但那个字段被
     * 上传、下载、归档三条路径共用，归档一条旧内容后它照样会被改成那条内容，豁免于是变成漏洞：同一条
     * 内容在被归档后的下一次补查里会绕过陈旧判定，直接写进剪贴板。
     */
    private fun staleRemoteContentAgeMillis(
        transferService: ClipboardTransferService,
        payload: ClipboardPayload,
    ): Long? {
        // 只有文本有可归档的本地历史；其他类型保持原有下载流程。
        if (payload.type != ClipboardType.TEXT) return null
        if (repository.loadAdvancedSyncSettings().receivePausedRemoteChanges) return null
        val offset = remoteClockOffsetMillis ?: return null
        val contentTimestamp = transferService.getRemoteContentTimestamp(payload) ?: return null
        return StaleRemoteContentPolicy.staleAgeMillis(
            judgedAtMillis = System.currentTimeMillis(),
            serverClockOffsetMillis = offset,
            contentCreatedAtMillis = contentTimestamp,
            graceMillis = STALE_CONTENT_GRACE_MILLIS,
        )
    }

    /** 时间判据是否可用；可用时不再需要基线，直接按内容时间判定。 */
    private fun canJudgeByContentTime(settings: AdvancedSyncSettings): Boolean =
        !settings.receivePausedRemoteChanges && remoteClockOffsetMillis != null

    /**
     * 补查路径上是否只能靠「基线」（上次同步点）阻断停摆窗口里到达的内容。
     *
     * 条件是两个同时成立：用户开着这个选项，且内容时间判据拿不到。此时没有更准的信息可用，
     * 基线是唯一手段。其余情况（选项关着、或时间判据可用）都用本机剪贴板登记值，它记录的是
     * 本机事实，能区分「剪贴板里就是它」和「只是同步过、之后被清空或覆盖」。
     */
    private fun needsBaselineSuppression(settings: AdvancedSyncSettings): Boolean =
        !settings.receivePausedRemoteChanges && remoteClockOffsetMillis == null

    /**
     * 校准服务器时钟。拿不到、或偏移大到不像话（单位错误、系统时钟错乱）时返回 null，
     * 让所有时间判定整体降级，而不是拿一个坏值去误杀正常内容。
     */
    private fun calibrateServerClock(): Long? {
        val offset = runCatching { ClipboardTransferService(this, repository).getServerTimeOffsetMillis() }
            .getOrNull() ?: return null
        if (abs(offset) > MAX_TRUSTED_CLOCK_OFFSET_MILLIS) {
            Log.w(TAG, "Server clock offset $offset out of trusted range, content time checks disabled")
            return null
        }
        return offset
    }

    /** 被跳过写入的旧内容仍要归档进本地历史，用户可以在历史列表里找到它。 */
    private fun archiveStaleRemoteText(
        transferService: ClipboardTransferService,
        payload: ClipboardPayload,
    ) {
        runCatching { transferService.captureRemoteTextForHistory(payload) }
            .onFailure { Log.w(TAG, "Unable to archive stale remote text", it) }
    }

    @Synchronized
    private fun requestClipboardWriteRetry() {
        if (!deviceUnlocked || pendingClipboardWrite == null) return
        if (clipboardWriteRetryJob?.isActive == true) return
        clipboardWriteRetryJob = scope.launch {
            for (retryDelay in CLIPBOARD_WRITE_RETRY_DELAYS_MILLIS) {
                delay(retryDelay)
                if (!deviceUnlocked) return@launch
                val succeeded = transferMutex.withLock {
                    val pending = pendingClipboardWrite ?: return@withLock true
                    val callback = systemBridge ?: return@withLock false
                    try {
                        callback.setClipboardText(pending.text, pending.sourceHash)
                        repository.saveLocalClipboardHash(pending.sourceHash)
                        pendingClipboardWrite = null
                        repository.loadServer()?.let { server ->
                            TextSyncHistory.record(
                                context = this@SystemBridgeService,
                                enabled = repository.loadAdvancedSyncSettings().textHistoryEnabled,
                                source = TextSyncHistorySource.REMOTE,
                                serverId = server.id,
                                hash = pending.sourceHash,
                                text = pending.text,
                                appliedToClipboard = true,
                            )
                        }
                        repository.saveLastAutomaticRemoteHash(pending.sourceHash)
                        repository.recordSuccessfulSync(SyncDirection.DOWNLOAD)
                        remoteTransferFailure = null
                        recordAutomaticSyncEvent(
                            AutomaticSyncEventKind.DOWNLOAD_SUCCEEDED,
                            contentType = ClipboardType.TEXT,
                        )
                        true
                    } catch (error: RemoteException) {
                        remoteTransferFailure = SyncFailureKind.BRIDGE
                        recordAutomaticSyncEvent(
                            AutomaticSyncEventKind.DOWNLOAD_FAILED,
                            failure = SyncFailureKind.BRIDGE,
                            contentType = ClipboardType.TEXT,
                        )
                        Log.w(TAG, "Clipboard write retry failed", error)
                        false
                    }
                }
                if (succeeded) return@launch
            }
            // 重试预算已用尽：丢弃待写内容，避免它在很久之后被某次扩展重连重新拾起。
            transferMutex.withLock { pendingClipboardWrite = null }
        }
    }

    private fun shouldRunRemoteSync(): Boolean {
        val settings = repository.loadAdvancedSyncSettings()
        val downloadsEnabled = settings.downloadText || settings.downloadImage || settings.downloadFile
        return settings.enabled &&
            downloadsEnabled &&
            deviceUnlocked &&
            networkAvailable &&
            systemBridge?.asBinder()?.isBinderAlive == true
    }

    private fun resolveAutomaticSyncState(): Int {
        val settings = repository.loadAdvancedSyncSettings()
        if (!settings.enabled) return BridgeContract.AUTOMATIC_SYNC_DISABLED
        val serverProfiles = repository.loadServerProfilesResult()
        if (serverProfiles.credentialsUnavailable) {
            return BridgeContract.AUTOMATIC_SYNC_SERVER_CREDENTIALS_UNAVAILABLE
        }
        if (serverProfiles.profiles.activeServer == null) {
            return BridgeContract.AUTOMATIC_SYNC_SERVER_NOT_CONFIGURED
        }
        if (!deviceUnlocked) return BridgeContract.AUTOMATIC_SYNC_WAITING_FOR_UNLOCK

        val network = connectivityManager.activeNetwork
            ?: return BridgeContract.AUTOMATIC_SYNC_WAITING_FOR_NETWORK
        val capabilities = connectivityManager.getNetworkCapabilities(network)
            ?: return BridgeContract.AUTOMATIC_SYNC_WAITING_FOR_NETWORK
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return BridgeContract.AUTOMATIC_SYNC_WAITING_FOR_NETWORK
        }
        if (settings.wifiOnly && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return BridgeContract.AUTOMATIC_SYNC_WAITING_FOR_WIFI
        }
        if (signalRFailure != null || pendingUploadFailure != null || remoteTransferFailure != null) {
            return BridgeContract.AUTOMATIC_SYNC_ERROR
        }

        val downloadsEnabled = settings.downloadText || settings.downloadImage || settings.downloadFile
        return if (downloadsEnabled && !signalRConnected) {
            BridgeContract.AUTOMATIC_SYNC_CONNECTING
        } else {
            BridgeContract.AUTOMATIC_SYNC_RUNNING
        }
    }

    private fun resolveAutomaticSyncError(): Int = when (
        pendingUploadFailure ?: remoteTransferFailure ?: signalRFailure
    ) {
        SyncFailureKind.AUTHENTICATION -> BridgeContract.AUTOMATIC_SYNC_ERROR_AUTHENTICATION
        SyncFailureKind.NETWORK -> BridgeContract.AUTOMATIC_SYNC_ERROR_NETWORK
        SyncFailureKind.TLS -> BridgeContract.AUTOMATIC_SYNC_ERROR_TLS
        SyncFailureKind.SERVER -> BridgeContract.AUTOMATIC_SYNC_ERROR_SERVER
        SyncFailureKind.STORAGE -> BridgeContract.AUTOMATIC_SYNC_ERROR_STORAGE
        SyncFailureKind.CONTENT -> BridgeContract.AUTOMATIC_SYNC_ERROR_CONTENT
        SyncFailureKind.BRIDGE -> BridgeContract.AUTOMATIC_SYNC_ERROR_BRIDGE
        SyncFailureKind.UNKNOWN -> BridgeContract.AUTOMATIC_SYNC_ERROR_UNKNOWN
        null -> BridgeContract.AUTOMATIC_SYNC_ERROR_NONE
    }

    private fun refreshDeviceUnlockedState() {
        updateDeviceUnlockedState(isDeviceUnlocked())
    }

    private fun updateDeviceUnlockedState(unlocked: Boolean) {
        if (deviceUnlocked == unlocked) return
        deviceUnlocked = unlocked
        Log.i(TAG, "Device unlocked state changed: $deviceUnlocked")
        if (!unlocked) {
            clipboardWriteRetryJob?.cancel()
            clipboardWriteRetryJob = null
            val settings = repository.loadAdvancedSyncSettings()
            remoteResumePolicy.onAutomaticConditionLost(settings.receivePausedRemoteChanges)
            if (!settings.receivePausedRemoteChanges) pendingClipboardWrite = null
        }
        requestPendingTextUpload()
        requestClipboardWriteRetry()
        requestRemoteSyncRestart()
    }

    private fun refreshNetworkAvailability() {
        val available = isNetworkAvailable()
        if (networkAvailable == available) return
        networkAvailable = available
        val settings = repository.loadAdvancedSyncSettings()
        if (!available) {
            remoteResumePolicy.onAutomaticConditionLost(settings.receivePausedRemoteChanges)
            if (!settings.receivePausedRemoteChanges) pendingClipboardWrite = null
        }
        if (!available && settings.enabled) {
            scope.launch {
                recordAutomaticSyncEvent(AutomaticSyncEventKind.WAITING_FOR_NETWORK)
            }
        }
        Log.i(TAG, "Network availability changed: $networkAvailable")
        requestPendingTextUpload()
        requestRemoteSyncRestart()
    }

    private fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        val settings = repository.loadAdvancedSyncSettings()
        return !settings.wifiOnly || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun isDeviceUnlocked(): Boolean {
        val interactive = getSystemService(PowerManager::class.java).isInteractive
        val locked = getSystemService(KeyguardManager::class.java).isDeviceLocked
        return interactive && !locked
    }

    private fun disconnectSystemBridge(restartRemoteSync: Boolean = true) {
        val current = systemBridge
        systemBridge = null
        clipboardWriteRetryJob?.cancel()
        clipboardWriteRetryJob = null
        val settings = repository.loadAdvancedSyncSettings()
        // 扩展 Binder 掉线同样会让自动同步循环停摆，必须与锁屏、断网同等对待。
        // 否则重新注册后会跳过基线，把停机期间云端产生的旧内容写进剪贴板。
        remoteResumePolicy.onAutomaticConditionLost(settings.receivePausedRemoteChanges)
        if (!settings.receivePausedRemoteChanges) pendingClipboardWrite = null
        if (current != null) {
            current.asBinder().unlinkToDeath(bridgeDeathRecipient, 0)
            scope.launch {
                recordAutomaticSyncEvent(AutomaticSyncEventKind.EXTENSION_DISCONNECTED)
            }
        }
        if (restartRemoteSync) requestRemoteSyncRestart()
    }

    private fun recordAutomaticSyncEvent(
        kind: AutomaticSyncEventKind,
        failure: SyncFailureKind? = null,
        contentType: ClipboardType? = null,
        ageMillis: Long? = null,
    ) {
        runCatching { automaticSyncEvents.record(kind, failure, contentType, ageMillis) }
            .onFailure { Log.w(TAG, "Unable to record automatic sync event", it) }
    }

    private fun enforceSystemUiCaller() {
        enforceCallerPackage(BridgeContract.SYSTEM_UI_PACKAGE)
    }

    private fun enforceHostCaller() {
        enforceCallerPackage(BridgeContract.HOST_PACKAGE)
    }

    private fun enforceCallerPackage(expectedPackage: String) {
        val packages = packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
        if (expectedPackage !in packages) {
            throw SecurityException("Caller is not allowed")
        }
    }

    private companion object {
        const val TAG = "SystemBridgeService"
        const val FALLBACK_POLL_INTERVAL_MILLIS = 5 * 60 * 1_000L

        /**
         * 补查时只接收「刚刚」到达云端的内容：早于判定时刻这么多毫秒即视为停机期间
         * 积压的旧内容，归档进历史而不写剪贴板。容忍时钟采样误差与短期抖动。
         */
        const val STALE_CONTENT_GRACE_MILLIS = 5_000L

        /** 服务器时钟偏移超过这个量级就认为不可信（单位错误、系统时钟错乱），整体降级到基线。 */
        const val MAX_TRUSTED_CLOCK_OFFSET_MILLIS = 24 * 60 * 60 * 1_000L
        val CLIPBOARD_WRITE_RETRY_DELAYS_MILLIS = longArrayOf(1_000L, 5_000L, 15_000L, 30_000L)
        val RECONNECT_DELAYS_MILLIS = longArrayOf(
            5_000L,
            15_000L,
            30_000L,
            60_000L,
            120_000L,
            300_000L,
        )
    }
}
