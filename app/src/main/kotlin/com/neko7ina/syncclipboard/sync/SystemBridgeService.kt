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
import android.os.SystemClock
import android.util.Log
import com.neko7ina.syncclipboard.bridge.BridgeContract
import com.neko7ina.syncclipboard.bridge.ISyncBridgeService
import com.neko7ina.syncclipboard.bridge.ISystemClipboardBridge
import com.neko7ina.syncclipboard.data.SettingsRepository
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

    @Volatile
    private var deviceUnlocked = false

    @Volatile
    private var networkAvailable = false

    @Volatile
    private var signalRConnected = false

    @Volatile
    private var signalRFailed = false

    @Volatile
    private var automaticTransferFailed = false

    @Volatile
    private var pendingClipboardText: String? = null

    private var pendingUploadJob: Job? = null
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
            Log.i(TAG, "System bridge registered with protocol $protocolVersion")
            incompatibleBridgeDetected = false
            disconnectSystemBridge(restartRemoteSync = false)
            systemBridge = bridge
            runCatching { bridge.asBinder().linkToDeath(bridgeDeathRecipient, 0) }
                .onFailure {
                    systemBridge = null
                    return BridgeContract.INCOMPATIBLE
                }
            requestPendingTextUpload()
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
            if (!settings.enabled || !settings.uploadText) {
                clearPendingText()
            } else if (pendingClipboardText == null) {
                pendingClipboardText = repository.loadPendingAutomaticText()
            }
            signalRFailed = false
            automaticTransferFailed = false
            refreshNetworkAvailability()
            requestPendingTextUpload()
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
    }

    override fun onCreate() {
        super.onCreate()
        repository = SettingsRepository(this, reloadForAnotherProcess = true)
        val settings = repository.loadAdvancedSyncSettings()
        pendingClipboardText = if (settings.enabled && settings.uploadText) {
            repository.loadPendingAutomaticText()
        } else {
            repository.clearPendingAutomaticText()
            null
        }
        deviceUnlocked = isDeviceUnlocked()
        networkAvailable = isNetworkAvailable()
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
    }

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
        val text = pendingClipboardText ?: return true
        val settings = repository.loadAdvancedSyncSettings()
        if (!settings.enabled || !settings.uploadText) {
            clearPendingText()
            return true
        }
        val previousHash = repository.loadLastAutomaticRemoteHash()
        return runCatching {
            ClipboardTransferService(this).uploadTextIfChanged(text, previousHash)
        }.fold(
            onSuccess = { hash ->
                automaticTransferFailed = false
                if (hash != null) repository.saveLastAutomaticRemoteHash(hash)
                clearPendingText(text)
                true
            },
            onFailure = {
                automaticTransferFailed = true
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
                signalRFailed = false
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
                client.start()
                if (!shouldRunRemoteSync()) return
                failureIndex = 0
                signalRConnected = true
                signalRFailed = false
                Log.i(TAG, "SignalR connected")
                transferMutex.withLock { pollRemoteClipboard() }
                lastFallbackPollAt = SystemClock.elapsedRealtime()
                val closeError = client.awaitClosed()
                throw closeError ?: IllegalStateException("SignalR connection closed")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                signalRConnected = false
                signalRFailed = true
                Log.w(TAG, "SignalR unavailable", error)
                val now = SystemClock.elapsedRealtime()
                if (now - lastFallbackPollAt >= FALLBACK_POLL_INTERVAL_MILLIS) {
                    transferMutex.withLock { pollRemoteClipboard() }
                    lastFallbackPollAt = now
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { client.stop() }
            }
            delay(RECONNECT_DELAYS_MILLIS[failureIndex])
            failureIndex = (failureIndex + 1).coerceAtMost(RECONNECT_DELAYS_MILLIS.lastIndex)
        }
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
                            callback.setClipboardText(text, sourceHash)
                        }
                }.onSuccess { newHash ->
                    automaticTransferFailed = false
                    if (newHash != null) repository.saveLastAutomaticRemoteHash(newHash)
                }.onFailure {
                    automaticTransferFailed = true
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
                callback.setClipboardText(text, sourceHash)
            }
        }.onSuccess { newHash ->
            automaticTransferFailed = false
            if (newHash != null) repository.saveLastAutomaticRemoteHash(newHash)
        }.onFailure {
            automaticTransferFailed = true
            Log.w(TAG, "Automatic content download failed", it)
            if (!callback.asBinder().isBinderAlive) disconnectSystemBridge()
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
        if (repository.loadServer() == null) {
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
        if (signalRFailed || automaticTransferFailed) {
            return BridgeContract.AUTOMATIC_SYNC_ERROR
        }

        val downloadsEnabled = settings.downloadText || settings.downloadImage || settings.downloadFile
        return if (downloadsEnabled && !signalRConnected) {
            BridgeContract.AUTOMATIC_SYNC_CONNECTING
        } else {
            BridgeContract.AUTOMATIC_SYNC_RUNNING
        }
    }

    private fun refreshDeviceUnlockedState() {
        updateDeviceUnlockedState(isDeviceUnlocked())
    }

    private fun updateDeviceUnlockedState(unlocked: Boolean) {
        if (deviceUnlocked == unlocked) return
        deviceUnlocked = unlocked
        Log.i(TAG, "Device unlocked state changed: $deviceUnlocked")
        requestPendingTextUpload()
        requestRemoteSyncRestart()
    }

    private fun refreshNetworkAvailability() {
        val available = isNetworkAvailable()
        if (networkAvailable == available) return
        networkAvailable = available
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
        current?.asBinder()?.unlinkToDeath(bridgeDeathRecipient, 0)
        if (restartRemoteSync) requestRemoteSyncRestart()
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
