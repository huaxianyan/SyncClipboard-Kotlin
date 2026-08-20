package com.huaxianyan.syncclipboard.extension

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.huaxianyan.syncclipboard.bridge.BridgeContract
import com.huaxianyan.syncclipboard.bridge.ISyncBridgeService

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
        onStateChanged(SystemExtensionState(status, lastEventTime, lastSyncTime))
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
)

enum class SystemExtensionStatus {
    NOT_INSTALLED,
    INSTALLED_NOT_CONNECTED,
    INCOMPATIBLE,
    READY,
}
