package com.neko7ina.syncclipboard.sync

/**
 * 「这条远端内容是不是暂停期间提交的」。
 *
 * 判据只比一个量：内容的提交时刻（`syncedAt`，服务器时钟）是否早于本轮恢复同步的时刻。
 * 早于就属于暂停窗口里积压下来的，按产品定义只归档进本地历史，不写系统剪贴板。
 *
 * 与 [StaleRemoteContentPolicy] 的分工：那个是拿 `createTime` 与「现在」比，只能回答
 * 「多久以前第一次出现」，是同 hash 重复提交就不刷新的旧口径。只要服务端给了
 * `syncedAt`，就以本策略为准。
 *
 * 推送与补查共用同一套判定。旧实现把推送排除在外，理由是「推送本身就是刚刚的证据」，
 * 那个理由是 `createTime` 口径下的补丁：重新提交一条以前提交过的内容时它同样很老。
 * `syncedAt` 会在每次提交时刷新，推送与补查因此不再需要分叉。
 */
internal object PauseWindowPolicy {

    sealed interface Verdict {
        /** 早于暂停窗口起点，`ageMillis` 是它比起点早了多少（已扣除容差）。 */
        data class Skip(val ageMillis: Long) : Verdict

        /** 落在窗口起点之后，正常写入。 */
        data object Fresh : Verdict

        /** 缺判据（老服务端没有 `syncedAt`、尚无窗口起点、时钟偏移不可信）。 */
        data object Unavailable : Verdict
    }

    /**
     * @param syncedAtMillis 内容提交时刻（服务器时钟）
     * @param windowStartMillis 本轮恢复同步的时刻（本机时钟）
     * @param serverClockOffsetMillis 服务器时钟相对本机时钟的偏移，用于把提交时刻换到本机时区
     * @param toleranceMillis 容差，吸收时钟采样误差与循环启动抖动。恰好等于阈值按「不算暂停期间」处理
     */
    fun judge(
        syncedAtMillis: Long?,
        windowStartMillis: Long?,
        serverClockOffsetMillis: Long?,
        toleranceMillis: Long,
    ): Verdict {
        require(toleranceMillis >= 0L) { "Tolerance must not be negative" }
        if (syncedAtMillis == null || windowStartMillis == null || serverClockOffsetMillis == null) {
            return Verdict.Unavailable
        }
        val submittedAtMillis = syncedAtMillis - serverClockOffsetMillis
        val threshold = windowStartMillis - toleranceMillis
        return if (submittedAtMillis < threshold) {
            Verdict.Skip(threshold - submittedAtMillis)
        } else {
            Verdict.Fresh
        }
    }
}
