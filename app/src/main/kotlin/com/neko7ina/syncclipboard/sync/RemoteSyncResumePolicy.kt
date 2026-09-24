package com.neko7ina.syncclipboard.sync

/**
 * 决定「恢复同步时是否必须先重建基线」。
 *
 * 关闭「接收暂停期间的云端更新」后，任何一次自动同步循环的停摆都意味着
 * 这段窗口内云端可能产生了本机尚未见过的新内容。恢复时若直接接收，
 * 这份旧内容会被当成新内容写进剪贴板。
 *
 * 这只是**降级路径**：只要服务端能提供内容的 `createTime`（见
 * [StaleRemoteContentPolicy]），就改由内容时间去判定，不再依赖这里的标志，
 * 也就不再依赖 [onAutomaticConditionLost] 是否覆盖了循环存活条件的**全部**来源
 * ——那正是这条路径历史上反复漏判的根因。只有拿不到内容时间时才回落到基线。
 */
internal class RemoteSyncResumePolicy {
    private var receivePausedRemoteChanges = true
    private var remoteSyncEnabled = false
    private var baselineRequired = false
    private var generation = 0L

    @Synchronized
    fun initialize(
        receivePausedRemoteChanges: Boolean,
        remoteSyncEnabled: Boolean,
    ) {
        this.receivePausedRemoteChanges = receivePausedRemoteChanges
        this.remoteSyncEnabled = remoteSyncEnabled
        if (receivePausedRemoteChanges) {
            baselineRequired = false
        } else {
            baselineRequired = true
            generation++
        }
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
            generation++
        }
        this.receivePausedRemoteChanges = receivePausedRemoteChanges
        this.remoteSyncEnabled = remoteSyncEnabled
    }

    @Synchronized
    fun onAutomaticConditionLost(receivePausedRemoteChanges: Boolean) {
        if (!receivePausedRemoteChanges) {
            baselineRequired = true
            generation++
        }
    }

    /**
     * 是否必须先重建基线。
     *
     * [canJudgeByContentTime] 为 true 时一律返回 false：内容时间比「对齐哈希」更准，
     * 而且不必知道循环是为什么停摆的。基线仅在服务端不提供内容时间（老版本、
     * 纯 WebDAV 部署、时钟不可信）时启用。
     */
    @Synchronized
    fun shouldEstablishBaseline(
        receivePausedRemoteChanges: Boolean,
        lastRemoteHash: String?,
        canJudgeByContentTime: Boolean,
    ): Boolean = !receivePausedRemoteChanges &&
        !canJudgeByContentTime &&
        (baselineRequired || lastRemoteHash == null)

    /**
     * 取当前代际。建立基线是阻塞网络请求，开始前先记下代际，
     * 完成时用于判断期间是否又发生了新的条件丢失。
     */
    @Synchronized
    fun baselineGeneration(): Long = generation

    /**
     * 基线建立完成后清理标志。
     *
     * 若取远端内容期间发生了新的自动条件丢失（代际已变化），则**保留**标志，
     * 让下一轮重新建立基线，避免这次「陈旧完成」把新暂停窗口刚置上的标志一起清掉。
     */
    @Synchronized
    fun markBaselineEstablished(generationAtStart: Long): Boolean {
        if (generation != generationAtStart) return false
        baselineRequired = false
        return true
    }
}
