package com.neko7ina.syncclipboard.bridge;

import com.neko7ina.syncclipboard.bridge.ISystemClipboardBridge;

interface ISyncBridgeService {
    int registerSystemBridge(int protocolVersion, ISystemClipboardBridge bridge);
    void unregisterSystemBridge();
    void onClipboardText(String text, boolean sensitive);
    int getConnectionState();
    long getLastClipboardEventTime();
    long getLastSuccessfulSyncTime();
    void reloadConfiguration();
    void updateExtensionAvailability(boolean installed);
}
