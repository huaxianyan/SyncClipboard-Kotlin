package com.neko7ina.syncclipboard.sync

internal class RemoteSyncResumePolicy {
    private var receivePausedRemoteChanges = true
    private var remoteSyncEnabled = false
    private var baselineRequired = false

    @Synchronized
    fun initialize(
        receivePausedRemoteChanges: Boolean,
        remoteSyncEnabled: Boolean,
    ) {
        this.receivePausedRemoteChanges = receivePausedRemoteChanges
        this.remoteSyncEnabled = remoteSyncEnabled
        baselineRequired = !receivePausedRemoteChanges
    }

    @Synchronized
    fun onSettingsChanged(
        receivePausedRemoteChanges: Boolean,
        remoteSyncEnabled: Boolean,
    ) {
        if (
            !receivePausedRemoteChanges &&
            (this.receivePausedRemoteChanges || this.remoteSyncEnabled && !remoteSyncEnabled)
        ) {
            baselineRequired = true
        }
        this.receivePausedRemoteChanges = receivePausedRemoteChanges
        this.remoteSyncEnabled = remoteSyncEnabled
    }

    @Synchronized
    fun onAutomaticConditionLost(receivePausedRemoteChanges: Boolean) {
        if (!receivePausedRemoteChanges) baselineRequired = true
    }

    @Synchronized
    fun shouldEstablishBaseline(
        receivePausedRemoteChanges: Boolean,
        lastRemoteHash: String?,
    ): Boolean = !receivePausedRemoteChanges && (baselineRequired || lastRemoteHash == null)

    @Synchronized
    fun markBaselineEstablished() {
        baselineRequired = false
    }
}
