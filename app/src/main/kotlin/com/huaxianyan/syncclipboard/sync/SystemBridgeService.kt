package com.huaxianyan.syncclipboard.sync

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.huaxianyan.syncclipboard.bridge.BridgeContract
import com.huaxianyan.syncclipboard.bridge.ISyncBridgeService
import com.huaxianyan.syncclipboard.bridge.ISystemClipboardBridge
import com.huaxianyan.syncclipboard.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SystemBridgeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val transferMutex = Mutex()

    @Volatile
    private var systemBridge: ISystemClipboardBridge? = null

    @Volatile
    private var lastClipboardEventTime = 0L

    @Volatile
    private var incompatibleBridgeDetected = false

    @Volatile
    private var repository = SettingsRepository(this, reloadForAnotherProcess = true)

    private var pollingJob: Job? = null
    private val bridgeDeathRecipient = IBinder.DeathRecipient(::disconnectSystemBridge)

    private val binder = object : ISyncBridgeService.Stub() {
        override fun registerSystemBridge(
            protocolVersion: Int,
            bridge: ISystemClipboardBridge,
        ): Int {
            enforceSystemUiCaller()
            if (protocolVersion != BridgeContract.PROTOCOL_VERSION) {
                incompatibleBridgeDetected = true
                return BridgeContract.INCOMPATIBLE
            }
            incompatibleBridgeDetected = false
            disconnectSystemBridge()
            systemBridge = bridge
            runCatching { bridge.asBinder().linkToDeath(bridgeDeathRecipient, 0) }
                .onFailure {
                    systemBridge = null
                    return BridgeContract.INCOMPATIBLE
                }
            ensurePolling()
            return BridgeContract.REGISTERED
        }

        override fun unregisterSystemBridge() {
            enforceSystemUiCaller()
            disconnectSystemBridge()
        }

        override fun onClipboardText(text: String, sensitive: Boolean) {
            enforceSystemUiCaller()
            this@SystemBridgeService.lastClipboardEventTime = System.currentTimeMillis()
            val settings = repository.loadAdvancedSyncSettings()
            if (!settings.enabled || !settings.uploadText) return
            if (sensitive && settings.ignoreSensitiveContent) return
            scope.launch {
                transferMutex.withLock {
                    runCatching { ClipboardTransferService(this@SystemBridgeService).uploadText(text) }
                        .onSuccess { hash ->
                            if (hash != null) repository.saveLastAutomaticRemoteHash(hash)
                        }
                        .onFailure { Log.w(TAG, "Automatic text upload failed", it) }
                }
            }
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
            return lastClipboardEventTime
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
            ensurePolling()
        }

        override fun updateExtensionAvailability(installed: Boolean) {
            enforceHostCaller()
            if (!installed) disconnectSystemBridge()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        disconnectSystemBridge()
        scope.cancel()
        super.onDestroy()
    }

    private fun ensurePolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch {
            while (isActive) {
                val settings = repository.loadAdvancedSyncSettings()
                if (settings.enabled && settings.downloadText && systemBridge != null) {
                    transferMutex.withLock { pollRemoteText() }
                }
                delay(settings.pollingIntervalSeconds * 1_000L)
            }
        }
    }

    private fun pollRemoteText() {
        val callback = systemBridge ?: return
        val previousHash = repository.loadLastAutomaticRemoteHash()
        runCatching {
            ClipboardTransferService(this).downloadTextIfChanged(previousHash) { text, sourceHash ->
                callback.setClipboardText(text, sourceHash)
            }
        }.onSuccess { newHash ->
            if (newHash != null) repository.saveLastAutomaticRemoteHash(newHash)
        }.onFailure {
            Log.w(TAG, "Automatic text download failed", it)
            if (!callback.asBinder().isBinderAlive) disconnectSystemBridge()
        }
    }

    private fun disconnectSystemBridge() {
        val current = systemBridge
        systemBridge = null
        current?.asBinder()?.unlinkToDeath(bridgeDeathRecipient, 0)
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
    }
}
