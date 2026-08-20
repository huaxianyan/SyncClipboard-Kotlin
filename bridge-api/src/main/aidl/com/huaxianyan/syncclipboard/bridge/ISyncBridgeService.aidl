package com.huaxianyan.syncclipboard.bridge;

import com.huaxianyan.syncclipboard.bridge.ISystemClipboardBridge;

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
