package com.quake.alert.util

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private fun Int.pad2(): String = if (this < 10) "0$this" else toString()

/** 按指定时区把 epoch millis 格式化为 `MM-dd HH:mm:ss`。 */
fun formatLocalTime(
    epochMillis: Long,
    zoneId: String = "Asia/Shanghai",
): String {
    val local = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.of(zoneId))
    return "${local.monthNumber.pad2()}-${local.dayOfMonth.pad2()} " +
        "${local.hour.pad2()}:${local.minute.pad2()}:${local.second.pad2()}"
}

/** 把秒数格式化为 `1 分 20 秒` 这类人类可读文本；null 表示无法估算。 */
fun formatCountdown(seconds: Int?): String = when {
    seconds == null -> "未知（未获取定位）"
    seconds <= 0 -> "已到达"
    seconds < 60 -> "$seconds 秒"
    else -> "${seconds / 60} 分 ${seconds % 60} 秒"
}
