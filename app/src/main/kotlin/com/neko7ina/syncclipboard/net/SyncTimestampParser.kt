package com.neko7ina.syncclipboard.net

import java.time.Instant
import java.time.OffsetDateTime

/**
 * 解析服务端返回的时间。
 *
 * 官方服务端（SyncClipboard.Server）用 System.Text.Json 序列化 `DateTimeOffset`，
 * 输出形如 `"2026-09-16T11:26:06.6904181+00:00"`——7 位小数秒、带显式 UTC 偏移。
 *
 * 两点实现约束：
 * 1. `Instant.parse` 底层的 ISO_INSTANT 对非 `Z` 偏移的支持是 JDK 12 才补齐的，Android
 *    各版本运行时并不一致，所以按可用性逐个回退，避免时间戳功能在部分设备上静默失效。
 * 2. 不使用 `org.json`——它在 JVM 单元测试下是空壳，会让解析路径无法被测试覆盖。
 *    这里要提取的只是平铺的元数据字段，且 JSON 字符串值内的引号必然被转义
 *    （`\"createTime\":\"` 不会匹配要求纯引号的模式），正则提取足够可靠。
 */
internal object SyncTimestampParser {

    /** 服务端在不同版本与序列化配置下字段名大小写不一致，按优先级逐个尝试。 */
    private val FIELDS = listOf(
        "createTime",
        "CreateTime",
        "lastModified",
        "LastModified",
        "timestamp",
        "Timestamp",
    )

    private val STRING_PATTERNS = FIELDS.map { field ->
        Regex("\"$field\"\\s*:\\s*\"([^\"]+)\"")
    }

    private val NUMBER_PATTERNS = FIELDS.map { field ->
        Regex("\"$field\"\\s*:\\s*(\\d{10,})")
    }

    /**
     * @param raw 原始响应体：可以是 epoch 毫秒数字、ISO-8601 字符串（可带引号），
     *   或包含时间字段的 JSON 对象。
     * @return epoch 毫秒；无法识别时返回 null，调用方据此保持原有行为。
     */
    fun parse(raw: String?): Long? {
        val text = raw?.trim()?.trim('"') ?: return null
        if (text.isEmpty()) return null

        // /api/time 直接返回一个 ISO 字符串，/api/history/{profileId} 返回 JSON 对象。
        parseValue(text)?.let { return it }

        STRING_PATTERNS.forEach { pattern ->
            val match = pattern.find(text) ?: return@forEach
            parseValue(match.groupValues[1])?.let { return it }
        }
        NUMBER_PATTERNS.forEach { pattern ->
            val match = pattern.find(text) ?: return@forEach
            match.groupValues[1].toLongOrNull()?.let { return it }
        }
        return null
    }

    private fun parseValue(value: String): Long? {
        val text = value.trim().trim('"')
        if (text.isEmpty()) return null
        text.toLongOrNull()?.let { return it }
        parseIso(text)?.let { return it }
        val zulu = toZulu(text)
        return if (zulu == text) null else parseIso(zulu)
    }

    private fun parseIso(text: String): Long? =
        runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()

    /** `Instant.parse` 只保证接受 `Z` 结尾，把等价的 `+00:00` 偏移归一化后再试一次。 */
    private fun toZulu(text: String): String = when {
        text.endsWith("+00:00") -> text.dropLast(6) + "Z"
        text.endsWith("+0000") -> text.dropLast(5) + "Z"
        else -> text
    }
}
