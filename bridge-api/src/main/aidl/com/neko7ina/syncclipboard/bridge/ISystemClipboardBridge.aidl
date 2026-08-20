package com.neko7ina.syncclipboard.bridge;

interface ISystemClipboardBridge {
    void setClipboardText(String text, String sourceHash);
    int getProtocolVersion();
}
