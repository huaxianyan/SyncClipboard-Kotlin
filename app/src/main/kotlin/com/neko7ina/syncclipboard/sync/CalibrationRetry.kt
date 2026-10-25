package com.neko7ina.syncclipboard.sync

import kotlinx.coroutines.delay

/**
 * 时钟校准的重试外壳。
 *
 * 解锁或网络刚恢复的瞬间，系统已经报告有网，实际请求仍可能失败。一次失败就会让
 * 服务器时钟偏移为空，暂停恢复的判定退化成哈希基线：内容照样不会写进剪贴板，但要多走
 * 一次网络往返，诊断里也会多出一条 `baseline_established`，看起来像是判据没生效。
 * 重试能把这种瞬时失败挡在降级之前。
 *
 * 做成独立函数而不是内联在服务里，是为了让「试几次、什么条件下停」能在单元测试里覆盖，
 * 不必起一个带网络的服务。
 *
 * @param retryDelaysMillis 每次重试前等待的毫秒数，数组长度就是最多重试次数
 * @param attempt 一次校准，成功返回偏移，失败返回 null
 * @param shouldContinue 每次重试前询问是否还该继续，例如循环已经被取消时
 * @param onRetry 每次重试前回调，用于记一条日志；首次就成功时不会触发
 */
internal suspend fun retryUntilCalibrated(
    retryDelaysMillis: LongArray,
    attempt: suspend () -> Long?,
    shouldContinue: () -> Boolean = { true },
    onRetry: (Long) -> Unit = {},
): Long? {
    attempt()?.let { return it }
    for (retryDelayMillis in retryDelaysMillis) {
        if (!shouldContinue()) return null
        onRetry(retryDelayMillis)
        delay(retryDelayMillis)
        attempt()?.let { return it }
    }
    return null
}
