package com.huaxianyan.syncclipboard.bridge;

interface ISystemClipboardBridge {
    void setClipboardText(String text, String sourceHash);
    int getProtocolVersion();
}
