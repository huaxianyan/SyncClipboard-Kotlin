package com.huaxianyan.syncclipboard.bridge

object BridgeContract {
    const val PROTOCOL_VERSION = 1
    const val REGISTERED = 0
    const val INCOMPATIBLE = 1

    const val CONNECTION_DISCONNECTED = 0
    const val CONNECTION_READY = 1
    const val CONNECTION_INCOMPATIBLE = 2

    const val HOST_PACKAGE = "com.huaxianyan.syncclipboard"
    const val EXTENSION_PACKAGE = "com.huaxianyan.syncclipboard.extension"
    const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    const val HOST_SERVICE_CLASS =
        "com.huaxianyan.syncclipboard.sync.SystemBridgeService"
}
