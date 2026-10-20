package com.quake.alert.data.source

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * 上游时间字符串的统一解析。
 *
 * Wolfx 下发的时间有两种形态，必须区分开：
 * - 带时区：`2026-08-29T06:07:28Z`、`2026-08-29T06:07:28+08:00`
 * - 不带时区：`2026-08-29 06:07:28`（**北京时间**，上游不会附加时区后缀）
 *
 * 之前两个解码器都先尝试"补 Z 按 UTC 解析"，导致不带时区的报文被当成 UTC，
 * 整体偏移 8 小时（列表时间、倒计时、本地估算都会错）。这里按"有无显式时区"分流。
 */
private val CHINA_ZONE = TimeZone.of("Asia/Shanghai")

/** 匹配结尾的 `+08:00` / `+0800` / `-05:00`，排除日期里的连字符。 */
private val OFFSET_SUFFIX = Regex("""[+-]\d{2}:?\d{2}$""")

/**
 * @return epoch millis；无法识别时返回 null（调用方决定回退策略）。
 */
internal fun parseApiTime(raw: String?): Long? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null

    val normalized = text.replace(' ', 'T')
    val hasZone = normalized.endsWith("Z", ignoreCase = true) ||
        OFFSET_SUFFIX.containsMatchIn(normalized)

    if (hasZone) {
        runCatching { Instant.parse(normalized).toEpochMilliseconds() }
            .getOrNull()?.let { return it }
    } else {
        runCatching {
            LocalDateTime.parse(normalized).toInstant(CHINA_ZONE).toEpochMilliseconds()
        }.getOrNull()?.let { return it }
    }

    // 纯数字时间戳：> 1e12 视为毫秒，否则视为秒
    text.toLongOrNull()?.let { return if (it > 1_000_000_000_000L) it else it * 1000L }
    return null
}
