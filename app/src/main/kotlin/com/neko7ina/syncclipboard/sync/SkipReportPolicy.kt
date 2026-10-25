package com.neko7ina.syncclipboard.sync

/**
 * 同一条积压内容被判多次时，只记录一次。
 *
 * 暂停窗口的起点是「本轮恢复同步的时刻」，每次循环重启都会往后推。因此同一份仍然留在
 * 云端的内容会在每次重启后被重新判定为暂停期间提交。判定本身必须继续（内容确实不该写入），
 * 但诊断事件和历史条目没有必再记第二遍。
 *
 * 哈希在不同路径上大小写不一致，比较时忽略大小写。记得只在「内容被真正写入」后清空，
 * 否则同一份内容以后再次被跳过时就会被永久静默。
 */
internal object SkipReportPolicy {

    /** @param lastReportedHash 上次已记录过的跳过内容，从未记录过时为 null */
    fun shouldReport(hash: String, lastReportedHash: String?): Boolean =
        !hash.equals(lastReportedHash, ignoreCase = true)
}
