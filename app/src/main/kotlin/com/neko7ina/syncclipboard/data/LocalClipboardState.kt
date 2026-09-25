package com.neko7ina.syncclipboard.data

/**
 * 本机剪贴板内容的登记值。
 *
 * 「云端上次同步过什么」和「本机剪贴板里现在是什么」是两件事。前者一旦记下就长期躺在存储里，
 * 后者会被用户复制、被别的应用改写、被系统清空。判断「这份远端内容还要不要再写进剪贴板」
 * 必须用后者：只有登记值明确等于远端内容时，重复写入才是真的多余。
 *
 * 登记值唯一的已知失效来源是设备重启：重启后系统剪贴板是空的，而登记值还在。用开机计时器
 * （`SystemClock.elapsedRealtime`）识别：它随重启归零，所以「记录时刻晚于当前时刻」就说明中间
 * 重启过。此时登记值作废、按未知处理，而未知一律**不抑制**写入：把内容写进剪贴板总是安全的，
 * 该内容本来就该出现在那里。
 */
internal object LocalClipboardState {
    fun usableHash(
        nowElapsedRealtimeMillis: Long,
        recordedElapsedRealtimeMillis: Long,
        recordedHash: String?,
    ): String? {
        if (recordedHash.isNullOrBlank()) return null
        if (recordedElapsedRealtimeMillis <= 0L) return null
        if (recordedElapsedRealtimeMillis > nowElapsedRealtimeMillis) return null
        return recordedHash
    }
}
