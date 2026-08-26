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
                reloadForAnotherProcess = true,
            )
            val settings = repository.loadAdvancedSyncSettings()
            remoteResumePolicy.onSettingsChanged(
                receivePausedRemoteChanges = settings.receivePausedRemoteChanges,
                remoteSyncEnabled = settings.enabled && (
                    settings.downloadText || settings.downloadImage || settings.downloadFile
                ),
            )
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
        repository = SettingsRepository(this, reloadForAnotherProcess = true)
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
            ClipboardTransferService(this).uploadTextIfChanged(
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
                if (remoteSyncJob?.isActive == true) Log.i(TAG, "Remote sync stopping")
                remoteSyncJob?.cancel()
                remoteSyncJob = null
                signalRConnected = false
                signalRFailure = null
                if (shouldRunRemoteSync()) {
                    Log.i(TAG, "Remote sync starting")
                    remoteSyncJob = scope.launch { runRemoteSyncLoop() }
                }
            }
        }
    }

    private suspend fun runRemoteSyncLoop() {
        var failureIndex = 0
        var lastFallbackPollAt = 0L
        while (currentCoroutineContext().isActive && shouldRunRemoteSync()) {
            val server = repository.loadServer() ?: return
            val client = SignalRSyncClient(server, ::handleRemoteProfile)
            try {
                val settings = repository.loadAdvancedSyncSettings()
                if (
                    remoteResumePolicy.shouldEstablishBaseline(
                        receivePausedRemoteChanges = settings.receivePausedRemoteChanges,
                        lastRemoteHash = repository.loadLastAutomaticRemoteHash(),
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
                    transferMutex.withLock {
                        val settings = repository.loadAdvancedSyncSettings()
                        if (
                            remoteResumePolicy.shouldEstablishBaseline(
                                receivePausedRemoteChanges = settings.receivePausedRemoteChanges,
                                lastRemoteHash = repository.loadLastAutomaticRemoteHash(),
                            )
                        ) {
                            establishRemoteBaseline(settings)
                        } else {
                            pollRemoteClipboard()
                        }
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
        val transferService = ClipboardTransferService(this)
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
        remoteResumePolicy.markBaselineEstablished()
        remoteTransferFailure = null
    }

    private fun handleRemoteProfile(payload: ClipboardPayload) {
        scope.launch {
            if (!shouldRunRemoteSync()) return@launch
            transferMutex.withLock {
                val callback = systemBridge ?: return@withLock
                val previousHash = repository.loadLastAutomaticRemoteHash()
                val settings = repository.loadAdvancedSyncSettings()
                runCatching {
                    ClipboardTransferService(this@SystemBridgeService)
                        .applyRemoteAutomatically(payload, previousHash, settings) { text, sourceHash ->
                            writeClipboardText(callback, text, sourceHash)
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
        val callback = systemBridge ?: return
        val settings = repository.loadAdvancedSyncSettings()
        if (!settings.enabled) return
        val previousHash = repository.loadLastAutomaticRemoteHash()
        runCatching {
            ClipboardTransferService(this).downloadAutomatically(
                previousHash,
                settings,
            ) { text, sourceHash ->
                writeClipboardText(callback, text, sourceHash)
            }
        }.onSuccess { newHash ->
            remoteTransferFailure = null
            if (newHash != null) {
                repository.saveLastAutomaticRemoteHash(newHash)
                recordAutomaticSyncEvent(AutomaticSyncEventKind.DOWNLOAD_SUCCEEDED)
            }
        }.onFailure {
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
        try {
            callback.setClipboardText(text, sourceHash)
            pendingClipboardWrite = null
        } catch (error: RemoteException) {
            pendingClipboardWrite = PendingClipboardWrite(text, sourceHash)
            requestClipboardWriteRetry()
            throw SyncException(
                "系统扩展暂时无法写入剪贴板，请保持设备解锁",
                error,
                SyncFailureKind.BRIDGE,
            )
        }
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
            remoteResumePolicy.onAutomaticConditionLost(
                repository.loadAdvancedSyncSettings().receivePausedRemoteChanges,
            )
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
    ) {
        runCatching { automaticSyncEvents.record(kind, failure, contentType) }
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
