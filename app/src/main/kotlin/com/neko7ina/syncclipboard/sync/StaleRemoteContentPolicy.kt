package com.neko7ina.syncclipboard.sync

/**
 * 「补查到的远端内容能不能写进剪贴板」的时间判据，"createTime" 口径。
 *
 * 官方服务端的历史记录接口 `GET /api/history/{Type}-{Hash}` 会返回该内容首次到达
 * 云端的时刻（`createTime`）。它与「最近一次提交时刻」不是同一个量：同 hash 重复提交
 * 只刷新 `lastModified` / `lastAccessed`，`createTime` 不变。
 *
 * 因此这只是**降级路径**。只要内容自带 `syncedAt`，就以
 * [PauseWindowPolicy] 的窗口判定为准：那个能直接回答「是不是恢复同步之前提交的」。
 * 本策略只在老服务端（`v3.3.1` 及更早）与没有落 `syncedAt` 的 WebDAV/S3 部署上生效。
 *
 * 刻意做成纯函数：输入全部由调用方解析好，两端时钟、宽限、恰好相等的边界都能在
 * 单元测试里覆盖，不必依赖真机复现。这一点正是这个功能过去反复出问题的原因。
 */
internal object StaleRemoteContentPolicy {

    /**
     * @param judgedAtMillis 本次判定的时刻（本机时钟）
     * @param serverClockOffsetMillis 服务器时钟相对本机时钟的偏移
     * @param contentCreatedAtMillis 远端内容的 `createTime`（服务器时钟）
     * @param graceMillis 宽限，用于吸收时钟采样误差与短期抖动
     * @return 判定为陈旧时返回「内容早于判定时刻」的毫秒数，否则返回 null
     */
    fun staleAgeMillis(
        judgedAtMillis: Long,
        serverClockOffsetMillis: Long,
        contentCreatedAtMillis: Long,
        graceMillis: Long,
    ): Long? {
        require(graceMillis >= 0L) { "Grace must not be negative" }
        val age = judgedAtMillis + serverClockOffsetMillis - contentCreatedAtMillis
        return age.takeIf { it > graceMillis }
    }
}
