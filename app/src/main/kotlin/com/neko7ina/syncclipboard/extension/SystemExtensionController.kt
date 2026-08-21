package com.neko7ina.syncclipboard.extension

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.neko7ina.syncclipboard.bridge.BridgeContract
import com.neko7ina.syncclipboard.bridge.ISyncBridgeService

class SystemExtensionController(
    context: Context,
    private val onStateChanged: (SystemExtensionState) -> Unit,
) {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var service: ISyncBridgeService? = null
    private var bound = false

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, REFRESH_INTERVAL_MILLIS)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = ISyncBridgeService.Stub.asInterface(binder)
            refresh()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
            refresh()
        }
    }

    fun start() {
        if (!bound) {
            val intent = Intent().setComponent(
                ComponentName(context, BridgeContract.HOST_SERVICE_CLASS),
            )
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
        handler.removeCallbacks(refreshRunnable)
        refreshRunnable.run()
    }

    fun stop() {
        handler.removeCallbacks(refreshRunnable)
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
        service = null
    }

    fun refresh() {
        val installed = isTrustedExtensionInstalled()
        runCatching { service?.updateExtensionAvailability(installed) }
        val status = when {
            !installed -> SystemExtensionStatus.NOT_INSTALLED
            service == null -> SystemExtensionStatus.INSTALLED_NOT_CONNECTED
            else -> when (runCatching { service?.connectionState }.getOrNull()) {
                BridgeContract.CONNECTION_READY -> SystemExtensionStatus.READY
                BridgeContract.CONNECTION_INCOMPATIBLE -> SystemExtensionStatus.INCOMPATIBLE
                else -> SystemExtensionStatus.INSTALLED_NOT_CONNECTED
            }
        }
        val lastEventTime = runCatching { service?.lastClipboardEventTime ?: 0L }.getOrDefault(0L)
        val lastSyncTime = runCatching { service?.lastSuccessfulSyncTime ?: 0L }.getOrDefault(0L)
        val automaticSyncState = runCatching { service?.automaticSyncState }
            .getOrNull()
            .toAutomaticSyncRuntimeState()
        val automaticSyncError = runCatching { service?.automaticSyncError }
            .getOrNull()
            .toAutomaticSyncError()
        onStateChanged(
            SystemExtensionState(
                status,
                lastEventTime,
                lastSyncTime,
                automaticSyncState,
                automaticSyncError,
            ),
        )
    }

    fun reloadConfiguration() {
        runCatching { service?.reloadConfiguration() }
        refresh()
    }

    private fun isTrustedExtensionInstalled(): Boolean {
        val host = packageInfo(BridgeContract.HOST_PACKAGE) ?: return false
        val extension = packageInfo(BridgeContract.EXTENSION_PACKAGE) ?: return false
        val hostSigners = signerCertificates(host)
        return hostSigners.isNotEmpty() && hostSigners == signerCertificates(extension)
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(packageName: String) = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }
    }.getOrNull()

    private fun Int?.toAutomaticSyncRuntimeState(): AutomaticSyncRuntimeState = when (this) {
        BridgeContract.AUTOMATIC_SYNC_DISABLED -> AutomaticSyncRuntimeState.DISABLED
        BridgeContract.AUTOMATIC_SYNC_RUNNING -> AutomaticSyncRuntimeState.RUNNING
        BridgeContract.AUTOMATIC_SYNC_WAITING_FOR_WIFI -> AutomaticSyncRuntimeState.WAITING_FOR_WIFI
        BridgeContract.AUTOMATIC_SYNC_WAITING_FOR_NETWORK -> AutomaticSyncRuntimeState.WAITING_FOR_NETWORK
        BridgeContract.AUTOMATIC_SYNC_WAITING_FOR_UNLOCK -> AutomaticSyncRuntimeState.WAITING_FOR_UNLOCK
        BridgeContract.AUTOMATIC_SYNC_CONNECTING -> AutomaticSyncRuntimeState.CONNECTING
        BridgeContract.AUTOMATIC_SYNC_ERROR -> AutomaticSyncRuntimeState.ERROR
        BridgeContract.AUTOMATIC_SYNC_SERVER_NOT_CONFIGURED ->
            AutomaticSyncRuntimeState.SERVER_NOT_CONFIGURED
        else -> AutomaticSyncRuntimeState.UNKNOWN
    }

    private fun Int?.toAutomaticSyncError(): AutomaticSyncError = when (this) {
        BridgeContract.AUTOMATIC_SYNC_ERROR_NONE -> AutomaticSyncError.NONE
        BridgeContract.AUTOMATIC_SYNC_ERROR_AUTHENTICATION -> AutomaticSyncError.AUTHENTICATION
        BridgeContract.AUTOMATIC_SYNC_ERROR_NETWORK -> AutomaticSyncError.NETWORK
        BridgeContract.AUTOMATIC_SYNC_ERROR_TLS -> AutomaticSyncError.TLS
        BridgeContract.AUTOMATIC_SYNC_ERROR_SERVER -> AutomaticSyncError.SERVER
        BridgeContract.AUTOMATIC_SYNC_ERROR_STORAGE -> AutomaticSyncError.STORAGE
        BridgeContract.AUTOMATIC_SYNC_ERROR_CONTENT -> AutomaticSyncError.CONTENT
        else -> AutomaticSyncError.UNKNOWN
    }

    private fun signerCertificates(info: android.content.pm.PackageInfo): Set<String> {
        val signingInfo = info.signingInfo ?: return emptySet()
        val signatures = if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners
        } else {
            signingInfo.signingCertificateHistory
        }
        return signatures.map { it.toCharsString() }.toSet()
    }

    private companion object {
        const val REFRESH_INTERVAL_MILLIS = 2_000L
    }
}

data class SystemExtensionState(
    val status: SystemExtensionStatus,
    val lastClipboardEventTime: Long = 0L,
    val lastSuccessfulSyncTime: Long = 0L,
    val automaticSyncState: AutomaticSyncRuntimeState = AutomaticSyncRuntimeState.UNKNOWN,
    val automaticSyncError: AutomaticSyncError = AutomaticSyncError.NONE,
)

enum class AutomaticSyncError {
    NONE,
    AUTHENTICATION,
    NETWORK,
    TLS,
    SERVER,
    STORAGE,
    CONTENT,
    UNKNOWN,
}

enum class AutomaticSyncRuntimeState {
    UNKNOWN,
    DISABLED,
    RUNNING,
    WAITING_FOR_WIFI,
    WAITING_FOR_NETWORK,
    WAITING_FOR_UNLOCK,
    CONNECTING,
    ERROR,
    SERVER_NOT_CONFIGURED,
}

enum class SystemExtensionStatus {
    NOT_INSTALLED,
    INSTALLED_NOT_CONNECTED,
    INCOMPATIBLE,
    READY,
}
