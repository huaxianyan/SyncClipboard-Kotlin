package com.neko7ina.syncclipboard.bridge

object BridgeContract {
    const val PROTOCOL_VERSION = 2
    const val REGISTERED = 0
    const val INCOMPATIBLE = 1

    const val CONNECTION_DISCONNECTED = 0
    const val CONNECTION_READY = 1
    const val CONNECTION_INCOMPATIBLE = 2

    const val AUTOMATIC_SYNC_DISABLED = 0
    const val AUTOMATIC_SYNC_RUNNING = 1
    const val AUTOMATIC_SYNC_WAITING_FOR_WIFI = 2
    const val AUTOMATIC_SYNC_WAITING_FOR_NETWORK = 3
    const val AUTOMATIC_SYNC_WAITING_FOR_UNLOCK = 4
    const val AUTOMATIC_SYNC_CONNECTING = 5
    const val AUTOMATIC_SYNC_ERROR = 6
    const val AUTOMATIC_SYNC_SERVER_NOT_CONFIGURED = 7

    const val HOST_PACKAGE = "com.neko7ina.syncclipboard"
    const val EXTENSION_PACKAGE = "com.neko7ina.syncclipboard.extension"
    const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    const val HOST_SERVICE_CLASS =
        "com.neko7ina.syncclipboard.sync.SystemBridgeService"
}
