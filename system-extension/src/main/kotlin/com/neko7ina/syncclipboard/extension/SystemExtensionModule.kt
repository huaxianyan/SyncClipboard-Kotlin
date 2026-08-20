package com.neko7ina.syncclipboard.extension

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.neko7ina.syncclipboard.bridge.BridgeContract
import com.neko7ina.syncclipboard.bridge.ISyncBridgeService
import com.neko7ina.syncclipboard.bridge.ISystemClipboardBridge
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.util.concurrent.atomic.AtomicBoolean

class SystemExtensionModule : XposedModule() {
    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName != BridgeContract.SYSTEM_UI_PACKAGE) return

        val applicationClass = param.applicationInfo.className
            ?.let { runCatching { param.defaultClassLoader.loadClass(it) }.getOrNull() }
            ?: Application::class.java
        val onCreate = runCatching { applicationClass.getDeclaredMethod("onCreate") }
            .getOrElse { Application::class.java.getDeclaredMethod("onCreate") }

        hook(onCreate).intercept { chain ->
            val result = chain.proceed()
            (chain.thisObject as? Application)?.let(SystemClipboardConnector::start)
            result
        }
    }
}

private object SystemClipboardConnector {
    private val started = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var host: ISyncBridgeService? = null
    private var bound = false
    private var suppressedText: String? = null

    private val bridgeCallback = object : ISystemClipboardBridge.Stub() {
        override fun setClipboardText(text: String, sourceHash: String) {
            handler.post {
                val appContext = context ?: return@post
                suppressedText = text
                appContext.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("SyncClipboard", text))
            }
        }

        override fun getProtocolVersion(): Int = BridgeContract.PROTOCOL_VERSION
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            host = ISyncBridgeService.Stub.asInterface(service)
            runCatching {
                host?.registerSystemBridge(BridgeContract.PROTOCOL_VERSION, bridgeCallback)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            host = null
        }

        override fun onBindingDied(name: ComponentName) {
            resetBinding()
        }

        override fun onNullBinding(name: ComponentName) {
            resetBinding()
        }
    }

    private val reconnectRunnable = Runnable(::bindHost)

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        val appContext = context ?: return@OnPrimaryClipChangedListener
        val clipboard = appContext.getSystemService(ClipboardManager::class.java)
        val clip = clipboard.primaryClip ?: return@OnPrimaryClipChangedListener
        if (clip.itemCount == 0) return@OnPrimaryClipChangedListener
        val item = clip.getItemAt(0)
        if (item.uri != null) return@OnPrimaryClipChangedListener
        val text = item.text?.toString() ?: item.htmlText?.toString()
            ?: return@OnPrimaryClipChangedListener
        if (text == suppressedText) {
            suppressedText = null
            return@OnPrimaryClipChangedListener
        }
        val sensitive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true
        } else {
            false
        }
        runCatching { host?.onClipboardText(text, sensitive) }
            .onFailure { resetBinding() }
    }

    fun start(application: Application) {
        if (!started.compareAndSet(false, true)) return
        context = application.applicationContext
        application.getSystemService(ClipboardManager::class.java)
            .addPrimaryClipChangedListener(clipListener)
        bindHost()
    }

    private fun resetBinding() {
        host = null
        if (bound) {
            context?.let { runCatching { it.unbindService(serviceConnection) } }
        }
        bound = false
        scheduleBind()
    }

    private fun scheduleBind() {
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MILLIS)
    }

    private fun bindHost() {
        if (bound) return
        val appContext = context ?: return
        val intent = Intent().setComponent(
            ComponentName(BridgeContract.HOST_PACKAGE, BridgeContract.HOST_SERVICE_CLASS),
        )
        bound = runCatching {
            appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) scheduleBind()
    }

    private const val RECONNECT_DELAY_MILLIS = 5_000L
}
