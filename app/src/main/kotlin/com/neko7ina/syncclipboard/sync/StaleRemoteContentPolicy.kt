package com.neko7ina.syncclipboard.sync

/**
 * 「补查到的远端内容能不能写进剪贴板」的时间判据。
 *
 * 官方服务端的历史记录接口 `GET /api/history/{Type}-{Hash}` 会返回该内容首次到达
 * 云端的时刻（`createTime`）。补查发生在「本机刚经历了一段看不到云端的窗口」之后，
 * 此刻云端躺着的内容只要不是刚刚到达的，就属于窗口里积压下来的，按产品定义只归档进
 * 本地历史，不写系统剪贴板。
 *
 * 只对补查生效，不要用于实时推送：推送本身就是内容「刚刚」到达的证据，套用判据会把
 * 「重新复制一条以前复制过的内容」误判成积压（它的 `createTime` 同样很老）。
 *
 * 刻意做成纯函数：输入全部由调用方解析好，两端时钟、宽限、恰好相等的边界都能在
 * 单元测试里覆盖，不必依赖真机复现——这一点正是这个功能过去反复出问题的原因。
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
